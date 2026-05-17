"""
富邦當日沖銷 - WebSocket Manager (Phase 4-2)
管理 Android App 的 WebSocket 連線，支援事件廣播
"""

import asyncio
import json
import logging
from datetime import datetime
from typing import Dict, Set, Optional, Any

from fastapi import WebSocket, WebSocketDisconnect

logger = logging.getLogger(__name__)


# ============================================================================
# ConnectionManager — 管理 WebSocket clients
# ============================================================================
class ConnectionManager:
    """
    WebSocket 連線管理器

    功能：
    - 接受/拒絕 WebSocket 連線
    - 維護已連線 client 清單
    - 向所有 client 廣播事件
    - 向特定 client 發送訊息
    - 連線健康檢查
    """

    def __init__(self):
        # client_id -> WebSocket
        self._active_connections: Dict[str, WebSocket] = {}
        # client_id -> metadata
        self._client_metadata: Dict[str, Dict[str, Any]] = {}
        # asyncio Lock（保護並發存取）
        self._lock = asyncio.Lock()

    # ------------------------------------------------------------------------
    # 連線管理
    # ------------------------------------------------------------------------

    async def connect(self, websocket: WebSocket, client_id: Optional[str] = None) -> str:
        """
        接受新 WebSocket 連線

        Args:
            websocket: FastAPI WebSocket 物件
            client_id: 客戶端 ID（None = 自動產生）

        Returns:
            str — 分配的 client_id
        """
        await websocket.accept()

        async with self._lock:
            if client_id is None:
                client_id = f"client_{datetime.now().strftime('%Y%m%d%H%M%S%f')}"

            self._active_connections[client_id] = websocket
            self._client_metadata[client_id] = {
                "connected_at": datetime.now().isoformat(),
                "last_ping": datetime.now().isoformat(),
                "remote_addr": str(websocket.client.remote_address) if websocket.client else "unknown",
            }

        logger.info(f"WebSocket connected: {client_id} (total: {len(self._active_connections)})")
        return client_id

    async def disconnect(self, client_id: str):
        """斷開 WebSocket 連線"""
        async with self._lock:
            if client_id in self._active_connections:
                del self._active_connections[client_id]
            if client_id in self._client_metadata:
                del self._client_metadata[client_id]

        logger.info(f"WebSocket disconnected: {client_id} (total: {len(self._active_connections)})")

    async def disconnect_all(self):
        """斷開所有 WebSocket 連線"""
        async with self._lock:
            for client_id, ws in list(self._active_connections.items()):
                try:
                    await ws.close()
                except Exception:
                    pass
            self._active_connections.clear()
            self._client_metadata.clear()
        logger.info("All WebSocket connections closed")

    # ------------------------------------------------------------------------
    # 訊息發送
    # ------------------------------------------------------------------------

    async def send_personal_message(self, client_id: str, message: Dict[str, Any]) -> bool:
        """
        向特定 client 發送訊息

        Args:
            client_id: 目標客戶端 ID
            message:   訊息內容（dict，會轉為 JSON）

        Returns:
            bool — 發送是否成功
        """
        async with self._lock:
            websocket = self._active_connections.get(client_id)

        if websocket is None:
            logger.warning(f"Client not found: {client_id}")
            return False

        try:
            await websocket.send_json(message)
            return True
        except Exception as e:
            logger.error(f"Send to {client_id} failed: {e}")
            await self.disconnect(client_id)
            return False

    async def broadcast(self, event_type: str, data: Dict[str, Any]) -> int:
        """
        廣播事件到所有已連線的 clients

        Args:
            event_type: 事件類型（如 "order_update", "condition_triggered"）
            data:       事件資料

        Returns:
            int — 成功發送數量
        """
        payload = {
            "event": event_type,
            "timestamp": datetime.now().isoformat(),
            "data": data,
        }

        success_count = 0
        failed_clients = []

        async with self._lock:
            connections = list(self._active_connections.items())

        for client_id, websocket in connections:
            try:
                await websocket.send_json(payload)
                success_count += 1
            except Exception as e:
                logger.warning(f"Broadcast to {client_id} failed: {e}")
                failed_clients.append(client_id)

        # 移除失敗的連線
        for client_id in failed_clients:
            await self.disconnect(client_id)

        if success_count > 0:
            logger.debug(f"Broadcast {event_type} to {success_count} clients")

        return success_count

    async def broadcast_if(
        self,
        event_type: str,
        data: Dict[str, Any],
        filter_func,
    ) -> int:
        """
        條件廣播（符合 filter_func 條件的 client 才發送）

        Args:
            event_type:  事件類型
            data:        事件資料
            filter_func: 過濾函式 (client_id, metadata) -> bool

        Returns:
            int — 成功發送數量
        """
        payload = {
            "event": event_type,
            "timestamp": datetime.now().isoformat(),
            "data": data,
        }

        success_count = 0

        async with self._lock:
            connections = [
                (cid, ws, self._client_metadata.get(cid, {}))
                for cid, ws in self._active_connections.items()
                if filter_func(cid, self._client_metadata.get(cid, {}))
            ]

        for client_id, websocket, metadata in connections:
            try:
                await websocket.send_json(payload)
                success_count += 1
            except Exception as e:
                logger.warning(f"Broadcast to {client_id} failed: {e}")
                await self.disconnect(client_id)

        return success_count

    # ------------------------------------------------------------------------
    # 健康檢查
    # ------------------------------------------------------------------------

    def get_connection_count(self) -> int:
        """取得目前連線數"""
        return len(self._active_connections)

    def get_clients_summary(self) -> Dict[str, Any]:
        """取得 clients 概覽"""
        async with self._lock:
            return {
                "total": len(self._active_connections),
                "clients": [
                    {
                        "client_id": cid,
                        "connected_at": meta.get("connected_at"),
                        "last_ping": meta.get("last_ping"),
                        "remote_addr": meta.get("remote_addr"),
                    }
                    for cid, meta in self._client_metadata.items()
                ],
            }

    async def ping_clients(self) -> Dict[str, bool]:
        """
        向所有 clients 發送 ping（用於健康檢查）

        Returns:
            dict — client_id -> 是否成功
        """
        results = {}

        async with self._lock:
            connections = list(self._active_connections.items())

        for client_id, websocket in connections:
            try:
                await websocket.send_json({
                    "event": "ping",
                    "timestamp": datetime.now().isoformat(),
                })
                results[client_id] = True

                # 更新 last_ping
                async with self._lock:
                    if client_id in self._client_metadata:
                        self._client_metadata[client_id]["last_ping"] = datetime.now().isoformat()
            except Exception as e:
                logger.warning(f"Ping {client_id} failed: {e}")
                results[client_id] = False
                await self.disconnect(client_id)

        return results


# ============================================================================
# 模組級別實例
# ============================================================================
_connection_manager_instance: Optional[ConnectionManager] = None


def get_connection_manager() -> ConnectionManager:
    """取得 ConnectionManager 單例"""
    global _connection_manager_instance
    if _connection_manager_instance is None:
        _connection_manager_instance = ConnectionManager()
    return _connection_manager_instance

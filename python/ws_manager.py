# ws_manager.py — WebSocket 連線管理器
# 職責：
#   - 管理所有連線的 WebSocket clients
#   - 維護 client → 訂閱 topic（symbol） 的映射
#   - 提供 broadcast() 對特定 symbol 廣播訊息
#   - 提供 broadcast_all() 廣播給所有客戶端

import asyncio
import json
import logging
import threading
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional, Any

logger = logging.getLogger(__name__)


@dataclass
class WSClient:
    """單一 WebSocket 客戶端"""
    client_id: str
    websocket: Any               # FastAPI WebSocket
    subscribed_symbols: set = field(default_factory=set)  # 訂閱的股票代碼
    subscribed_topics: set = field(default_factory=set)    # 訂閱的事件類型
    connected_at: str = ""


class WebSocketManager:
    """
    WebSocket 連線管理器（單例，執行緒安全）

    功能：
    - connect(client_id, websocket) → 註冊新連線
    - disconnect(client_id) → 移除連線
    - subscribe(client_id, symbols, topics) → 訂閱股票/事件
    - unsubscribe(client_id, symbols) → 取消訂閱
    - broadcast(event_type, payload, symbol=None) → 廣播
        * symbol=None → 廣播給所有客戶端
        * symbol="2330" → 只廣播給訂閱 2330 的客戶端
    - get_clients_summary() → 連線統計
    """

    _instance: Optional["WebSocketManager"] = None
    _lock = threading.Lock()

    def __new__(cls):
        with cls._lock:
            if cls._instance is None:
                cls._instance = super().__new__(cls)
                cls._instance._initialized = False
            return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True

        # client_id → WSClient
        self._clients: dict[str, WSClient] = {}
        self._clients_lock = threading.Lock()

        # symbol → set of client_ids（用於快速查找訂閱者）
        self._symbol_subscribers: dict[str, set[str]] = defaultdict(set)
        self._symbol_lock = threading.Lock()

        # topic → set of client_ids
        self._topic_subscribers: dict[str, set[str]] = defaultdict(set)
        self._topic_lock = threading.Lock()

        logger.info("WebSocketManager 初始化完成")

    # ──────────────────────────────────────────────────────────────
    # 連線管理
    # ──────────────────────────────────────────────────────────────

    async def connect(self, client_id: str, websocket) -> str:
        """註冊新 WebSocket 連線"""
        with self._clients_lock:
            # 踢掉舊的同 client_id 連線
            if client_id in self._clients:
                old = self._clients[client_id]
                try:
                    await old.websocket.close()
                except Exception:
                    pass
                self._remove_client_state(client_id)

            ws_client = WSClient(
                client_id=client_id,
                websocket=websocket,
                connected_at=datetime.now().isoformat(),
            )
            self._clients[client_id] = ws_client

        logger.info(f"[WS] Client connected: {client_id} (total: {self.get_connection_count()})")
        return client_id

    async def disconnect(self, client_id: str):
        """移除 WebSocket 連線"""
        with self._clients_lock:
            if client_id not in self._clients:
                return
            self._remove_client_state(client_id)
            del self._clients[client_id]

        logger.info(f"[WS] Client disconnected: {client_id} (remaining: {self.get_connection_count()})")

    def _remove_client_state(self, client_id: str):
        """移除客戶端的訂閱狀態（internal）"""
        client = self._clients.get(client_id)
        if not client:
            return

        # 從 symbol 訂閱者移除
        for sym in client.subscribed_symbols:
            with self._symbol_lock:
                if sym in self._symbol_subscribers:
                    self._symbol_subscribers[sym].discard(client_id)
                    if not self._symbol_subscribers[sym]:
                        del self._symbol_subscribers[sym]

        # 從 topic 訂閱者移除
        for topic in client.subscribed_topics:
            with self._topic_lock:
                if topic in self._topic_subscribers:
                    self._topic_subscribers[topic].discard(client_id)

    # ──────────────────────────────────────────────────────────────
    # 訂閱管理
    # ──────────────────────────────────────────────────────────────

    def subscribe(self, client_id: str, symbols: list[str] = None, topics: list[str] = None):
        """
        讓 client 訂閱股票報價或事件 topics

        Args:
            client_id: 客戶端 ID
            symbols:   訂閱的股票代碼列表（例: ["2330", "2317"]）
            topics:    訂閱的事件類型（例: ["order_update", "condition_triggered"]）
        """
        with self._clients_lock:
            client = self._clients.get(client_id)
            if not client:
                logger.warning(f"[WS] subscribe() called but client {client_id} not found")
                return

        if symbols:
            for sym in symbols:
                sym = sym.upper()
                with self._symbol_lock:
                    self._symbol_subscribers[sym].add(client_id)
                client.subscribed_symbols.add(sym)
            logger.info(f"[WS] {client_id} subscribed to symbols: {symbols}")

        if topics:
            for topic in topics:
                with self._topic_lock:
                    self._topic_subscribers[topic].add(client_id)
                client.subscribed_topics.add(topic)
            logger.info(f"[WS] {client_id} subscribed to topics: {topics}")

    def unsubscribe(self, client_id: str, symbols: list[str] = None):
        """取消訂閱股票代碼"""
        with self._clients_lock:
            client = self._clients.get(client_id)
            if not client:
                return

        if symbols:
            for sym in symbols:
                sym = sym.upper()
                with self._symbol_lock:
                    if sym in self._symbol_subscribers:
                        self._symbol_subscribers[sym].discard(client_id)
                client.subscribed_symbols.discard(sym)

    # ──────────────────────────────────────────────────────────────
    # 廣播
    # ──────────────────────────────────────────────────────────────

    async def broadcast(
        self,
        event_type: str,
        payload: dict,
        symbol: str = None,
    ):
        """
        廣播訊息給客戶端

        Args:
            event_type: 事件類型（"quote" | "order_update" | "condition_triggered" 等）
            payload:    訊息內容
            symbol:     若指定，只送給訂閱該 symbol 的客戶端；若為 None，送給所有客戶端
        """
        message = json.dumps({
            "event": event_type,
            "data": payload,
            "timestamp": datetime.now().isoformat(),
        }, ensure_ascii=False)

        # 決定目標 client_ids
        if symbol:
            symbol = symbol.upper()
            with self._symbol_lock:
                target_ids = set(self._symbol_subscribers.get(symbol, set()))
        else:
            with self._clients_lock:
                target_ids = set(self._clients.keys())

        if not target_ids:
            return

        # 並發送給所有目標
        async def send_to(client_id: str):
            try:
                with self._clients_lock:
                    client = self._clients.get(client_id)
                if client and client.websocket:
                    await client.websocket.send_text(message)
            except Exception as e:
                logger.warning(f"[WS] broadcast to {client_id} failed: {e}")

        await asyncio.gather(*[send_to(cid) for cid in target_ids], return_exceptions=True)

    async def broadcast_quote(self, symbol: str, quote_data: dict):
        """
        廣播股票/期貨報價（ shorthand ）
        symbol 為 None 時廣播給所有客戶端
        """
        await self.broadcast(
            event_type="quote",
            payload={
                "symbol": symbol,
                **quote_data,
            },
            symbol=symbol,  # 只送給訂閱該 symbol 的客戶端
        )

    # ──────────────────────────────────────────────────────────────
    # 統計
    # ──────────────────────────────────────────────────────────────

    def get_connection_count(self) -> int:
        with self._clients_lock:
            return len(self._clients)

    def get_clients_summary(self) -> dict:
        with self._clients_lock:
            clients = []
            for cid, client in self._clients.items():
                clients.append({
                    "client_id": cid,
                    "connected_at": client.connected_at,
                    "subscribed_symbols": list(client.subscribed_symbols),
                    "subscribed_topics": list(client.subscribed_topics),
                })
        return {
            "total_connections": len(clients),
            "subscribed_symbols_count": len(self._symbol_subscribers),
            "subscribed_topics_count": len(self._topic_subscribers),
            "clients": clients,
        }


# ════════════════════════════════════════════════════════════════════
# 全域實例（供 service.py 使用）
# ════════════════════════════════════════════════════════════════════

def get_ws_manager() -> WebSocketManager:
    """取得 WebSocketManager 單例（供 FastAPI 注入使用）"""
    return WebSocketManager()
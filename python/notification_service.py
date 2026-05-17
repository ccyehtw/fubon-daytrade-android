"""
富邦當日沖銷 - Notification Service (Phase 4-2)
推播通知服務：成交通知、条件單觸發通知、自動平倉通知、獲利提醒
實作方式：輸出到 stdout（JSON log），並透過 WebSocket 廣播給 Android App
預留 Telegram 推送介面
"""

import json
import logging
import threading
from datetime import datetime
from typing import Dict, Any, Optional, List

logger = logging.getLogger(__name__)

# ============================================================================
# 事件類型常量
# ============================================================================
class EventType:
    ORDER_UPDATE        = "order_update"       # 成交通知
    CONDITION_TRIGGERED = "condition_triggered" # 條件單觸發
    AUTO_SQUARE         = "auto_square"         # 自動平倉通知
    PROFIT_ALERT        = "profit_alert"        # 獲利提醒
    QUOTE_ALERT         = "quote_alert"         # 報價警報


# ============================================================================
# NotificationService 單例
# ============================================================================
class NotificationService:
    """
    推播通知服務（單例）

    使用方式：
        ns = NotificationService()
        ns.send_trade_notification(order_id, symbol, "buy", 21500.0, 1, "filled")
    """

    _instance: Optional["NotificationService"] = None
    _lock = threading.Lock()

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super().__new__(cls)
                    cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True

        # WebSocket broadcaster（延遲初始化避免循環 import）
        self._ws_manager = None

        # 已註冊的 client tokens（for Firebase/LINE Push）
        self._registered_tokens: Dict[str, Dict[str, Any]] = {}

        # Telegram 推送設定（預留）
        self._telegram_enabled = False
        self._telegram_token: Optional[str] = None
        self._telegram_chat_id: Optional[str] = None

        logger.info("NotificationService initialized")

    # ------------------------------------------------------------------------
    # WebSocket Manager 設定
    # ------------------------------------------------------------------------
    def set_websocket_manager(self, ws_manager):
        """設定 WebSocket Manager（由 service.py 注入）"""
        self._ws_manager = ws_manager

    # ------------------------------------------------------------------------
    # 內部廣播方法
    # ------------------------------------------------------------------------
    def _broadcast(self, event_type: str, data: Dict[str, Any]):
        """
        廣播事件到所有 WebSocket clients
        同時輸出 JSON log（供 Android App 解析）
        """
        payload = {
            "event": event_type,
            "timestamp": datetime.now().isoformat(),
            "data": data,
        }

        # 1. 輸出到 stdout（JSON log）
        print(json.dumps(payload, ensure_ascii=False), flush=True)

        # 2. WebSocket 廣播
        if self._ws_manager is not None:
            try:
                import asyncio
                loop = asyncio.get_event_loop()
                if loop.is_running():
                    asyncio.create_task(
                        self._ws_manager.broadcast(event_type, payload)
                    )
                else:
                    loop.run_until_complete(
                        self._ws_manager.broadcast(event_type, payload)
                    )
            except Exception as e:
                logger.warning(f"WebSocket broadcast failed: {e}")

        logger.info(f"[NOTIFY] {event_type}: {data}")

    # ------------------------------------------------------------------------
    # 成交通知
    # ------------------------------------------------------------------------
    def send_trade_notification(
        self,
        order_id: str,
        symbol: str,
        bs: str,           # "buy" | "sell"
        price: float,
        quantity: int,
        status: str,       # "filled" | "partial" | "cancelled" | "rejected"
        message: Optional[str] = None,
    ):
        """
        發送成交通知

        Args:
            order_id: 委託單號
            symbol:   股票/期貨代碼
            bs:       買賣別 "buy" | "sell"
            price:    成交價格
            quantity: 成交數量
            status:   成交狀態
            message:  額外訊息
        """
        data = {
            "order_id": order_id,
            "symbol": symbol,
            "bs": bs.upper(),
            "price": price,
            "quantity": quantity,
            "status": status,
            "message": message or self._get_status_message(status, symbol, bs, price, quantity),
        }

        self._broadcast(EventType.ORDER_UPDATE, data)

    def _get_status_message(self, status: str, symbol: str, bs: str, price: float, quantity: int) -> str:
        """產生狀態訊息"""
        action = "買進" if bs.lower() == "buy" else "賣出"
        if status == "filled":
            return f"{action}成交 {symbol} {quantity}股 @ {price}"
        elif status == "partial":
            return f"部分成交 {symbol} {quantity}股 @ {price}"
        elif status == "cancelled":
            return f"委託取消 {symbol}"
        elif status == "rejected":
            return f"委託拒絕 {symbol}"
        return f"委託狀態更新 {symbol}"

    # ------------------------------------------------------------------------
    # 條件單觸發通知
    # ------------------------------------------------------------------------
    def send_condition_triggered_notification(
        self,
        condition_id: str,
        symbol: str,
        trigger_price: float,
        trigger_type: str,   # "above" | "below" | "change_up" | "change_down"
        action: str,         # "buy" | "sell"
        order_price: Optional[float] = None,
    ):
        """
        發送條件單觸發通知

        Args:
            condition_id: 條件單 ID
            symbol:       標的代碼
            trigger_price: 觸發價格
            trigger_type: 觸發類型
            action:       執行動作 "buy" | "sell"
            order_price:  委託價格
        """
        type_labels = {
            "above": "突破上漲",
            "below": "跌破下跌",
            "change_up": "漲幅觸發",
            "change_down": "跌幅觸發",
        }

        data = {
            "condition_id": condition_id,
            "symbol": symbol,
            "trigger_price": trigger_price,
            "trigger_type": trigger_type,
            "trigger_type_label": type_labels.get(trigger_type, trigger_type),
            "action": action.upper(),
            "order_price": order_price,
            "message": (
                f"條件單觸發！{symbol} 價格 {trigger_price} "
                f"({type_labels.get(trigger_type, trigger_type)})，"
                f"執行{'買進' if action.lower() == 'buy' else '賣出'}"
                + (f" @ {order_price}" if order_price else " (市價)")
            ),
        }

        self._broadcast(EventType.CONDITION_TRIGGERED, data)

    # ------------------------------------------------------------------------
    # 自動平倉通知
    # ------------------------------------------------------------------------
    def send_auto_square_notification(
        self,
        positions: List[Dict[str, Any]],
        square_time: str,   # "13:20" | "13:30"
        result: Optional[Dict[str, Any]] = None,
    ):
        """
        發送自動平倉通知

        Args:
            positions:  平倉部位列表
            square_time: 實際執行時間
            result:     平倉結果（可選）
        """
        total = len(positions)
        success = sum(1 for p in positions if p.get("success", False))
        failed = total - success

        data = {
            "square_time": square_time,
            "total_positions": total,
            "success_count": success,
            "failed_count": failed,
            "positions": positions,
            "result_summary": (
                f"自動平倉完成：{success} 檔成功，{failed} 檔失敗"
                if result is None
                else result.get("summary", "")
            ),
            "message": (
                f"⚠️ 當日沖自動平倉執行 {square_time}，"
                f"共 {total} 檔（成功 {success}，失敗 {failed}）"
            ),
        }

        self._broadcast(EventType.AUTO_SQUARE, data)

    # ------------------------------------------------------------------------
    # 獲利提醒通知（可選）
    # ------------------------------------------------------------------------
    def send_profit_alert_notification(
        self,
        symbol: str,
        profit_percent: float,
        profit_amount: float,
        current_price: float,
        quantity: int,
    ):
        """
        發送獲利提醒通知

        Args:
            symbol:         股票代碼
            profit_percent: 獲利百分比
            profit_amount:  獲利金額
            current_price:  現價
            quantity:      持有數量
        """
        data = {
            "symbol": symbol,
            "profit_percent": round(profit_percent, 2),
            "profit_amount": round(profit_amount, 2),
            "current_price": current_price,
            "quantity": quantity,
            "message": (
                f"📈 {symbol} 獲利提醒："
                f"目前漲幅 {profit_percent:+.2f}%，"
                f"獲利約 {profit_amount:+.2f} 元"
            ),
        }

        self._broadcast(EventType.PROFIT_ALERT, data)

    # ------------------------------------------------------------------------
    # Client Token 管理（for Firebase/LINE Push）
    # ------------------------------------------------------------------------
    def register_client_token(
        self,
        client_id: str,
        platform: str,    # "firebase" | "line" | "telegram"
        token: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> bool:
        """
        註冊 client push token

        Returns:
            bool — 註冊是否成功
        """
        self._registered_tokens[client_id] = {
            "platform": platform,
            "token": token,
            "metadata": metadata or {},
            "registered_at": datetime.now().isoformat(),
            "active": True,
        }
        logger.info(f"Client token registered: {client_id} ({platform})")
        return True

    def unregister_client_token(self, client_id: str) -> bool:
        """移除 client token"""
        if client_id in self._registered_tokens:
            self._registered_tokens[client_id]["active"] = False
            logger.info(f"Client token unregistered: {client_id}")
            return True
        return False

    def get_registered_tokens(
        self, platform: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """取得已註冊的 tokens"""
        tokens = list(self._registered_tokens.values())
        if platform:
            tokens = [t for t in tokens if t.get("platform") == platform and t.get("active")]
        return tokens

    # ------------------------------------------------------------------------
    # Telegram 推送（預留介面）
    # ------------------------------------------------------------------------
    def configure_telegram(self, bot_token: str, chat_id: str):
        """設定 Telegram 推送"""
        self._telegram_token = bot_token
        self._telegram_chat_id = chat_id
        self._telegram_enabled = True
        logger.info("Telegram push configured")

    def _send_telegram_message(self, text: str) -> bool:
        """透過 Telegram 發送訊息（預留實作）"""
        if not self._telegram_enabled:
            return False
        # TODO: Implement Telegram bot API call
        logger.info(f"[TELEGRAM] {text}")
        return True

    # ------------------------------------------------------------------------
    # 手工發送通知（管理後台）
    # ------------------------------------------------------------------------
    def send_custom_notification(
        self,
        title: str,
        body: str,
        event_type: str = "manual",
        data: Optional[Dict[str, Any]] = None,
    ) -> bool:
        """
        手動發送自訂通知（管理後台使用）

        Args:
            title:     通知標題
            body:      通知內容
            event_type: 事件類型
            data:      額外資料

        Returns:
            bool — 發送是否成功
        """
        payload = {
            "title": title,
            "body": body,
            "event": event_type,
            "data": data or {},
            "timestamp": datetime.now().isoformat(),
        }

        self._broadcast(event_type, payload)
        return True


# ============================================================================
# 模組級別實例（方便直接 import 使用）
# ============================================================================
_notification_service_instance: Optional[NotificationService] = None


def get_notification_service() -> NotificationService:
    """取得 NotificationService 單例"""
    global _notification_service_instance
    if _notification_service_instance is None:
        _notification_service_instance = NotificationService()
    return _notification_service_instance


# ============================================================================
# 便捷函式
# ============================================================================

def notify_trade(order_id: str, symbol: str, bs: str, price: float,
                 quantity: int, status: str):
    """快捷成交通知"""
    get_notification_service().send_trade_notification(
        order_id, symbol, bs, price, quantity, status
    )


def notify_condition_triggered(condition_id: str, symbol: str,
                               trigger_price: float, trigger_type: str,
                               action: str, order_price: float = None):
    """快捷條件單觸發通知"""
    get_notification_service().send_condition_triggered_notification(
        condition_id, symbol, trigger_price, trigger_type, action, order_price
    )


def notify_auto_square(positions: List[Dict[str, Any]], square_time: str):
    """快捷自動平倉通知"""
    get_notification_service().send_auto_square_notification(positions, square_time)


def notify_profit_alert(symbol: str, profit_percent: float,
                        profit_amount: float, current_price: float, quantity: int):
    """快捷獲利提醒通知"""
    get_notification_service().send_profit_alert_notification(
        symbol, profit_percent, profit_amount, current_price, quantity
    )

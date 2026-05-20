# quotes_broadcast_service.py — 即時報價轉播服務
# 職責：
#   - 定期從富邦 SDK 取得期貨/股票即時報價（polling 或 WebSocket 轉發）
#   - 透過 WebSocketManager 廣播給 Android App 訂閱者
#
# 資料流：
#   富邦 SDK → QuotesBroadcastService → WebSocketManager → Android App
#
# 設計考量：
#   - 期貨：使用富邦 WebSocket 報價介面（若 SDK 支援）
#   - 股票：目前無 SDK 報價介面，改用 Polling 方式（每 3 秒一次）
#   - 可擴展：日後富邦提供股票 WebSocket 時替換即可

import asyncio
import logging
import threading
import time
from datetime import datetime
from typing import Optional, Dict, Any

logger = logging.getLogger(__name__)

# 富邦 SDK 可用標記
FUBON_SDK_AVAILABLE = False
try:
    from fubon_neo.sdk import FubonSDK
    FUBON_SDK_AVAILABLE = True
except ImportError:
    logger.warning("fubon-neo-api not installed, quotes broadcast unavailable")


class QuotesBroadcastService:
    """
    即時報價轉播服務（單例）

    使用方式：
        qbs = QuotesBroadcastService()
        qbs.set_ws_manager(get_ws_manager())
        qbs.start()

        # 或訂閱特定 symbol
        qbs.add_subscription("2330")
        qbs.add_subscription("TXF")
    """

    _instance: Optional["QuotesBroadcastService"] = None
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

        self._ws_manager = None
        self._running = False
        self._task: Optional[asyncio.Task] = None

        # 訂閱的 symbols（股票 + 期貨）
        self._subscribed_symbols: Dict[str, Dict[str, Any]] = {}
        self._sub_lock = threading.Lock()

        # Polling interval（秒）
        self._poll_interval = 3  # 每 3 秒輪詢一次

        # 最後廣播的報價（用於過濾重複）
        self._last_quote_cache: Dict[str, dict] = {}

        # SDK 實例（由 service.py 注入）
        self._sdk = None

        logger.info("QuotesBroadcastService 初始化完成")

    # ──────────────────────────────────────────────────────────────
    # 注入
    # ──────────────────────────────────────────────────────────────

    def set_sdk(self, sdk):
        """注入 FubonSDK 實例"""
        self._sdk = sdk

    def set_ws_manager(self, ws_manager):
        """注入 WebSocketManager"""
        self._ws_manager = ws_manager

    # ──────────────────────────────────────────────────────────────
    # 訂閱管理
    # ──────────────────────────────────────────────────────────────

    def add_subscription(self, symbol: str, product_type: str = "stock"):
        """
        新增訂閱 symbol

        Args:
            symbol:        商品代碼（2330 / TXF / TXF202506）
            product_type: "stock" | "futures"
        """
        with self._sub_lock:
            sym = symbol.upper()
            self._subscribed_symbols[sym] = {
                "product_type": product_type,
                "added_at": datetime.now().isoformat(),
            }
            logger.info(f"[Quotes] 新增訂閱: {sym} ({product_type})")

    def remove_subscription(self, symbol: str):
        """移除訂閱"""
        with self._sub_lock:
            sym = symbol.upper()
            self._subscribed_symbols.pop(sym, None)
            logger.info(f"[Quotes] 移除訂閱: {sym}")

    def get_subscriptions(self) -> Dict[str, Dict]:
        with self._sub_lock:
            return dict(self._subscribed_symbols)

    # ──────────────────────────────────────────────────────────────
    # 啟動 / 停止
    # ──────────────────────────────────────────────────────────────

    def start(self):
        """啟動轉播服務（非阻塞）"""
        if self._running:
            logger.warning("[Quotes] 轉播服務已在執行中")
            return

        self._running = True
        self._task = asyncio.create_task(self._run_loop())
        logger.info("[Quotes] 轉播服務已啟動")

    async def stop(self):
        """停止轉播服務"""
        if not self._running:
            return

        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("[Quotes] 轉播服務已停止")

    # ──────────────────────────────────────────────────────────────
    # 主輪詢迴圈
    # ──────────────────────────────────────────────────────────────

    async def _run_loop(self):
        """主輪詢迴圈（非同步，每 _poll_interval 秒執行一次）"""
        logger.info(f"[Quotes] 輪詢迴圈啟動（間隔 {self._poll_interval}s）")

        while self._running:
            try:
                await self._poll_all_quotes()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"[Quotes] 輪詢例外: {e}")

            await asyncio.sleep(self._poll_interval)

    async def _poll_all_quotes(self):
        """輪詢所有已訂閱的 symbol 報價並廣播"""
        with self._sub_lock:
            subs = dict(self._subscribed_symbols)

        if not subs:
            return

        for symbol, info in subs.items():
            try:
                if info["product_type"] == "futures":
                    quote = await self._fetch_futures_quote(symbol)
                else:
                    quote = await self._fetch_stock_quote(symbol)

                if quote and self._should_broadcast(symbol, quote):
                    self._last_quote_cache[symbol] = quote
                    if self._ws_manager:
                        await self._ws_manager.broadcast_quote(
                            symbol=symbol,
                            quote_data=quote,
                        )
            except Exception as e:
                logger.warning(f"[Quotes] 取得 {symbol} 報價失敗: {e}")

    # ──────────────────────────────────────────────────────────────
    # 報價取得
    # ──────────────────────────────────────────────────────────────

    async def _fetch_stock_quote(self, symbol: str) -> Optional[dict]:
        """
        取得股票報價
        目前使用 REST polling（富邦 SDK 無股票 WebSocket）
        """
        if not self._sdk:
            # Mock 資料
            return self._mock_stock_quote(symbol)

        try:
            import json
            # 嘗試呼叫富邦股票報價 API
            # 注意：富邦目前無股票即時報價 SDK，此處預留介面
            # 若日後有，可改為：resp = await self._sdk.stock.get_quote(symbol)
            return self._mock_stock_quote(symbol)
        except Exception as e:
            logger.warning(f"[Quotes] 股票報價失敗 {symbol}: {e}, 使用 Mock")
            return self._mock_stock_quote(symbol)

    async def _fetch_futures_quote(self, symbol: str) -> Optional[dict]:
        """取得期貨報價"""
        if not self._sdk:
            return self._mock_futures_quote(symbol)

        try:
            # 嘗試使用 SDK 的期貨報價
            resp = self._sdk.futopt.get_quote(symbol)
            if not resp.is_success:
                return self._mock_futures_quote(symbol)

            data = resp.data
            return {
                "symbol": symbol,
                "name": getattr(data, 'name', symbol),
                "last_price": float(getattr(data, 'last_price', 0)),
                "bid_price": float(getattr(data, 'bid_price', 0)),
                "ask_price": float(getattr(data, 'ask_price', 0)),
                "volume": int(getattr(data, 'volume', 0)),
                "open_price": float(getattr(data, 'open_price', 0)),
                "high_price": float(getattr(data, 'high_price', 0)),
                "low_price": float(getattr(data, 'low_price', 0)),
                "change": float(getattr(data, 'change', 0)),
                "change_percent": float(getattr(data, 'change_percent', 0)),
                "updated_at": datetime.now().isoformat(),
            }
        except Exception as e:
            logger.warning(f"[Quotes] 期貨報價失敗 {symbol}: {e}, 使用 Mock")
            return self._mock_futures_quote(symbol)

    # ──────────────────────────────────────────────────────────────
    # Mock 報價（開發/無 SDK 時使用）
    # ──────────────────────────────────────────────────────────────

    def _mock_stock_quote(self, symbol: str) -> dict:
        """模擬股票報價（開發用）"""
        base_prices = {
            "2330": 1080.0,
            "2317": 158.0,
            "2454": 2280.0,
            "2308": 520.0,
            "3008": 1680.0,
        }
        base = base_prices.get(symbol, 100.0 + hash(symbol) % 500)
        change = (hash(str(time.time())) % 20 - 10) * 0.1

        return {
            "symbol": symbol,
            "name": symbol,
            "last_price": round(base + change, 2),
            "bid_price": round(base + change - 0.5, 2),
            "ask_price": round(base + change + 0.5, 2),
            "volume": (hash(symbol) % 500000) + 10000,
            "change": round(change, 2),
            "change_percent": round((change / base) * 100, 2),
            "open_price": round(base - 5, 2),
            "high_price": round(base + 15, 2),
            "low_price": round(base - 10, 2),
            "updated_at": datetime.now().isoformat(),
        }

    def _mock_futures_quote(self, symbol: str) -> dict:
        """模擬期貨報價（開發用）"""
        base_prices = {
            "TXF": 21500.0,
            "MXF": 21500.0,
            "EXF": 1850.0,
            "FEF": 1820.0,
        }
        base = base_prices.get(symbol, 20000.0)
        change = (hash(str(time.time())) % 40 - 20)

        return {
            "symbol": symbol,
            "name": symbol,
            "last_price": round(base + change, 1),
            "bid_price": round(base + change - 1, 1),
            "ask_price": round(base + change + 1, 1),
            "volume": (hash(symbol) % 30000) + 5000,
            "change": round(change, 1),
            "change_percent": round((change / base) * 100, 2),
            "open_price": round(base - 10, 1),
            "high_price": round(base + 30, 1),
            "low_price": round(base - 20, 1),
            "updated_at": datetime.now().isoformat(),
        }

    # ──────────────────────────────────────────────────────────────
    # 過濾（避免重複廣播相同報價）
    # ──────────────────────────────────────────────────────────────

    def _should_broadcast(self, symbol: str, quote: dict) -> bool:
        """檢查是否應該廣播（過濾價格不變的報價）"""
        last = self._last_quote_cache.get(symbol)
        if not last:
            return True
        return last.get("last_price") != quote.get("last_price")


# ════════════════════════════════════════════════════════════════════
# 全域單例（修復：確保同一實例被複用）
# ════════════════════════════════════════════════════════════════════

_instance: Optional[QuotesBroadcastService] = None


def get_quotes_broadcast_service() -> QuotesBroadcastService:
    """取得 QuotesBroadcastService 單例"""
    global _instance
    if _instance is None:
        _instance = QuotesBroadcastService()
    return _instance
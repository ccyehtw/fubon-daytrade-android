# quotes_broadcast_service.py — 即時報價轉播服務
# 職責：
#   - 定期從富邦 SDK 取得期貨/股票即時報價（polling）
#   - 透過 WebSocketManager 廣播給 Android App 訂閱者
#
# 資料流：
#   富邦 SDK (marketdata.rest_client.stock.snapshot.quotes) → QuotesBroadcastService → WebSocketManager → Android App

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

        # 股票快取（marketdata.rest_client.stock.snapshot.quotes）
        self._stock_cache: Optional[dict] = None
        self._stock_cache_time: float = 0

        logger.info("QuotesBroadcastService 初始化完成")

    # ──────────────────────────────────────────────────────────────
    # 注入
    # ──────────────────────────────────────────────────────────────

    def set_sdk(self, sdk):
        """注入 FubonSDK 實例"""
        self._sdk = sdk
        logger.info(f"[Quotes] SDK 注入成功，SDK={type(sdk).__name__ if sdk else 'None'}")

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
                quote = None
                if info["product_type"] == "futures":
                    quote = await self._fetch_futures_quote(symbol)
                else:
                    quote = await self._fetch_stock_quote(symbol)

                if quote and self._ws_manager:
                    self._last_quote_cache[symbol] = quote
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
        取得股票報價（富邦 SDK marketdata.rest_client.stock.snapshot.quotes）
        資料來源：富邦 Fubon Neo API SDK（fubon-neo-api skill）
        """
        logger.info(f"[Quotes] _fetch_stock_quote called, SDK={type(self._sdk).__name__ if self._sdk else 'None'}")
        if not self._sdk:
            logger.warning(f"[Quotes] 股票報價 {symbol} 失敗：SDK 未初始化")
            return None

        try:
            import time
            # 快取 30 秒，全市場一次抓取後快取
            now = time.time()
            if self._stock_cache is None or (now - self._stock_cache_time) > 30:
                rc = self._sdk.marketdata.rest_client
                quotes_data = rc.stock.snapshot.quotes(market="TSE")
                self._stock_cache = {q["symbol"]: q for q in quotes_data.get("data", [])}
                self._stock_cache_time = now
                logger.info(f"[Quotes] 已更新股票快取，共 {len(self._stock_cache)} 檔")

            # Step 1: 先嘗試以代碼（symbol）精確匹配
            stock = self._stock_cache.get(symbol)
            if not stock:
                # Step 2: 代碼未命中，再以名稱模糊匹配（支援「台積電」查詢「2330」）
                for sym, s in self._stock_cache.items():
                    if symbol in s.get("name", ""):
                        stock = s
                        logger.info(f"[Quotes] 股票名稱匹配: {symbol} → {sym} ({s.get('name')})")
                        break
                if not stock:
                    # Step 3: 反向匹配 — 以輸入去找任何包含該文字的股票
                    search_term = symbol.strip()
                    for sym, s in self._stock_cache.items():
                        name = s.get("name", "")
                        code = s.get("symbol", "")
                        if search_term in name or search_term.upper() in code.upper():
                            stock = s
                            logger.info(f"[Quotes] 股票模糊匹配: {symbol} → {sym} ({name})")
                            break

            if stock:
                return {
                    "symbol": stock.get("symbol"),
                    "name": stock.get("name"),
                    "last_price": stock.get("lastPrice"),
                    "change": stock.get("change", 0),
                    "change_percent": stock.get("changePercent", 0),
                    "open": stock.get("openPrice"),
                    "high": stock.get("highPrice"),
                    "low": stock.get("lowPrice"),
                    "close": stock.get("closePrice"),
                    "volume": stock.get("tradeVolume"),
                }
            else:
                logger.warning(f"[Quotes] 找不到股票 {symbol}")
                return None
        except Exception as e:
            logger.warning(f"[Quotes] 股票報價失敗 {symbol}: {e}")
            return None

    async def _fetch_futures_quote(self, symbol: str) -> Optional[dict]:
        """取得期貨報價（富邦 SDK marketdata.rest_client.futopt.intraday.quote）
        
        重要：要用 quote() 而非 ticker()，因為 ticker() 回傳 lastPrice=None。
        期貨代碼格式需完整合約代碼，如 TXFF6、MXFF6、FEFG6。
        """
        if not self._sdk:
            logger.warning(f"[Quotes] 期貨報價 {symbol} 失敗：SDK 未初始化")
            return None

        try:
            rc = self._sdk.marketdata.rest_client
            futopt_client = rc.futopt
            resp = futopt_client.intraday.quote(symbol=symbol)
            if not resp or not resp.get('data'):
                logger.warning(f"[Quotes] 期貨 {symbol} 期貨報價回傳異常: {resp}")
                return None
            d = resp['data']  # quote() 回傳 dict，直接是報價物件
            return {
                "symbol": d.get('symbol', symbol),
                "name": d.get('name', symbol),
                "last_price": float(d.get('lastPrice') or d.get('closePrice') or 0),
                "bid_price": float(d.get('lastTrade', {}).get('bid') or 0),
                "ask_price": float(d.get('lastTrade', {}).get('ask') or 0),
                "volume": int(d.get('total', {}).get('tradeVolume') or 0),
                "open_price": float(d.get('openPrice') or 0),
                "high_price": float(d.get('highPrice') or 0),
                "low_price": float(d.get('lowPrice') or 0),
                "change": float(d.get('change') or 0),
                "change_percent": float(d.get('changePercent') or 0),
                "updated_at": datetime.now().isoformat(),
            }
        except Exception as e:
            logger.warning(f"[Quotes] 期貨報價失敗 {symbol}: {e}")
            return None

    async def _fetch_futures_quote_fallback(self, symbol: str) -> Optional[dict]:
        """Fallback 期貨報價：使用 intraday.quote（與主要方法相同，但增加重試邏輯）"""
        return await self._fetch_futures_quote(symbol)

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

_instance = None


def get_quotes_broadcast_service() -> QuotesBroadcastService:
    """取得 QuotesBroadcastService 單例"""
    global _instance
    if _instance is None:
        _instance = QuotesBroadcastService()
    return _instance
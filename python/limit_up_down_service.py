# limit_up_down_service.py — 漲跌停監控服務
# 用途：避免開盤漲停/跌停時鎖死無法平倉，於交易時段持續監控並自動平倉
#
# 邏輯說明：
#   • 漲停（buy_lots > 0 且 ask_price = 漲停價）：代表手中持有多單，必須盡快卖出脫手
#   • 跌停（sell_lots > 0 且 bid_price = 跌停價）：代表手中持有空單，必須盡快买入回補
#   使用市價 IOC 委託，確保在漲跌停鎖死時仍有機會成交
#
# 漲停價計算：昨收價 × 1.10（一般股票 10%，部分 7%/3%）
# 跌停價計算：昨收價 × 0.90（一般股票 10%，部分 7%/3%）

import logging
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional, Dict, Any, List

logger = logging.getLogger(__name__)


# ──────────────────────────────────────────────
# 漲跌停價計算
# ──────────────────────────────────────────────

def calc_limit_up_price(close_price: float, limit_pct: float = 0.10) -> float:
    """計算漲停價（昨收 × 1.10）"""
    return round(close_price * (1 + limit_pct), 2)


def calc_limit_down_price(close_price: float, limit_pct: float = 0.10) -> float:
    """計算跌停價（昨收 × 0.90）"""
    return round(close_price * (1 - limit_pct), 2)


# ──────────────────────────────────────────────
# 漲跌停監控結果
# ──────────────────────────────────────────────

@dataclass
class LimitUpDownResult:
    """漲跌停監控結果"""
    symbol: str
    position_type: str        # "long" | "short"
    action: str               # "sell_limit_up" | "buy_limit_down"
    limit_price: float        # 觸發的漲/跌停價
    current_price: float      # 即時報價
    quantity: int             # 平倉數量
    order_response: Optional[Dict[str, Any]] = None
    success: bool = False
    error: Optional[str] = None
    checked_at: str = field(default_factory=lambda: datetime.now().isoformat())


# ──────────────────────────────────────────────
# 漲跌停監控服務
# ──────────────────────────────────────────────

class LimitUpDownService:
    """
    漲跌停自動平倉服務

    使用方式：
        svc = LimitUpDownService(fubon_client, daytrade_service)
        svc.check_limit_up_down()

    每分鐘由 SchedulerService 调用 check_limit_up_down()。
    也可在報價推送時，主動呼叫 check_symbol_limit_up_down(symbol, quote) 進行單檔檢查。
    """

    def __init__(self, fubon_client=None, daytrade_service=None):
        self._client = fubon_client
        self._daytrade_service = daytrade_service

        # 快取：symbol → { close_price, limit_up, limit_down }
        self._price_cache: Dict[str, Dict[str, float]] = {}

        # 最近的平倉結果（用於 API 查詢）
        self._recent_results: List[LimitUpDownResult] = []

        logger.info("LimitUpDownService initialized")

    def set_fubon_client(self, client):
        self._client = client

    def set_daytrade_service(self, svc):
        self._daytrade_service = svc

    # ──────────────────────────────────────────────
    # 昨收價查詢（需富邦 SDK）
    # ──────────────────────────────────────────────

    def _get_close_price(self, symbol: str) -> Optional[float]:
        """取得股票的昨收價（使用富邦報價 API）"""
        if self._client is None:
            logger.warning(f"無 FubonClient，無法取得 {symbol} 昨收價")
            return None

        try:
            # 嘗試取得報價
            if hasattr(self._client, 'get_stock_quote'):
                quote = self._client.get_stock_quote(symbol)
                if quote and "close_price" in quote:
                    return float(quote["close_price"])
                if quote and "close" in quote:
                    return float(quote["close"])
            # 富邦 SDK 行情接口
            if hasattr(self._client, '_sdk') and hasattr(self._client._sdk, 'stock'):
                resp = self._client._sdk.stock.quote(symbol)
                if resp.is_success and resp.data:
                    d = resp.data[0] if isinstance(resp.data, list) else resp.data
                    return float(getattr(d, 'close_price', 0) or getattr(d, 'close', 0))
        except Exception as e:
            logger.debug(f"取得 {symbol} 昨收價失敗: {e}")

        return self._price_cache.get(symbol, {}).get("close_price")

    def _update_price_cache(self, symbol: str, close_price: float):
        """更新價格快取"""
        if close_price and close_price > 0:
            self._price_cache[symbol] = {
                "close_price": close_price,
                "limit_up": calc_limit_up_price(close_price),
                "limit_down": calc_limit_down_price(close_price),
            }

    # ──────────────────────────────────────────────
    # 漲跌停監控（批次）
    # ──────────────────────────────────────────────

    def check_limit_up_down(self):
        """
        檢查所有持倉是否觸及漲跌停
        由 SchedulerService 每分鐘呼叫

        流程：
          1. 同步當日沖部位（使用 DayTradeService.sync_positions）
          2. 對每個 symbol 取得即時報價（昨收/現價/買一/賣一）
          3. 若 buylots > 0 且 ask_price = 漲停價 → 自動卖出平倉
          4. 若 sell_lots > 0 且 bid_price = 跌停價 → 自動买入平倉
        """
        if self._daytrade_service is None:
            logger.warning("DayTradeService 未設定，跳過漲跌停監控")
            return []

        try:
            # 同步持倉
            positions = self._daytrade_service.sync_positions()
            if not positions:
                logger.debug("無持倉，跳過漲跌停監控")
                return []

            results = []
            for symbol, pos in positions.items():
                buy_qty  = pos.get("buy_qty", 0)
                sell_qty = pos.get("sell_qty", 0)

                net_qty = buy_qty - sell_qty
                if net_qty == 0:
                    continue

                result = self._check_symbol_limit_up_down(symbol, pos, buy_qty, sell_qty)
                if result:
                    results.append(result)

            # 更新最近結果
            self._recent_results = results[-20:]  # 保留最近 20 筆

            return results

        except Exception as e:
            logger.error(f"漲跌停監控失敗: {e}")
            return []

    def _check_symbol_limit_up_down(
        self,
        symbol: str,
        pos: Dict[str, Any],
        buy_qty: int,
        sell_qty: int,
    ) -> Optional[LimitUpDownResult]:
        """
        檢查單一檔股票的漲跌停狀態
        """
        net_qty = buy_qty - sell_qty

        # 取得即時報價
        try:
            quote = self._get_realtime_quote(symbol)
        except Exception as e:
            logger.warning(f"取得 {symbol} 即時報價失敗: {e}")
            return None

        if not quote:
            return None

        bid_price  = float(quote.get("bid_price", 0) or 0)
        ask_price  = float(quote.get("ask_price", 0) or 0)
        last_price = float(quote.get("last_price", 0) or 0)
        close_price = float(quote.get("close_price", 0) or quote.get("close", 0) or 0)

        # 更新快取
        if close_price > 0:
            self._update_price_cache(symbol, close_price)

        cached = self._price_cache.get(symbol, {})
        limit_up   = cached.get("limit_up", calc_limit_up_price(close_price or last_price))
        limit_down = cached.get("limit_down", calc_limit_down_price(close_price or last_price))

        # ─── 漲停：buylots > 0 且 ask_price = 漲停價 ───
        if net_qty > 0 and ask_price > 0 and abs(ask_price - limit_up) < 0.01:
            logger.warning(
                f"[LimitUp] {symbol} 漲停！多單需平倉 "
                f"(buy_qty={buy_qty}, ask={ask_price}, limit_up={limit_up})"
            )
            return self._execute_limit_close(
                symbol=symbol,
                position_type="long",
                action="sell_limit_up",
                limit_price=limit_up,
                current_price=ask_price,
                quantity=net_qty,
                bs="sell",
            )

        # ─── 跌停：sell_lots > 0 且 bid_price = 跌停價 ───
        if net_qty < 0 and bid_price > 0 and abs(bid_price - limit_down) < 0.01:
            logger.warning(
                f"[LimitDown] {symbol} 跌停！空單需平倉 "
                f"(sell_qty={sell_qty}, bid={bid_price}, limit_down={limit_down})"
            )
            return self._execute_limit_close(
                symbol=symbol,
                position_type="short",
                action="buy_limit_down",
                limit_price=limit_down,
                current_price=bid_price,
                quantity=abs(net_qty),
                bs="buy",
            )

        return None

    # ──────────────────────────────────────────────
    # 漲跌停自動平倉執行
    # ──────────────────────────────────────────────

    def _execute_limit_close(
        self,
        symbol: str,
        position_type: str,
        action: str,
        limit_price: float,
        current_price: float,
        quantity: int,
        bs: str,
    ) -> LimitUpDownResult:
        """
        執行漲跌停自動平倉
        使用市價 IOC 委託（避免在漲跌停價位鎖死）
        """
        result = LimitUpDownResult(
            symbol=symbol,
            position_type=position_type,
            action=action,
            limit_price=limit_price,
            current_price=current_price,
            quantity=quantity,
        )

        if self._client is None:
            result.error = "FubonClient 未設定"
            logger.error(f"{symbol} 漲跌停平倉失敗: FubonClient 未設定")
            return result

        try:
            # 使用市價 IOC 委託（TimeInForce.IOC）平倉
            resp = self._client.place_order(
                stock_no=symbol,
                price=0,  # 市價
                quantity=quantity,
                order_type="market",    # 市價單
                buy_sell=bs,
                time_in_force="IOC",     # 立刻成交或取消
                product_type="daytrade", # 當日沖
            )

            result.order_response = resp
            result.success = resp.get("success", False)

            if result.success:
                logger.info(
                    f"[LimitClose] {symbol} 漲跌停平倉成功: "
                    f"{bs} x {quantity} @ {current_price}"
                )
            else:
                result.error = resp.get("message", "未知錯誤")
                logger.error(
                    f"[LimitClose] {symbol} 漲跌停平倉失敗: {result.error}"
                )

        except Exception as e:
            result.error = str(e)
            logger.error(f"[LimitClose] {symbol} 漲跌停平倉例外: {e}")

        return result

    def _get_realtime_quote(self, symbol: str) -> Optional[Dict[str, Any]]:
        """取得即時報價（相容富邦 SDK 格式）"""
        if self._client is None:
            return None

        try:
            # 嘗試富邦 SDK 即時報價接口
            if hasattr(self._client, '_sdk') and hasattr(self._client._sdk, 'stock'):
                resp = self._client._sdk.stock.quote(symbol)
                if resp.is_success and resp.data:
                    d = resp.data[0] if isinstance(resp.data, list) else resp.data
                    return {
                        "symbol": symbol,
                        "bid_price": getattr(d, 'bid_price', 0),
                        "ask_price": getattr(d, 'ask_price', 0),
                        "last_price": getattr(d, 'last_price', 0),
                        "close_price": getattr(d, 'close_price', 0),
                    }
        except Exception as e:
            logger.debug(f"即時報價取得失敗 ({symbol}): {e}")

        # 回傳快取資料（作為備援）
        return None

    # ──────────────────────────────────────────────
    # API 查詢
    # ──────────────────────────────────────────────

    def get_recent_results(self) -> List[Dict[str, Any]]:
        """回傳最近漲跌停平倉記錄"""
        return [vars(r) if isinstance(r, LimitUpDownResult) else r
                for r in self._recent_results]

    def get_price_cache(self) -> Dict[str, Dict[str, float]]:
        """回傳價格快取"""
        return dict(self._price_cache)

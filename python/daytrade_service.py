# daytrade_service.py — 當日沖銷核心服務（雙模式建倉/平倉）
# 實作：
#   - 追低點買入（breakdown_buy）→ 追高點回檔平倉
#   - 追高點回檔賣出（breakout_sell）→ 追低點回檔平倉
#   - 移動停損追蹤（highest/lowest_since_entry）
#   - 停損優先於平倉條件

import logging
import threading
import time
from datetime import datetime, time as dt_time
from typing import Optional, Dict, Any, List, Callable

from position_models import (
    Position, EntryMode, ProductType,
    create_position
)

logger = logging.getLogger(__name__)

# 券商交易時間（台灣）
TW_SE_OPEN  = dt_time(9, 0)   # 盤前開始
TW_SE_CLOSE = dt_time(13, 30) # 盤後結束


class DayTradeService:
    """
    當日沖銷服務（雙模式版）

    核心功能：
    - entry(symbol, mode, price, qty): 建倉（breakdown_buy 或 breakout_sell）
    - check_exit(symbol, current_price): 檢查是否觸發平倉條件
    - auto_close_all(): 手動觸發全部平倉（移動停損 / 收盤前）
    - get_position(symbol): 取得特定持倉

    使用範例:
        svc = DayTradeService(fubon_client)

        # 建倉：追低點買入（多方）
        pos = svc.entry("2330", EntryMode.BREAKDOWN_BUY,
                        price=605.0, quantity=2000,
                        stop_loss_pct=2.0, track_levels=1)

        # 行情饋入：持續更新持倉的 highest/lowest
        svc.update_price("2330", current_price=610.0)

        # 檢查是否需要平倉
        should_close, reason = svc.check_exit("2330", current_price=608.0)
        if should_close:
            svc.close_position("2330", reason)

        # 平倉（手動 / 移動停損觸發）
        result = svc.close_position("2330", reason="track_exit")
    """

    def __init__(self, fubon_client=None):
        """
        Args:
            fubon_client: FubonClient 實例（用於實際下單，可為 None 做脫機計算）
        """
        self._client = fubon_client

        # 持倉字典 key: symbol → Position
        self._positions: Dict[str, Position] = {}

        # 平倉歷史（用於記錄與回測）
        self._closed_positions: List[Dict[str, Any]] = []

        # 平倉失敗記錄
        self._failed_orders: List[Dict[str, Any]] = []

        # 鎖（執行緒安全）
        self._lock = threading.Lock()

        logger.info("DayTradeService 初始化完成（雙模式版）")

    # ══════════════════════════════════════════════════════════════
    # 建倉 — entry()
    # ══════════════════════════════════════════════════════════════

    def entry(
        self,
        symbol: str,
        entry_mode: str,
        price: float,
        quantity: int,
        stop_loss_pct: float = 2.0,
        track_levels: int = 1,
        product_type: str = "stock",
        account_id: str = "",
        order_no: Optional[str] = None,
        tick_size: float = 0.1,
    ) -> Dict[str, Any]:
        """
        建倉（支援雙模式）

        Args:
            symbol:        商品代碼（2330 / TXF202506）
            entry_mode:    "breakdown_buy" | "breakout_sell"
                           breakdown_buy  → 做多（回檔低點買入）
                           breakout_sell  → 做空（反彈高點放空）
            price:         建倉成交價
            quantity:      張數（股票）或口數（期貨）
            stop_loss_pct: 停損百分比（預設 2%）
            track_levels:  追蹤檔位（1~5，預設 1）
            product_type:  "stock" | "futures"
            account_id:    帳號識別
            order_no:      委託書號
            tick_size:     最小報價單位（股票 0.1，期貨 1.0）

        Returns:
            dict — 建倉結果（position dict）
        """
        mode = EntryMode(entry_mode)
        prod = ProductType(product_type)

        with self._lock:
            # 若已有相同 symbol 的未平倉，先警告
            if symbol in self._positions:
                old = self._positions[symbol]
                if old.status == "open":
                    logger.warning(
                        f"{symbol} 已有未平倉持倉（{old.entry_mode.value}），"
                        f"先平倉再新建倉"
                    )
                    self._force_close_position(symbol, reason="re-entry")

            # 建立新持倉
            pos = create_position(
                symbol=symbol,
                product_type=prod,
                entry_mode=mode,
                quantity=quantity,
                entry_price=price,
                stop_loss_pct=stop_loss_pct,
                track_levels=track_levels,
                tick_size=tick_size,
                account_id=account_id,
                order_no=order_no,
            )

            self._positions[symbol] = pos

            logger.info(
                f"建倉成功: {pos}"
            )

            return {
                "success": True,
                "position": pos.to_dict(),
                "message": f"{mode.value} 建倉完成 @ {price}",
            }

    # ══════════════════════════════════════════════════════════════
    # 行情更新 — update_price()
    # ══════════════════════════════════════════════════════════════

    def update_price(self, symbol: str, current_price: float) -> None:
        """
        接收即時報價，更新持倉的價格邊界（highest/lowest_since_entry）

        由行情監控服務每秒呼叫

        Args:
            symbol:        商品代碼
            current_price: 即時價格
        """
        with self._lock:
            if symbol not in self._positions:
                return
            pos = self._positions[symbol]
            if pos.status != "open":
                return
            pos.update_price(current_price)

    # ══════════════════════════════════════════════════════════════
    # 平倉條件檢查 — check_exit()
    # ══════════════════════════════════════════════════════════════

    def check_exit(
        self, symbol: str, current_price: float
    ) -> tuple[bool, str]:
        """
        檢查是否觸發平倉條件

        平倉優先順序：
        1. 停損觸發（最高優先）
        2. 移動停損回檔觸發（breakout_exit / breakdown_exit）

        Args:
            symbol:        商品代碼
            current_price: 即時價格

        Returns:
            (should_close: bool, reason: str)
            reason: "stop_loss" | "breakout_exit" | "breakdown_exit" | ""
        """
        with self._lock:
            if symbol not in self._positions:
                return False, ""

            pos = self._positions[symbol]
            if pos.status != "open":
                return False, ""

            # 1. 停損檢查
            if pos.is_stop_loss_hit(current_price):
                return True, "stop_loss"

            # 2. 移動停損回檔檢查
            if pos.entry_mode == EntryMode.BREAKDOWN_BUY:
                if pos.should_close_long(current_price):
                    return True, "breakout_exit"
            else:  # BREAKOUT_SELL
                if pos.should_close_short(current_price):
                    return True, "breakdown_exit"

            return False, ""

    # ══════════════════════════════════════════════════════════════
    # 平倉執行 — close_position()
    # ══════════════════════════════════════════════════════════════

    def close_position(
        self, symbol: str, reason: str = "manual", account_id: str = ""
    ) -> Dict[str, Any]:
        """
        平倉執行

        Args:
            symbol:     商品代碼
            reason:     平倉原因
            account_id: 帳號（驗證是否與持倉相符，防止他人平倉）
        """
        with self._lock:
            if symbol not in self._positions:
                return {"success": False, "message": f"找不到 {symbol} 持倉"}

            pos = self._positions[symbol]
            if pos.status == "closed":
                return {"success": False, "message": f"{symbol} 已平倉"}

            # 驗證帳號（若提供了 account_id）
            if account_id and pos.account_id and account_id != pos.account_id:
                return {"success": False, "message": "帳號不符，拒絕平倉"}

        # 決定平倉方向（與進場反向）
        if pos.entry_mode == EntryMode.BREAKDOWN_BUY:
            close_bs = "sell"
        else:
            close_bs = "buy"

        # 呼叫富邦 API 下單（市價 IOC）
        if self._client:
            try:
                close_resp = self._client.place_order(
                    stock_no=symbol,
                    price=None,         # 市價
                    quantity=pos.quantity,
                    order_type="market",
                    buy_sell=close_bs,
                    time_in_force="ioc",
                )
                if not close_resp.get("success"):
                    self._failed_orders.append({
                        "symbol": symbol,
                        "reason": close_resp.get("message", "未知錯誤"),
                        "close_reason": reason,
                        "time": datetime.now().isoformat(),
                    })
                    return {
                        "success": False,
                        "message": f"平倉下單失敗: {close_resp.get('message')}",
                    }
                order_no = close_resp.get("order_no")
                logger.info(
                    f"平倉成功: {symbol} x {pos.quantity} {close_bs} "
                    f"(@ 市價, reason={reason}) order_no={order_no}"
                )
            except Exception as e:
                logger.error(f"平倉執行例外: {e}")
                self._failed_orders.append({
                    "symbol": symbol,
                    "reason": str(e),
                    "close_reason": reason,
                    "time": datetime.now().isoformat(),
                })
                return {"success": False, "message": f"平倉錯誤: {e}"}
        else:
            # 無 client 時做脫機模擬
            order_no = f"mock_{symbol}_{int(time.time())}"
            logger.info(f"[MOCK] 平倉: {symbol} x {pos.quantity} {close_bs} reason={reason}")

        # 更新持倉狀態
        return self._finalize_close(symbol, close_bs, order_no, reason)

    def _finalize_close(
        self, symbol: str, close_bs: str, order_no: str, reason: str
    ) -> Dict[str, Any]:
        """內部：標記持倉為已平倉並記錄"""
        pos = self._positions[symbol]
        pos.status = "closed"
        pos.closed_at = datetime.now()

        # 計算已實現損益（使用建倉成本）
        realized_pnl = pos.unrealized_pnl(pos.entry_cost)
        pos.realized_pnl = realized_pnl

        record = {
            "symbol": symbol,
            "entry_mode": pos.entry_mode.value,
            "entry_price": pos.entry_price,
            "entry_cost": pos.entry_cost,
            "close_price": pos.highest_since_entry if pos.entry_mode == EntryMode.BREAKDOWN_BUY else pos.lowest_since_entry,
            "quantity": pos.quantity,
            "entry_time": pos.entry_time.isoformat(),
            "close_time": pos.closed_at.isoformat(),
            "realized_pnl": round(realized_pnl, 2),
            "close_reason": reason,
            "order_no": order_no,
        }
        self._closed_positions.append(record)

        logger.info(
            f"平倉記錄: {symbol} {pos.entry_mode.value} "
            f"建倉@{pos.entry_price:.2f} → 平倉@{record['close_price']:.2f} "
            f"損益: {realized_pnl:+.2f}"
        )

        return {
            "success": True,
            "position": pos.to_dict(),
            "realized_pnl": round(realized_pnl, 2),
            "close_reason": reason,
            "order_no": order_no,
        }

    def _force_close_position(self, symbol: str, reason: str) -> Dict[str, Any]:
        """內部：強制平倉（不做 API 呼叫，用於重新進場）"""
        pos = self._positions.get(symbol)
        if not pos:
            return {"success": False, "message": f"找不到 {symbol}"}
        pos.status = "closed"
        pos.closed_at = datetime.now()
        return {"success": True, "message": f"強制平倉 {symbol}"}

    # ══════════════════════════════════════════════════════════════
    # 取得持倉
    # ══════════════════════════════════════════════════════════════

    def get_position(self, symbol: str) -> Optional[Dict[str, Any]]:
        """取得特定 symbol 的持倉狀態"""
        with self._lock:
            pos = self._positions.get(symbol)
            if not pos:
                return None
            result = pos.to_dict()
            # 加入目前即時未實現損益（需外部餵入 current_price）
            return result

    def get_all_positions(self) -> List[Dict[str, Any]]:
        """取得所有持倉"""
        with self._lock:
            return [p.to_dict() for p in self._positions.values() if p.status == "open"]

    def get_closed_positions(self) -> List[Dict[str, Any]]:
        """取得已平倉記錄（回測用）"""
        with self._lock:
            return list(self._closed_positions)

    # ══════════════════════════════════════════════════════════════
    # 批量平倉（13:20 觸發，或用戶主動呼叫）
    # ══════════════════════════════════════════════════════════════

    def auto_close_all(self, reason: str = "manual") -> Dict[str, Any]:
        """
        平掉所有未平倉持倉（用於手動觸發或 13:20 收盤前）

        Args:
            reason: 平倉原因標記

        Returns:
            dict — 統計結果（成功/失敗筆數）
        """
        with self._lock:
            open_symbols = [
                sym for sym, p in self._positions.items()
                if p.status == "open"
            ]

        if not open_symbols:
            return {
                "success": True,
                "summary": "無未平倉持倉",
                "closed": 0,
                "failed": 0,
            }

        results = {"closed": 0, "failed": 0, "details": []}
        for symbol in open_symbols:
            resp = self.close_position(symbol, reason=reason)
            if resp.get("success"):
                results["closed"] += 1
            else:
                results["failed"] += 1
            results["details"].append({"symbol": symbol, **resp})

        results["summary"] = (
            f"自動平倉完成：{results['closed']} 檔成功，{results['failed']} 檔失敗"
        )
        logger.info(results["summary"])
        return results

    # ══════════════════════════════════════════════════════════════
    # 損益計算
    # ══════════════════════════════════════════════════════════════

    def calculate_pnl(self, current_prices: Dict[str, float]) -> Dict[str, Any]:
        """
        計算所有持倉的未實現 + 已實現損益

        Args:
            current_prices: dict — key: symbol, value: current_price

        Returns:
            dict — 損益報告
        """
        with self._lock:
            realized = sum(p.realized_pnl for p in self._positions.values())
            unrealized = sum(
                p.unrealized_pnl(current_prices.get(p.symbol, p.entry_cost))
                for p in self._positions.values()
                if p.status == "open"
            )
            total = realized + unrealized

            positions_detail = []
            for p in self._positions.values():
                cp = current_prices.get(p.symbol, p.entry_cost)
                d = p.to_dict()
                d["current_price"] = cp
                d["current_unrealized_pnl"] = round(p.unrealized_pnl(cp), 2)
                positions_detail.append(d)

            return {
                "realized_pnl": round(realized, 2),
                "unrealized_pnl": round(unrealized, 2),
                "total_pnl": round(total, 2),
                "open_positions": len([p for p in self._positions.values() if p.status == "open"]),
                "closed_positions": len([p for p in self._positions.values() if p.status == "closed"]),
                "positions": positions_detail,
                "calculated_at": datetime.now().isoformat(),
            }

    # ══════════════════════════════════════════════════════════════
    # 失敗訂單查詢
    # ══════════════════════════════════════════════════════════════

    def get_failed_orders(self) -> List[Dict[str, Any]]:
        """回傳平倉失敗的記錄"""
        with self._lock:
            return list(self._failed_orders)

    # ══════════════════════════════════════════════════════════════
    # 除錯用
    # ══════════════════════════════════════════════════════════════

    def __repr__(self) -> str:
        with self._lock:
            open_pos = [p for p in self._positions.values() if p.status == "open"]
            return f"DayTradeService(持倉: {len(open_pos)} 檔)"
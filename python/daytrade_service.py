# daytrade_service.py — 富邦當日沖銷核心服務
# 實作自動平倉檢查、損益計算、條件單觸發邏輯

import logging
import threading
import time
from datetime import datetime, time as dt_time
from typing import Optional, Dict, Any, List, Callable

logger = logging.getLogger(__name__)

# 券商交易時間（台灣）
TW_SE_OPEN  = dt_time(9, 0)   # 盤前開始
TW_SE_CLOSE = dt_time(13, 30) # 盤後結束
AUTO_SQUARING_TIME = dt_time(13, 20) # 自動平倉檢查時間（13:20）


class DayTradeService:
    """
    當日沖銷服務封裝

    使用範例:
        service = DayTradeService(fubon_client)
        service.start()
        # ... 執行策略 ...
        service.stop()
    """

    def __init__(self, fubon_client, auto_square_time: dt_time = AUTO_SQUARING_TIME):
        """
        Args:
            fubon_client:   已登入的 FubonClient 實例
            auto_square_time: 自動平倉檢查時間（預設 13:20）
        """
        self._client = fubon_client
        self._auto_square_time = auto_square_time
        self._running = False
        self._timer_thread: Optional[threading.Thread] = None

        # 條件單回調
        self._condition_triggers: List[Dict[str, Any]] = []

        # 當日沖部位的 key: symbol → { buy_qty, sell_qty, avg_buy, avg_sell }
        self._positions: Dict[str, Dict[str, Any]] = {}

        # 平倉失敗記錄
        self._failed_orders: List[Dict[str, Any]] = []

    # ══════════════════════════════════════════════════════════════
    # 部位管理
    # ══════════════════════════════════════════════════════════════

    def sync_positions(self):
        """
        同步富邦帳號內的當日部位

        讀取當日委託單與成交回報，計算各標的目前淨部位
        """
        try:
            orders = self._client.get_today_orders()
            fills  = self._get_today_fills()

            self._positions.clear()

            # 從委託單計算淨部位
            for o in orders:
                sym = o.get("symbol")
                if not sym:
                    continue
                bs  = o.get("buy_sell", "").lower()
                qty = o.get("after_qty", 0)

                if sym not in self._positions:
                    self._positions[sym] = {
                        "buy_qty": 0, "sell_qty": 0,
                        "buy_amount": 0.0, "sell_amount": 0.0,
                        "buy_avg": 0.0, "sell_avg": 0.0,
                    }

                if "buy" in bs:
                    self._positions[sym]["buy_qty"] += qty
                else:
                    self._positions[sym]["sell_qty"] += qty

            # 從成交補正平均成本
            for f in fills:
                sym = f.get("symbol")
                if not sym or sym not in self._positions:
                    continue
                price = float(f.get("price", 0))
                qty   = int(f.get("quantity", 0))

                if "buy" in f.get("buy_sell", "").lower():
                    pos = self._positions[sym]
                    total = pos["buy_amount"] + price * qty
                    new_qty = pos["buy_qty"] + qty
                    pos["buy_avg"] = total / new_qty if new_qty else 0
                    pos["buy_amount"] = total
                else:
                    pos = self._positions[sym]
                    total = pos["sell_amount"] + price * qty
                    new_qty = pos["sell_qty"] + qty
                    pos["sell_avg"] = total / new_qty if new_qty else 0
                    pos["sell_amount"] = total

            logger.info(f"當日部位同步完成，共 {len(self._positions)} 檔")
            return self._positions

        except Exception as e:
            logger.error(f"同步部位失敗: {e}")
            return {}

    def _get_today_fills(self) -> List[Dict[str, Any]]:
        """讀取當日成交資料（需富邦 SDK 支援 filled_history）"""
        try:
            # 富邦 SDK: sdk.stock.filled_history(account, date, date)
            today = datetime.now().strftime("%Y%m%d")
            resp = self._client._sdk.stock.filled_history(
                self._client._stock_account.id, today, today
            )
            if resp.is_success:
                return resp.data
        except Exception as e:
            logger.warning(f"讀取成交歷史失敗: {e}")
        return []

    # ══════════════════════════════════════════════════════════════
    # 自動平倉檢查（13:20）
    # ══════════════════════════════════════════════════════════════

    def auto_squaring_check(self) -> Dict[str, Any]:
        """
        13:20 自動平倉檢查

        當日沖必須在 13:30 收盤前完成反向沖銷。
        此方法會自動平掉所有尚未平倉的當日沖部位。

        Returns:
            dict — 平倉結果統計
        """
        now = datetime.now()
        logger.info(
            f"[{now.strftime('%Y-%m-%d %H:%M:%S')}] 執行自動平倉檢查"
        )

        # 先同步最新部位
        self.sync_positions()

        result = {
            "time":       now.isoformat(),
            "total_positions": len(self._positions),
            "orders_placed":   [],
            "failed":          [],
            "summary":         "",
        }

        for symbol, pos in self._positions.items():
            buy_qty  = pos.get("buy_qty", 0)
            sell_qty = pos.get("sell_qty", 0)

            # 計算淨部位（當日沖 = 今日買進並卖出 or 今日賣出並買回）
            net_qty = buy_qty - sell_qty

            if net_qty == 0:
                logger.debug(f"{symbol} 淨部位為 0，跳過")
                continue

            # 決定平倉方向：淨部位 > 0 → 賣出平倉；< 0 → 買入平倉
            if net_qty > 0:
                # 今日買超，需賣出
                close_price = pos.get("sell_avg", 0) or pos.get("buy_avg", 0)
                close_resp = self._client.place_order(
                    stock_no=symbol,
                    price=close_price,
                    quantity=net_qty,
                    order_type="limit",
                    buy_sell="sell",
                )
            else:
                # 今日賣超，需買回
                net_qty = abs(net_qty)
                close_price = pos.get("buy_avg", 0) or pos.get("sell_avg", 0)
                close_resp = self._client.place_order(
                    stock_no=symbol,
                    price=close_price,
                    quantity=net_qty,
                    order_type="limit",
                    buy_sell="buy",
                )

            if close_resp.get("success"):
                result["orders_placed"].append({
                    "symbol":   symbol,
                    "qty":      net_qty,
                    "price":    close_price,
                    "order_no": close_resp.get("order_no"),
                })
                logger.info(f"自動平倉 {symbol} x {net_qty} @ {close_price}")
            else:
                result["failed"].append({
                    "symbol": symbol,
                    "reason": close_resp.get("message", "未知錯誤"),
                })
                logger.error(f"自動平倉失敗 {symbol}: {close_resp.get('message')}")

        # 組合摘要
        n = len(result["orders_placed"])
        f = len(result["failed"])
        result["summary"] = (
            f"自動平倉完成：{n} 檔成功，{f} 檔失敗"
        )
        logger.info(result["summary"])

        return result

    # ══════════════════════════════════════════════════════════════
    # 當日沖銷損益計算
    # ══════════════════════════════════════════════════════════════

    def calculate_profit_loss(self) -> Dict[str, Any]:
        """
        計算當日沖銷損益

        計算公式：
          當日沖銷損益 = Σ[(賣出均價 - 買入均價) × 數量] - 手续费

        Returns:
            dict — 包含總損益、已平倉、未平倉明細
        """
        self.sync_positions()

        realized_pnl  = 0.0   # 已實現
        unrealized_pnl = 0.0 # 未實現（帳面）
        detail = []

        for symbol, pos in self._positions.items():
            buy_qty   = pos.get("buy_qty", 0)
            sell_qty  = pos.get("sell_qty", 0)
            buy_avg   = pos.get("buy_avg", 0.0)
            sell_avg  = pos.get("sell_avg", 0.0)

            # 當日冲：買賣數量相等才視為完成一轮
            matched = min(buy_qty, sell_qty)
            remaining_buy  = buy_qty - matched
            remaining_sell = sell_qty - matched

            pnl = (sell_avg - buy_avg) * matched
            realized_pnl += pnl

            # 未平倉帳面損益（假設現價 = 成本价）
            # 若有即時報價，替換 unrealized_price 計算
            unrealized = 0.0  # 需串接即時報價

            detail.append({
                "symbol":           symbol,
                "buy_qty":          buy_qty,
                "sell_qty":         sell_qty,
                "buy_avg":          round(buy_avg, 2),
                "sell_avg":         round(sell_avg, 2),
                "matched":          matched,
                "realized_pnl":     round(pnl, 2),
                "remaining_buy":     remaining_buy,
                "remaining_sell":   remaining_sell,
            })

        total_pnl = realized_pnl + unrealized_pnl

        return {
            "calculated_at": datetime.now().isoformat(),
            "realized_pnl":  round(realized_pnl, 2),
            "unrealized_pnl": round(unrealized_pnl, 2),
            "total_pnl":      round(total_pnl, 2),
            "positions":      detail,
            "summary": (
                f"當日沖總損益: {total_pnl:+.2f} "
                f"(已實現: {realized_pnl:+.2f}, 未實現: {unrealized_pnl:+.2f})"
            ),
        }

    # ══════════════════════════════════════════════════════════════
    # 條件單觸發邏輯
    # ══════════════════════════════════════════════════════════════

    ConditionOrder = Dict[str, Any]

    def add_condition_order(
        self,
        symbol: str,
        condition_type: str,
        trigger_price: float,
        action: str,
        quantity: int,
        order_price: Optional[float] = None,
        callback: Optional[Callable] = None,
    ) -> str:
        """
        新增條件單

        Args:
            symbol:        股票代碼
            condition_type: "above" | "below" | "change_up" | "change_down"
            trigger_price: 觸發條件價
            action:        "buy" | "sell"
            quantity:      委託數量
            order_price:   委託價格（None = 市價）
            callback:      觸發後回調函數

        Returns:
            str — 條件單 ID
        """
        cond_id = f"cond_{symbol}_{int(time.time() * 1000)}"
        order = {
            "id":            cond_id,
            "symbol":        symbol,
            "condition_type": condition_type,
            "trigger_price": trigger_price,
            "action":        action,
            "quantity":      quantity,
            "order_price":   order_price,
            "callback":      callback,
            "active":        True,
            "triggered_at":  None,
        }
        self._condition_triggers.append(order)
        logger.info(f"條件單已加入: {cond_id} ({symbol} {condition_type} {trigger_price})")
        return cond_id

    def condition_order_trigger(
        self,
        symbol: str,
        current_price: float,
    ) -> Optional[Dict[str, Any]]:
        """
        條件單觸發檢查

        將即時報價饋入，檢查是否有條件單被觸發。

        Args:
            symbol:        股票代碼
            current_price: 即時價格

        Returns:
            dict — 觸發的條件單資訊；若無則回傳 None
        """
        triggered = None

        for cond in self._condition_triggers:
            if not cond.get("active") or cond.get("symbol") != symbol:
                continue

            tp    = cond["trigger_price"]
            ctype = cond["condition_type"]

            fired = False
            if ctype == "above" and current_price >= tp:
                fired = True
            elif ctype == "below" and current_price <= tp:
                fired = True
            elif ctype == "change_up":
                # 需有昨收價比對，此處簡化為 >= 1% 上漲
                fired = (current_price / tp - 1) >= 0.01
            elif ctype == "change_down":
                fired = (current_price / tp - 1) <= -0.01

            if fired:
                cond["active"] = False
                cond["triggered_at"] = datetime.now().isoformat()
                logger.info(
                    f"條件單觸發: {cond['id']} {symbol} {current_price} "
                    f"(條件: {ctype} {tp})"
                )

                # 執行委託
                try:
                    resp = self._client.place_order(
                        stock_no=symbol,
                        price=cond["order_price"],
                        quantity=cond["quantity"],
                        buy_sell=cond["action"],
                    )
                    cond["order_response"] = resp

                    # 執行回呼
                    if cond.get("callback"):
                        cond["callback"](cond, resp)

                except Exception as e:
                    logger.error(f"條件單 {cond['id']} 執行失敗: {e}")
                    cond["error"] = str(e)

                triggered = cond
                break  # 一次只觸發一筆

        return triggered

    def remove_condition_order(self, cond_id: str) -> bool:
        """移除條件單"""
        for i, c in enumerate(self._condition_triggers):
            if c["id"] == cond_id:
                c["active"] = False
                logger.info(f"條件單已移除: {cond_id}")
                return True
        return False

    def list_condition_orders(self) -> List[Dict[str, Any]]:
        """列出所有條件單"""
        return [dict(c) for c in self._condition_triggers]

    # ══════════════════════════════════════════════════════════════
    # 定時器管理（13:20 自動平倉）
    # ══════════════════════════════════════════════════════════════

    def start(self):
        """啟動當日沖服務（含定時自動平倉）"""
        if self._running:
            logger.warning("DayTradeService 已在執行中")
            return

        self._running = True
        self._timer_thread = threading.Thread(target=self._timer_loop, daemon=True)
        self._timer_thread.start()
        logger.info("DayTradeService 已啟動")

    def stop(self):
        """停止當日沖服務"""
        self._running = False
        if self._timer_thread:
            self._timer_thread.join(timeout=5)
        logger.info("DayTradeService 已停止")

    def _timer_loop(self):
        """定時檢查執行緒"""
        while self._running:
            now = datetime.now()
            current_t = now.time()

            # 檢查是否在交易時段
            if TW_SE_OPEN <= current_t <= TW_SE_CLOSE:
                # 比較時間（忽略日期，只比時分秒）
                sq_time = self._auto_square_time
                if (
                    current_t.hour   == sq_time.hour and
                    current_t.minute == sq_time.minute and
                    current_t.second <  5
                ):
                    logger.info("觸發自動平倉時間點，執行平倉...")
                    try:
                        self.auto_squaring_check()
                    except Exception as e:
                        logger.error(f"自動平倉執行失敗: {e}")

            # 每 60 秒檢查一次
            time.sleep(60)
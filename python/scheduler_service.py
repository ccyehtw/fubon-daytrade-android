# scheduler_service.py — 自動排程服務
# 負責 13:20 股票自動平倉、13:30 期貨自動平倉、08:30 條件單預掃
#
# 排程時間表：
#   08:30 — 條件單引擎 evaluate_all()（开盘前预扫条件单）
#   13:20 — 股票當日沖自動平倉（DayTradeService.auto_squaring_check）
#   13:30 — 期貨自動平倉
#   每分鐘 — 漲跌停監控（LimitUpDownService.check_limit_up_down）

import asyncio
import logging
import schedule
import time
import threading
from datetime import datetime, time as dt_time
from typing import Optional, Dict, Any

logger = logging.getLogger(__name__)

# 排程時間設定
SCHEDULED_AUTO_SQUARE_STOCK  = dt_time(13, 20)  # 股票自動平倉
SCHEDULED_AUTO_SQUARE_FUTURE  = dt_time(13, 30)  # 期貨自動平倉
SCHEDULED_PRE_MARKET_SCAN     = dt_time(8, 30)   # 盤前條件單預掃

# 台灣股市交易時段
TW_TRADE_START = dt_time(9, 0)
TW_TRADE_END   = dt_time(13, 30)


class AutoSquareResult:
    """自動平倉結果封裝"""
    def __init__(
        self,
        success_count: int = 0,
        failed_count: int = 0,
        total_profit: float = 0.0,
        orders: list = None,
        failed: list = None,
        executed_at: str = None,
    ):
        self.success_count = success_count
        self.failed_count = failed_count
        self.total_profit = total_profit
        self.orders = orders or []
        self.failed = failed or []
        self.executed_at = executed_at or datetime.now().isoformat()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "success_count": self.success_count,
            "failed_count": self.failed_count,
            "total_profit": round(self.total_profit, 2),
            "orders": self.orders,
            "failed": self.failed,
            "executed_at": self.executed_at,
        }


class SchedulerService:
    """
    全域排程服務（單例）
    使用 schedule 庫設定每日定時任務，背景執行。

    排程時間表：
    ┌─────────────┬──────────────────────────────────────────────┐
    │ 時間        │ 任務                                         │
    ├─────────────┼──────────────────────────────────────────────┤
    │ 08:30       │ 條件單引擎預掃（ConditionEngine.evaluate_all）│
    │ 09:00-13:30 │ 每分鐘漲跌停監控                             │
    │ 13:20       │ 股票當日沖自動平倉                          │
    │ 13:30       │ 期貨自動平倉                                 │
    └─────────────┴──────────────────────────────────────────────┘
    """
    _instance: Optional["SchedulerService"] = None
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

        self._running = False
        self._scheduler_thread: Optional[threading.Thread] = None
        self._last_auto_square_result: Optional[AutoSquareResult] = None
        self._status = "idle"  # "idle" | "running"

        # 延遲引用，避免循環 import
        self._daytrade_service = None
        self._condition_engine = None
        self._limit_up_down_service = None
        self._futures_order_module = None

        logger.info("SchedulerService initialized")

    # ──────────────────────────────────────────────
    # 內部元件注入（由 service.py 啟動時注入）
    # ──────────────────────────────────────────────

    def set_daytrade_service(self, svc):
        self._daytrade_service = svc

    def set_condition_engine(self, engine):
        self._condition_engine = engine

    def set_limit_up_down_service(self, svc):
        self._limit_up_down_service = svc

    def set_futures_order_module(self, module):
        self._futures_order_module = module

    # ──────────────────────────────────────────────
    # 市場狀態判斷
    # ──────────────────────────────────────────────

    @staticmethod
    def is_market_open() -> bool:
        """
        判斷是否為台灣交易日（僅檢查時段，非真正的農曆年/假日判斷）
        交易时段：09:00 - 13:30
        """
        now = datetime.now()
        current_t = now.time()
        # 星期判斷（週六週日不交易）
        if now.weekday() >= 6:
            return False
        return TW_TRADE_START <= current_t <= TW_TRADE_END

    @staticmethod
    def is_trading_day() -> bool:
        """簡單判斷是否為交易日（週一至週五）"""
        return datetime.now().weekday() < 5

    # ──────────────────────────────────────────────
    # 排程任務（由 schedule 呼叫）
    # ──────────────────────────────────────────────

    def _job_pre_market_condition_scan(self):
        """08:30 盤前條件單預掃"""
        if not self.is_trading_day():
            logger.debug("非交易日，跳過盤前條件單掃描")
            return

        logger.info("[Scheduler] 執行盤前條件單預掃 (08:30)")
        try:
            if self._condition_engine is not None:
                # 預掃：評估所有 active 條件單（用昨日收盤價初步評估）
                self._condition_engine.evaluate_all_conditions(quote_or_data={})
                logger.info("[Scheduler] 盤前條件單預掃完成")
            else:
                logger.warning("[Scheduler] ConditionEngine 未注入，跳過")
        except Exception as e:
            logger.error(f"[Scheduler] 盤前條件單預掃失敗: {e}")

    def _job_stock_auto_square(self):
        """13:20 股票當日沖自動平倉"""
        if not self.is_market_open():
            logger.debug("非交易時段，跳過股票自動平倉")
            return

        logger.info("[Scheduler] 執行股票當日沖自動平倉 (13:20)")
        try:
            if self._daytrade_service is not None:
                result = self._daytrade_service.auto_squaring_check()
                self._last_auto_square_result = self._convert_to_auto_square_result(result)
                logger.info(
                    f"[Scheduler] 股票自動平倉完成: "
                    f"{self._last_auto_square_result.success_count} 成功, "
                    f"{self._last_auto_square_result.failed_count} 失敗"
                )
            else:
                logger.warning("[Scheduler] DayTradeService 未注入，跳過")
        except Exception as e:
            logger.error(f"[Scheduler] 股票自動平倉失敗: {e}")

    def _job_futures_auto_square(self):
        """13:30 期貨自動平倉"""
        if not self.is_market_open():
            logger.debug("非交易時段，跳過期貨自動平倉")
            return

        logger.info("[Scheduler] 執行期貨自動平倉 (13:30)")
        try:
            if self._futures_order_module is not None:
                self._futures_auto_square_impl()
            else:
                logger.warning("[Scheduler] futures_order 未注入，跳過")
        except Exception as e:
            logger.error(f"[Scheduler] 期貨自動平倉失敗: {e}")

    def _job_limit_up_down_monitor(self):
        """每分鐘執行的漲跌停監控（僅在交易時段）"""
        if not self.is_market_open():
            return

        try:
            if self._limit_up_down_service is not None:
                self._limit_up_down_service.check_limit_up_down()
            else:
                logger.debug("[Scheduler] LimitUpDownService 未注入，跳過漲跌停監控")
        except Exception as e:
            logger.error(f"[Scheduler] 漲跌停監控失敗: {e}")

    # ──────────────────────────────────────────────
    # 期貨自動平倉實作
    # ──────────────────────────────────────────────

    def _futures_auto_square_impl(self):
        """
        取出期貨持倉並自動平倉
        - 多單（buy_lots > 0）→ 送出反向 sell 了結
        - 空單（sell_lots > 0）→ 送出反向 buy 了結
        使用市價 IOC 委託
        """
        try:
            # 取得期貨持倉
            positions = self._futures_order_module.get_futures_positions()
            if not positions or "positions" not in positions:
                logger.info("[Scheduler] 無期貨持倉")
                return

            success_count = 0
            failed_count = 0

            for pos in positions.get("positions", []):
                buy_lots  = pos.get("buy_lots", 0)
                sell_lots = pos.get("sell_lots", 0)

                if buy_lots > 0:
                    # 多單：卖出平倉
                    resp = self._futures_order_module.place_futures_order(
                        account=pos.get("account", ""),
                        futures_code=pos.get("code", ""),
                        price=None,  # 市價
                        quantity=buy_lots,
                        bs="sell"
                    )
                    if resp.get("success"):
                        success_count += 1
                        logger.info(f"[Scheduler] 期貨多單平倉 {pos.get('code')} x {buy_lots}")
                    else:
                        failed_count += 1
                        logger.error(f"[Scheduler] 期貨多單平倉失敗: {resp.get('message')}")

                elif sell_lots > 0:
                    # 空單：买入平倉
                    resp = self._futures_order_module.place_futures_order(
                        account=pos.get("account", ""),
                        futures_code=pos.get("code", ""),
                        price=None,  # 市價
                        quantity=sell_lots,
                        bs="buy"
                    )
                    if resp.get("success"):
                        success_count += 1
                        logger.info(f"[Scheduler] 期貨空單平倉 {pos.get('code')} x {sell_lots}")
                    else:
                        failed_count += 1
                        logger.error(f"[Scheduler] 期貨空單平倉失敗: {resp.get('message')}")

            logger.info(
                f"[Scheduler] 期貨自動平倉完成: {success_count} 成功, {failed_count} 失敗"
            )

        except Exception as e:
            logger.error(f"[Scheduler] 期貨自動平倉實作失敗: {e}")

    # ──────────────────────────────────────────────
    # 結果轉換
    # ──────────────────────────────────────────────

    def _convert_to_auto_square_result(self, raw_result: Dict[str, Any]) -> AutoSquareResult:
        """將 DayTradeService.auto_squaring_check() 的回傳轉為 AutoSquareResult"""
        orders = raw_result.get("orders_placed", [])
        failed = raw_result.get("failed", [])

        # 簡單計算 total_profit（需根據實際成交回報更新）
        total_profit = 0.0
        for o in orders:
            total_profit += o.get("profit", 0.0)

        return AutoSquareResult(
            success_count=len(orders),
            failed_count=len(failed),
            total_profit=total_profit,
            orders=orders,
            failed=failed,
            executed_at=raw_result.get("time", datetime.now().isoformat()),
        )

    # ──────────────────────────────────────────────
    # 啟動 / 停止排程
    # ──────────────────────────────────────────────

    def start(self):
        """啟動排程服務（背景執行）"""
        if self._running:
            logger.warning("SchedulerService 已在執行中")
            return

        self._running = True
        self._status = "running"

        # 清除舊排程（防止重複）
        schedule.clear()

        # 設定每日定時任務
        schedule.every().day.at("08:30").do(self._job_pre_market_condition_scan)
        schedule.every().day.at("13:20").do(self._job_stock_auto_square)
        schedule.every().day.at("13:30").do(self._job_futures_auto_square)

        # 每分鐘執行漲跌停監控
        schedule.every(1).minutes.do(self._job_limit_up_down_monitor)

        # 啟動排程執行緒
        self._scheduler_thread = threading.Thread(
            target=self._run_schedule_loop,
            daemon=True,
            name="SchedulerThread"
        )
        self._scheduler_thread.start()

        logger.info(
            "SchedulerService 已啟動 | "
            f"盤前掃描 08:30 | 股票平倉 13:20 | 期貨平倉 13:30 | "
            f"漲跌停監控 每分鐘"
        )

    def stop(self):
        """停止排程服務"""
        if not self._running:
            logger.warning("SchedulerService 未在執行中")
            return

        self._running = False
        self._status = "idle"
        schedule.clear()

        if self._scheduler_thread:
            self._scheduler_thread.join(timeout=5)
            self._scheduler_thread = None

        logger.info("SchedulerService 已停止")

    def _run_schedule_loop(self):
        """排程執行迴圈（背景執行）"""
        logger.info("排程執行緒啟動")
        while self._running:
            schedule.run_pending()
            time.sleep(30)  # 每 30 秒檢查一次

    # ──────────────────────────────────────────────
    # API 查詢
    # ──────────────────────────────────────────────

    @property
    def status(self) -> str:
        """回傳排程狀態"""
        return self._status

    def get_auto_square_result(self) -> Optional[Dict[str, Any]]:
        """回傳最近一次自動平倉結果"""
        if self._last_auto_square_result:
            return self._last_auto_square_result.to_dict()
        return None

    def trigger_now(self) -> Dict[str, Any]:
        """
        手動觸發一次自動平倉（測試用）
        同時執行股票與期貨平倉
        """
        logger.info("[Scheduler] 手動觸發自動平倉")
        results = []

        # 股票
        if self._daytrade_service:
            try:
                r = self._daytrade_service.auto_squaring_check()
                self._last_auto_square_result = self._convert_to_auto_square_result(r)
                results.append({"type": "stock", "result": r})
            except Exception as e:
                results.append({"type": "stock", "error": str(e)})

        # 期貨
        if self._futures_order_module:
            try:
                self._futures_auto_square_impl()
                results.append({"type": "futures", "result": "completed"})
            except Exception as e:
                results.append({"type": "futures", "error": str(e)})

        return {"triggered_at": datetime.now().isoformat(), "results": results}


# 全域單例
scheduler_service = SchedulerService()
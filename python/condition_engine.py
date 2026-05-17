# condition_engine.py — 條件單觸發引擎（核心）
# 由 quotes 推送餵入即時報價，評估所有未觸發條件單

import sqlite3
import os
import logging
from dataclasses import dataclass, field
from typing import Optional, Dict, Any, List
from datetime import datetime
import threading

logger = logging.getLogger(__name__)

# SQLite 資料庫路徑
DB_PATH = os.path.join(os.path.dirname(__file__), "condition_orders.db")

# ══════════════════════════════════════════════════════════════
# 資料結構
# ══════════════════════════════════════════════════════════════

@dataclass
class ConditionOrder:
    """
    條件單資料類

    Attributes:
        id:            條件單唯一識別碼
        symbol:        商品代碼（股票或期貨）
        trigger_price: 觸發條件價
        trigger_type:  觸發類型
                       - "above": 價格突破上限
                       - "below": 價格突破下限
                       - "change_up": 漲幅達標
                       - "change_down": 跌幅達標
                       - "volume_above": 成交量突破
        quantity:      委託數量
        bs:            買賣別 "buy" | "sell"
        order_price:   委託價格（None = 市價）
        created_at:    建立時間
        status:        狀態 "active" | "triggered" | "cancelled"
        triggered_at:  觸發時間
        order_no:      觸發後的委託書號
    """
    id: str
    symbol: str
    trigger_price: float
    trigger_type: str
    quantity: int
    bs: str
    order_price: Optional[float] = None
    created_at: str = field(default_factory=lambda: datetime.now().isoformat())
    status: str = "active"
    triggered_at: Optional[str] = None
    order_no: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "symbol": self.symbol,
            "trigger_price": self.trigger_price,
            "trigger_type": self.trigger_type,
            "quantity": self.quantity,
            "bs": self.bs,
            "order_price": self.order_price,
            "created_at": self.created_at,
            "status": self.status,
            "triggered_at": self.triggered_at,
            "order_no": self.order_no,
        }


# ══════════════════════════════════════════════════════════════
# 條件單引擎
# ══════════════════════════════════════════════════════════════

class ConditionEngine:
    """
    條件單觸發引擎

    由 quotes 推送呼叫 evaluate_all_conditions()，評估所有未觸發條件單。
    當條件觸發時，回傳被觸發的條件單列表（由 service.py 呼叫下單）。

    使用範例:
        engine = ConditionEngine()
        engine.add_condition_order(...)
        # 每當收到報價推送時:
        engine.evaluate_all_conditions(quote_data)
    """

    def __init__(self):
        self._conditions: List[ConditionOrder] = []
        self._lock = threading.Lock()
        self._init_db()

    # ══════════════════════════════════════════════════════════════
    # 資料庫
    # ══════════════════════════════════════════════════════════════

    def _init_db(self):
        """初始化 SQLite 資料庫"""
        conn = sqlite3.connect(DB_PATH)
        cursor = conn.cursor()
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS condition_orders (
                id TEXT PRIMARY KEY,
                symbol TEXT NOT NULL,
                trigger_price REAL NOT NULL,
                trigger_type TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                bs TEXT NOT NULL,
                order_price REAL,
                created_at TEXT NOT NULL,
                status TEXT DEFAULT 'active',
                triggered_at TEXT,
                order_no TEXT
            )
        """)
        conn.commit()
        conn.close()

    # ══════════════════════════════════════════════════════════════
    # 條件單 CRUD
    # ══════════════════════════════════════════════════════════════

    def add_condition_order(self, order: ConditionOrder) -> str:
        """
        新增條件單

        Args:
            order: ConditionOrder 實例

        Returns:
            str — 條件單 ID
        """
        with self._lock:
            self._conditions.append(order)
            self._save_to_db(order)
            logger.info(
                f"條件單已新增: {order.id} "
                f"({order.symbol} {order.trigger_type} {order.trigger_price})"
            )
            return order.id

    def remove_condition_order(self, cond_id: str) -> bool:
        """
        移除條件單（標記為已取消）

        Args:
            cond_id: 條件單 ID

        Returns:
            bool — 是否移除成功
        """
        with self._lock:
            for cond in self._conditions:
                if cond.id == cond_id:
                    cond.status = "cancelled"
                    self._update_status_in_db(cond_id, "cancelled")
                    logger.info(f"條件單已移除: {cond_id}")
                    return True
            return False

    def list_condition_orders(
        self,
        status: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """
        列出所有條件單

        Args:
            status: 過濾條件單狀態（"active" | "triggered" | "cancelled"）
                    若為 None，回傳所有狀態

        Returns:
            List[dict] — 條件單列表
        """
        with self._lock:
            if status is None:
                return [c.to_dict() for c in self._conditions]
            return [c.to_dict() for c in self._conditions if c.status == status]

    # ══════════════════════════════════════════════════════════════
    # 觸發判斷邏輯
    # ══════════════════════════════════════════════════════════════

    def trigger_price_hit(
        self,
        quote: Dict[str, Any],
        trigger_price: float,
        trigger_type: str
    ) -> bool:
        """
        判斷報價是否觸及條件（價格類型）

        Args:
            quote:         即時報價 dict（需含 last_price）
            trigger_price: 條件觸發價格
            trigger_type:  觸發類型

        Returns:
            bool — 是否觸發
        """
        last_price = quote.get("last_price")
        if last_price is None:
            return False

        if trigger_type == "above":
            # 價格突破上限：當價格 >= 觸發價
            return float(last_price) >= trigger_price
        elif trigger_type == "below":
            # 價格突破下限：當價格 <= 觸發價
            return float(last_price) <= trigger_price
        elif trigger_type == "change_up":
            # 漲幅達標：需有昨收價或參考價
            ref_price = quote.get("close_price") or quote.get("settlement_price")
            if ref_price and ref_price > 0:
                change_ratio = (float(last_price) - float(ref_price)) / float(ref_price)
                return change_ratio >= 0.01  # 1% 漲幅
            return False
        elif trigger_type == "change_down":
            # 跌幅達標
            ref_price = quote.get("close_price") or quote.get("settlement_price")
            if ref_price and ref_price > 0:
                change_ratio = (float(last_price) - float(ref_price)) / float(ref_price)
                return change_ratio <= -0.01  # 1% 跌幅
            return False
        return False

    def trigger_quantity_hit(
        self,
        volume: int,
        trigger_volume: int
    ) -> bool:
        """
        判斷成交量是否觸及條件

        Args:
            volume:         目前成交量
            trigger_volume: 條件成交量

        Returns:
            bool — 是否觸發
        """
        return volume >= trigger_volume

    # ══════════════════════════════════════════════════════════════
    # 評估所有條件單
    # ══════════════════════════════════════════════════════════════

    def evaluate_all_conditions(
        self,
        quote_or_data: Dict[str, Any]
    ) -> List[Dict[str, Any]]:
        """
        評估所有未觸發條件單（由 quotes 推送呼叫）

        接收即時報價或包含 symbol/last_price/volume 的 dict，
        逐一檢查所有 status="active" 的條件單是否被觸發。

        Args:
            quote_or_data: 即時報價 dict，
                           需包含 symbol, last_price, volume（可選）

        Returns:
            List[dict] — 被觸發的條件單列表（可由 service.py 呼叫下單）
                         空列表表示無條件單被觸發
        """
        symbol = quote_or_data.get("symbol")
        last_price = quote_or_data.get("last_price")
        volume = quote_or_data.get("volume", 0)

        if not symbol:
            return []

        triggered_list = []

        with self._lock:
            for cond in self._conditions:
                if cond.status != "active":
                    continue
                if cond.symbol != symbol:
                    continue

                fired = False

                # 價格/漲跌觸發
                if cond.trigger_type in ("above", "below", "change_up", "change_down"):
                    fired = self.trigger_price_hit(
                        quote_or_data,
                        cond.trigger_price,
                        cond.trigger_type
                    )
                # 成交量觸發
                elif cond.trigger_type == "volume_above":
                    fired = self.trigger_quantity_hit(
                        volume,
                        int(cond.trigger_price)
                    )

                if fired:
                    cond.status = "triggered"
                    cond.triggered_at = datetime.now().isoformat()
                    self._update_status_in_db(cond.id, "triggered")
                    logger.info(
                        f"條件單觸發: {cond.id} "
                        f"({symbol} {cond.trigger_type} "
                        f"price={last_price} trigger={cond.trigger_price})"
                    )
                    triggered_list.append(cond.to_dict())

        return triggered_list

    # ══════════════════════════════════════════════════════════════
    # 資料庫輔助
    # ══════════════════════════════════════════════════════════════

    def _save_to_db(self, cond: ConditionOrder):
        """儲存條件單至 SQLite"""
        try:
            conn = sqlite3.connect(DB_PATH)
            cursor = conn.cursor()
            cursor.execute("""
                INSERT INTO condition_orders
                (id, symbol, trigger_price, trigger_type, quantity, bs,
                 order_price, created_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                cond.id, cond.symbol, cond.trigger_price, cond.trigger_type,
                cond.quantity, cond.bs, cond.order_price,
                cond.created_at, cond.status
            ))
            conn.commit()
            conn.close()
        except Exception as e:
            logger.error(f"_save_to_db error: {e}")

    def _update_status_in_db(self, cond_id: str, status: str):
        """更新條件單狀態"""
        try:
            conn = sqlite3.connect(DB_PATH)
            cursor = conn.cursor()
            if status == "triggered":
                cursor.execute("""
                    UPDATE condition_orders
                    SET status = ?, triggered_at = ?
                    WHERE id = ?
                """, (status, datetime.now().isoformat(), cond_id))
            else:
                cursor.execute("""
                    UPDATE condition_orders
                    SET status = ?
                    WHERE id = ?
                """, (status, cond_id))
            conn.commit()
            conn.close()
        except Exception as e:
            logger.error(f"_update_status_in_db error: {e}")

    def load_from_db(self):
        """自 SQLite 讀取所有條件單（啟動時呼叫）"""
        try:
            conn = sqlite3.connect(DB_PATH)
            cursor = conn.cursor()
            cursor.execute(
                "SELECT id, symbol, trigger_price, trigger_type, "
                "quantity, bs, order_price, created_at, status, "
                "triggered_at, order_no FROM condition_orders"
            )
            rows = cursor.fetchall()
            conn.close()

            with self._lock:
                existing_ids = {c.id for c in self._conditions}  # 去重檢查
                for row in rows:
                    cond_id = row[0]
                    if cond_id in existing_ids:
                        continue  # 跳過已存在的條件單
                    cond = ConditionOrder(
                        id=cond_id,
                        symbol=row[1],
                        trigger_price=row[2],
                        trigger_type=row[3],
                        quantity=row[4],
                        bs=row[5],
                        order_price=row[6],
                        created_at=row[7],
                        status=row[8],
                        triggered_at=row[9],
                        order_no=row[10],
                    )
                    self._conditions.append(cond)
                    existing_ids.add(cond_id)  # 加入已知 ID 集合
            logger.info(f"已自 DB 載入 {len(rows)} 筆條件單")
        except Exception as e:
            logger.error(f"load_from_db error: {e}")
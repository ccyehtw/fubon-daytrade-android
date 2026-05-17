# position_models.py — 當沖 / 期貨持倉模型
# 定義 EntryMode（進場模式）與 Position（持倉資料結構）

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Optional

# ══════════════════════════════════════════════════════════════
# Entry Mode — 雙模式進場定義
# ══════════════════════════════════════════════════════════════

class EntryMode(str, Enum):
    """
    進場模式（互補邏輯）

    【多方】追低點買入建倉 → 以追高點回檔平倉
    【空方】追高點回檔賣出建倉 → 以追低點回檔平倉
    """
    # 做多：價格由高拉回時買入，等漲回高點（或創新高）時平倉
    BREAKDOWN_BUY = "breakdown_buy"   # 追低點買入（Breakdown Buy）

    # 做空：價格由低反彈時賣出，等跌破低點時平倉
    # 對應：追高點回檔賣出
    BREAKOUT_SELL = "breakout_sell"    # 追高點回檔賣出（Breakout Sell）


class ProductType(str, Enum):
    """商品類型"""
    STOCK = "stock"      # 證券（股票）
    FUTURES = "futures"  # 期貨


# ══════════════════════════════════════════════════════════════
# Position — 持倉資料結構
# ══════════════════════════════════════════════════════════════

@dataclass
class Position:
    """
    當沖 / 期貨持倉資料結構

    核心概念：
    - 建倉後持續追蹤建倉後最高價（highest_since_entry）和最低價（lowest_since_entry）
    - 根據 entry_mode 自動計算平倉觸發價：
        - 多單（B-breakdown_buy）：現價 >= highest - N 檔 → 平多
        - 空單（B-breakout_sell）：現價 <= lowest + N 檔 → 平空
    - 停損價在進場時鎖定，不隨現價移動
    """

    # 基本資訊
    symbol: str                          # 商品代碼（2330 / TXF202506）
    product_type: ProductType           # 商品類型
    entry_mode: EntryMode               # 進場模式

    # 數量與成本
    quantity: int                        # 持有數量（股數 or 口數）
    entry_price: float                  # 建倉均價
    entry_cost: float                   # 建倉成本（已含手續費/滑價）

    # 時間戳
    entry_time: datetime               # 建倉時間

    # 移動追蹤（建倉後的價格邊界）
    highest_since_entry: float         # 建倉後最高價（用於多單平倉）
    lowest_since_entry: float          # 建倉後最低價（用於空單平倉）

    # 停損設定（進場時鎖定，不移動）
    stop_loss_price: float              # 停損價格（跌破此價自動平倉）
    stop_loss_pct: float                # 停損百分比（用於記錄）

    # 追蹤檔位（1~5 檔，用於計算回檔平倉觸發價）
    track_levels: int = 1              # 預設 1 檔

    # tick size（最小報價單位，用於計算平倉檔位）
    tick_size: float = 0.1             # 股票預設 0.1，期貨預設 1.0

    # 附加資訊
    account_id: str = ""                # 帳號
    order_no: Optional[str] = None      # 原始委託書號
    status: str = "open"                # open / closed
    closed_at: Optional[datetime] = None # 平倉時間
    realized_pnl: float = 0.0          # 已實現損益

    # ──────────────────────────────────────────────────────────
    # 平倉觸發價計算（核心）
    # ──────────────────────────────────────────────────────────

    def breakout_exit_trigger(self) -> float:
        """
        多單平倉觸發價（追高點回檔平倉）

        現價 >= 建倉後最高價 - (追蹤檔位 × tick) 時觸發
        適用於 breakdown_buy（追低點買入）的多單
        """
        return self.highest_since_entry - (self.track_levels * self.tick_size)

    def breakdown_exit_trigger(self) -> float:
        """
        空單平倉觸發價（追低點回檔平倉）

        現價 <= 建倉後最低價 + (追蹤檔位 × tick) 時觸發
        適用於 breakout_sell（追高點回檔放空）的空單
        """
        return self.lowest_since_entry + (self.track_levels * self.tick_size)

    def exit_trigger_price(self, current_price: float) -> float:
        """
        根據目前持倉方向，回傳平倉觸發價

        Returns:
            float — 平倉觸發價（若現價觸及此價則需平倉）
        """
        if self.entry_mode == EntryMode.BREAKDOWN_BUY:
            return self.breakout_exit_trigger()
        else:  # BREAKOUT_SELL
            return self.breakdown_exit_trigger()

    # ──────────────────────────────────────────────────────────
    # 停損判斷
    # ──────────────────────────────────────────────────────────

    def is_stop_loss_hit(self, current_price: float) -> bool:
        """
        判斷是否觸發停損

        適用於所有倉位：多單跌破停損價 或 空單突破停損價
        """
        if self.entry_mode == EntryMode.BREAKDOWN_BUY:
            return current_price <= self.stop_loss_price
        else:  # BREAKOUT_SELL
            return current_price >= self.stop_loss_price

    # ──────────────────────────────────────────────────────────
    # 平倉條件判斷
    # ──────────────────────────────────────────────────────────

    def should_close_long(self, current_price: float) -> bool:
        """
        判斷是否需要平多單（breakdown_buy）
        條件：現價 >= 平倉觸發價，且不停損
        """
        if self.entry_mode != EntryMode.BREAKDOWN_BUY:
            return False
        if self.is_stop_loss_hit(current_price):
            return True  # 停損優先
        return current_price >= self.breakout_exit_trigger()

    def should_close_short(self, current_price: float) -> bool:
        """
        判斷是否需要平空單（breakout_sell）
        條件：現價 <= 平倉觸發價，且不停損
        """
        if self.entry_mode != EntryMode.BREAKOUT_SELL:
            return False
        if self.is_stop_loss_hit(current_price):
            return True  # 停損優先
        return current_price <= self.breakdown_exit_trigger()

    def should_close(self, current_price: float) -> bool:
        """
        通用平倉判斷（根據倉位方向自動選擇）
        """
        if self.entry_mode == EntryMode.BREAKDOWN_BUY:
            return self.should_close_long(current_price)
        else:
            return self.should_close_short(current_price)

    # ──────────────────────────────────────────────────────────
    # 損益計算
    # ──────────────────────────────────────────────────────────

    def unrealized_pnl(self, current_price: float) -> float:
        """
        計算未實現損益

        多單（breakdown_buy）：(現價 - 成本) × 數量
        空單（breakout_sell）：(成本 - 現價) × 數量
        """
        if self.entry_mode == EntryMode.BREAKDOWN_BUY:
            return (current_price - self.entry_cost) * self.quantity
        else:
            return (self.entry_cost - current_price) * self.quantity

    def unrealized_pnl_pct(self, current_price: float) -> float:
        """未實現損益百分比"""
        if self.entry_cost == 0:
            return 0.0
        return (self.unrealized_pnl(current_price) / (self.entry_cost * self.quantity)) * 100

    # ──────────────────────────────────────────────────────────
    # 報價更新（持續追蹤價格邊界）
    # ──────────────────────────────────────────────────────────

    def update_price(self, current_price: float) -> None:
        """
        收到新報價時更新價格邊界（highest/lowest_since_entry）

        這個方法由行情監控服務每秒呼叫
        """
        if current_price > self.highest_since_entry:
            self.highest_since_entry = current_price
        if current_price < self.lowest_since_entry:
            self.lowest_since_entry = current_price

    # ──────────────────────────────────────────────────────────
    # 序列化
    # ──────────────────────────────────────────────────────────

    def to_dict(self) -> dict:
        """轉為 dict（供 API 回傳）"""
        return {
            "symbol": self.symbol,
            "product_type": self.product_type.value,
            "entry_mode": self.entry_mode.value,
            "quantity": self.quantity,
            "entry_price": round(self.entry_price, 2),
            "entry_cost": round(self.entry_cost, 2),
            "entry_time": self.entry_time.isoformat(),
            "highest_since_entry": round(self.highest_since_entry, 2),
            "lowest_since_entry": round(self.lowest_since_entry, 2),
            "stop_loss_price": round(self.stop_loss_price, 2),
            "stop_loss_pct": round(self.stop_loss_pct, 2),
            "track_levels": self.track_levels,
            "tick_size": self.tick_size,
            "account_id": self.account_id,
            "order_no": self.order_no,
            "status": self.status,
            "closed_at": self.closed_at.isoformat() if self.closed_at else None,
            "realized_pnl": round(self.realized_pnl, 2),
            # 動態計算
            "breakout_exit_trigger": round(self.breakout_exit_trigger(), 2),
            "breakdown_exit_trigger": round(self.breakdown_exit_trigger(), 2),
        }

    def __repr__(self) -> str:
        return (
            f"Position({self.symbol} {self.entry_mode.value} "
            f"x{self.quantity} @ {self.entry_cost:.2f} "
            f"H={self.highest_since_entry:.2f} L={self.lowest_since_entry:.2f})"
        )


# ══════════════════════════════════════════════════════════════
# 工廠函式
# ══════════════════════════════════════════════════════════════

def create_position(
    symbol: str,
    product_type: ProductType,
    entry_mode: EntryMode,
    quantity: int,
    entry_price: float,
    entry_time: Optional[datetime] = None,
    stop_loss_pct: float = 2.0,
    track_levels: int = 1,
    tick_size: float = 0.1,
    account_id: str = "",
    order_no: Optional[str] = None,
) -> Position:
    """
    建立新持倉的工廠函式

    Args:
        symbol:        商品代碼
        product_type:  STOCK 或 FUTURES
        entry_mode:    BREAKDOWN_BUY 或 BREAKOUT_SELL
        quantity:      數量（股數 or 口數）
        entry_price:   進場價格
        entry_time:    進場時間（預設現在）
        stop_loss_pct: 停損百分比（預設 2%）
        track_levels:  追蹤檔位（預設 1 檔）
        tick_size:     最小報價單位（股票預設 0.1，期貨預設 1.0）
        account_id:    帳號
        order_no:      委託書號

    Returns:
        Position — 新持倉物件
    """
    now = entry_time or datetime.now()

    # 計算建倉成本（含手續費攤銷）
    if product_type == ProductType.STOCK:
        if entry_mode == EntryMode.BREAKDOWN_BUY:
            # 多頭：成本 = 成交價 × 1.005（含手續費）
            entry_cost = entry_price * 1.005
        else:
            # 空頭：成本 = 成交價 × 0.995
            entry_cost = entry_price * 0.995
    else:
        # 期貨（每點 200 元台幣，以 1 口計算）
        multiplier = 200
        if entry_mode == EntryMode.BREAKDOWN_BUY:
            entry_cost = entry_price * 1.003
        else:
            entry_cost = entry_price * 0.997

    # 計算停損價
    if entry_mode == EntryMode.BREAKDOWN_BUY:
        stop_loss_price = entry_price * (1 - stop_loss_pct / 100)
    else:
        stop_loss_price = entry_price * (1 + stop_loss_pct / 100)

    return Position(
        symbol=symbol,
        product_type=product_type,
        entry_mode=entry_mode,
        quantity=quantity,
        entry_price=entry_price,
        entry_cost=entry_cost,
        entry_time=now,
        highest_since_entry=entry_price,
        lowest_since_entry=entry_price,
        stop_loss_price=stop_loss_price,
        stop_loss_pct=stop_loss_pct,
        track_levels=track_levels,
        tick_size=tick_size,
        account_id=account_id,
        order_no=order_no,
    )
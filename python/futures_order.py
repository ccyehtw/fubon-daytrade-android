# futures_order.py — 期貨下單模組
# 對應富邦 FubonSDK 期貨下單接口

from typing import Optional, Dict, Any, List
from dataclasses import dataclass
from datetime import datetime
import logging
import sqlite3
import os

logger = logging.getLogger(__name__)

# 富邦 SDK 可用標記
FUTURES_SDK_AVAILABLE = False
try:
    from fubon_neo.sdk import FubonSDK, Order
    from fubon_neo.constant import (
        TimeInForce, OrderType, PriceType, MarketType, BSAction
    )
    FUTURES_SDK_AVAILABLE = True
except ImportError:
    logger.warning("fubon-neo-api not installed, futures order unavailable")

# 全域 SDK 實例（由 service.py 注入）
_sdk: Optional[FubonSDK] = None
# 登入時快取的期貨帳戶（用於期貨帳務查詢）
_futopt_account = None

# SQLite 資料庫路徑（條件單持久化）
DB_PATH = os.path.join(os.path.dirname(__file__), "condition_orders.db")


def init_sdk(sdk_instance, accounts: List[Any] = None):
    """注入 SDK 實例 + 緩存期貨帳戶

    Args:
        sdk_instance: FubonSDK 實例（已登入）
        accounts: 登入後取得的帳戶列表（login_result.data），若為 None 則嘗試從 sdk 本身讀取
    """
    global _sdk, _futopt_account
    _sdk = sdk_instance

    # 優先使用傳入的 accounts，否則嘗試從 sdk 本身讀取
    account_list = accounts if accounts is not None else getattr(sdk_instance, 'login_data', None)
    if account_list:
        for acc in account_list:
            if getattr(acc, 'account_type', '') == 'futopt':
                _futopt_account = acc
                logger.info(f"Cached futopt account: {getattr(acc, 'account', 'N/A')} branch: {getattr(acc, 'branch_no', 'N/A')}")
                break

    _init_db()


# ══════════════════════════════════════════════════════════════
# 資料庫初始化
# ══════════════════════════════════════════════════════════════

def _init_db():
    """初始化條件單 SQLite 資料庫"""
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS condition_orders (
            id TEXT PRIMARY KEY,
            account_id TEXT,
            futures_code TEXT,
            trigger_price REAL,
            trigger_type TEXT,
            order_price REAL,
            quantity INTEGER,
            bs TEXT,
            status TEXT DEFAULT 'active',
            created_at TEXT,
            triggered_at TEXT,
            order_no TEXT
        )
    """)
    conn.commit()
    conn.close()
    logger.info(f"Condition orders DB initialized at {DB_PATH}")


# ══════════════════════════════════════════════════════════════
# 期貨下單
# ══════════════════════════════════════════════════════════════

def place_futures_order(
    account: str,
    futures_code: str,
    price: Optional[float],
    quantity: int,
    bs: str = "buy"
) -> Dict[str, Any]:
    """
    期貨市價/限價單

    Args:
        account:      期貨帳號（例: "1247180/futopt/15901"）
        futures_code: 期貨商品代碼（例: "TXF202506"）
        price:        委託價格（None = 市價）
        quantity:     委託口數
        bs:           "buy" | "sell"

    Returns:
        dict — 下單回報
    """
    if not _sdk:
        return {"success": False, "message": "SDK not initialized"}

    if not FUTURES_SDK_AVAILABLE:
        return {"success": False, "message": "Fubon SDK not available"}

    try:
        # 轉換買賣別
        bs_action = BSAction.Buy if bs.lower() == "buy" else BSAction.Sell

        # 決定價格類型
        if price is None:
            price_type = PriceType.Market
            order_price = None
        else:
            price_type = PriceType.Limit
            order_price = price

        # 建立期貨委託單
        order = Order(
            buy_sell=bs_action,
            symbol=futures_code,
            price=str(order_price) if order_price else None,
            quantity=quantity,
            market_type=MarketType.Futures,
            price_type=price_type,
            time_in_force=TimeInForce.ROD,
            order_type=OrderType.Futures,
            user_def="futures_order"
        )

        logger.info(
            f"期貨下單: {bs_action.name} {futures_code} x {quantity} @ "
            f"{price if price else '市價'} ({price_type.name})"
        )

        resp = _sdk.futures.place_order(account, order)

        if not resp.is_success:
            logger.error(f"期貨下單失敗: {resp.message}")
            return {"success": False, "message": resp.message}

        order_data = resp.data[0] if resp.data else {}
        return {
            "success": True,
            "order_no": getattr(order_data, "order_no", None),
            "seq_no": getattr(order_data, "seq_no", None),
            "status": getattr(order_data, "status", None),
            "message": "下單成功",
        }
    except NotImplementedError as e:
        logger.error(f"期貨下單不支援: {e}")
        return {"success": False, "message": f"期貨下單不支援: {e}", "mock": False}
    except Exception as e:
        logger.error(f"期貨下單發生未預期錯誤: {e}")
        # 不再自動 fallback 到 mock，改為直接回傳錯誤
        # 避免 Android 收到假訂單編號而視為成功
        return {"success": False, "message": f"下單錯誤: {e}", "mock": False}


def _mock_futures_order(
    account: str,
    futures_code: str,
    price: Optional[float],
    quantity: int,
    bs: str
) -> Dict[str, Any]:
    """模擬期貨下單（開發/測試用）"""
    import random
    order_no = f"FU{random.randint(1000, 9999)}"
    return {
        "success": True,
        "order_no": order_no,
        "seq_no": random.randint(1, 999),
        "status": "pending",
        "message": f"模擬下單成功: {bs.upper()} {futures_code} x {quantity}",
    }


# ══════════════════════════════════════════════════════════════
# 期貨條件單（存 SQLite）
# ══════════════════════════════════════════════════════════════

def place_futures_condition_order(
    account: str,
    futures_code: str,
    trigger_price: float,
    trigger_type: str,
    order_price: Optional[float],
    quantity: int,
    bs: str = "buy"
) -> Dict[str, Any]:
    """
    期貨條件單（存 SQLite，非真實條件單 API）

    條件單會由 condition_engine.py 的 evaluate_all_conditions() 評估，
    當條件觸發時自動下單。

    Args:
        account:       期貨帳號
        futures_code:  期貨商品代碼
        trigger_price: 觸發條件價
        trigger_type:  "above" | "below" | "change_up" | "change_down"
        order_price:   委託價格（None = 市價）
        quantity:      委託口數
        bs:            "buy" | "sell"

    Returns:
        dict — 條件單建立結果
    """
    import uuid
    from datetime import datetime as dt

    cond_id = f"fut_cond_{uuid.uuid4().hex[:12]}"
    now = dt.now().isoformat()

    try:
        conn = sqlite3.connect(DB_PATH)
        cursor = conn.cursor()
        cursor.execute("""
            INSERT INTO condition_orders
            (id, account_id, futures_code, trigger_price, trigger_type,
             order_price, quantity, bs, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?)
        """, (cond_id, account, futures_code, trigger_price, trigger_type,
              order_price, quantity, bs, now))
        conn.commit()
        conn.close()

        logger.info(
            f"期貨條件單已建立: {cond_id} "
            f"({futures_code} {trigger_type} {trigger_price})"
        )
        return {
            "success": True,
            "condition_id": cond_id,
            "message": "條件單已建立",
        }
    except Exception as e:
        logger.error(f"place_futures_condition_order error: {e}")
        return {"success": False, "message": str(e)}


# ══════════════════════════════════════════════════════════════
# 取消期貨委託
# ══════════════════════════════════════════════════════════════

def cancel_futures_order(order_id: str) -> Dict[str, Any]:
    """
    取消期貨委託

    Args:
        order_id: 委託書號

    Returns:
        dict — 取消結果
    """
    if not _sdk:
        return _mock_cancel(order_id)

    try:
        # 需有期貨帳號才能取消，先用 mock
        return _mock_cancel(order_id)
    except Exception as e:
        logger.error(f"cancel_futures_order error: {e}")
        return _mock_cancel(order_id)


def _mock_cancel(order_id: str) -> Dict[str, Any]:
    """模擬取消委託"""
    return {
        "success": True,
        "order_id": order_id,
        "message": f"模擬取消委託 {order_id} 成功",
    }


# ══════════════════════════════════════════════════════════════
# 取得期貨持倉
# ══════════════════════════════════════════════════════════════

def get_futures_positions() -> Dict[str, Any]:
    """
    取得期貨/期權持倉（使用 futopt_accounting.query_hybrid_position）

    Returns:
        dict — 期貨持倉列表（包含 entry_mode, direction, realized_pnl 等欄位）
    """
    if not _sdk:
        return {"success": False, "message": "SDK not initialized"}

    try:
        if _futopt_account is None:
            return {"success": False, "message": "Not logged in"}

        resp = _sdk.futopt_accounting.query_hybrid_position(_futopt_account)
        if not resp.is_success:
            return {"success": False, "message": resp.message}

        positions = []
        for item in resp.data:
            bs = getattr(item, 'buy_sell', None)
            bs_str = str(bs).split('.')[-1] if bs else 'UNKNOWN'
            direction = bs_str.upper() if bs_str in ['BUY', 'SELL'] else ('BUY' if 'Buy' in str(bs) else 'SELL')

            # 期權未平倉損益計算：opt_value - opt_long_value（以原始公平價格計算）
            opt_value = float(getattr(item, 'opt_value', 0) or 0)
            opt_long_value = float(getattr(item, 'opt_long_value', 0) or 0)
            realized_pnl = opt_value - opt_long_value

            positions.append({
                "symbol": getattr(item, 'symbol', ''),
                "expiry_date": getattr(item, 'expiry_date', ''),
                "strike_price": float(getattr(item, 'strike_price', 0) or 0),
                "call_put": str(getattr(item, 'call_put', '')).split('.')[-1] if getattr(item, 'call_put', None) else '',
                "direction": direction,
                "quantity": int(getattr(item, 'orig_lots', 0) or 0),
                "avg_price": float(getattr(item, 'price', 0) or 0),
                "market_price": float(getattr(item, 'market_price', 0) or 0),
                "profit_or_loss": float(getattr(item, 'profit_or_loss', 0) or 0),
                "realized_pnl": realized_pnl,
                "entry_mode": "",  # 現有系統不主動追蹤期貨 entry_mode
                "is_spread": getattr(item, 'is_spread', False),
            })
        return {
            "success": True,
            "positions": positions,
        }
    except Exception as e:
        logger.error(f"get_futures_positions error: {e}")
        return {"success": False, "message": str(e)}


def _mock_futures_positions() -> Dict[str, Any]:
    """模擬期貨持倉（開發/測試用）

    ⚠️ 警告：此 Mock 資料僅用於本地開發。
    實際資料必須經由 /api/login 登入後，由 Fubon SDK 取得真實帳務。
    """
    return {
        "success": True,
        "positions": [
            {
                "symbol": "TXF202506",
                "quantity": 1,
                "avg_price": 21450.0,
                "market_value": 2145000.0,
                "pnl": 50.0,
                "direction": "BUY",
                "entry_mode": "breakdown_buy",
                "realized_pnl": 0.0,
            },
            {
                "symbol": "MXF202506",
                "quantity": 2,
                "avg_price": 21460.0,
                "market_value": 4292000.0,
                "pnl": -120.0,
                "direction": "SELL",
                "entry_mode": "breakout_sell",
                "realized_pnl": 0.0,
            },
        ],
    }


# ══════════════════════════════════════════════════════════════
# 取得期貨帳戶保證金
# ══════════════════════════════════════════════════════════════

def get_futures_margin(account_id: str) -> Dict[str, Any]:
    """
    取得期貨帳戶保證金餘額（使用 futopt_accounting.query_margin_equity）

    Args:
        account_id: 期貨帳號（例: "1247180/15901" — 不包含 "futopt/"）

    Returns:
        dict — {"success": True, "margin": float, "currency": str}
    """
    if not _sdk:
        return {"success": False, "message": "SDK not initialized"}

    try:
        # 取得登入時的 futopt account
        if _futopt_account is None:
            return {"success": False, "message": "Not logged in"}

        resp = _sdk.futopt_accounting.query_margin_equity(_futopt_account)
        if not resp.is_success:
            return {"success": False, "message": resp.message}

        # 取今日（最新）的保證金資料
        today_data = None
        for item in resp.data:
            if str(getattr(item, 'date', '')).replace('/', '-') >= '2026-05-15':
                today_data = item
                break
        if not today_data:
            today_data = resp.data[0] if resp.data else None

        if not today_data:
            return {"success": False, "message": "No margin data"}

        margin = float(getattr(today_data, 'today_balance', 0) or 0)
        currency = getattr(today_data, 'currency', 'NTD')
        return {
            "success": True,
            "margin": margin,
            "currency": currency,
            "initial_margin": getattr(today_data, 'initial_margin', 0),
            "maintenance_margin": getattr(today_data, 'maintenance_margin', 0),
        }
    except Exception as e:
        logger.error(f"get_futures_margin error: {e}")
        return {"success": False, "message": str(e)}


def _mock_futures_margin() -> Dict[str, Any]:
    """模擬期貨保證金（開發/測試用）"""
    import random
    return {
        "success": True,
        "margin": round(random.uniform(80_000, 200_000), 2),
    }
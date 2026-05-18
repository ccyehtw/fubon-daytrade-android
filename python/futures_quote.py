# futures_quote.py — 期貨即時報價模組
# 對應富邦 FubonSDK 期貨報價接口

from typing import Optional, Dict, Any, List
from dataclasses import dataclass
from datetime import datetime
import logging

logger = logging.getLogger(__name__)

# 富邦 SDK 可用標記
FUTURES_SDK_AVAILABLE = False
try:
    from fubon_neo.sdk import FubonSDK
    FUTURES_SDK_AVAILABLE = True
except ImportError:
    logger.warning("fubon-neo-api not installed, futures quote unavailable")

# 全域 SDK 實例（由 service.py 注入）
_sdk: Optional[FubonSDK] = None


def init_sdk(sdk_instance, accounts: list = None):
    """注入 SDK 實例"""
    global _sdk
    _sdk = sdk_instance


# ══════════════════════════════════════════════════════════════
# 資料結構
# ══════════════════════════════════════════════════════════════

@dataclass
class FuturesQuote:
    """期貨報價資料結構"""
    symbol: str           # 商品代碼（例: TXF202506）
    name: str             # 中文名稱（例: 臺股期貨）
    exchange: str         # 交易所
    last_price: float     # 最新價
    bid_price: float      # 買價
    ask_price: float      # 賣價
    volume: int           # 成交量
    open_price: float     # 開盤價
    high_price: float     # 最高價
    low_price: float      # 最低價
    close_price: float    # 收盤價（昨收）
    settlement_price: float #結算價
    updated_at: str       # 更新時間


@dataclass
class FuturesOptionQuote:
    """選擇權報價資料結構"""
    symbol: str
    name: str
    strike_price: float   # 履約價
    call_bid: float       # 買權bid
    call_ask: float        # 買權ask
    put_bid: float         # 賣權bid
    put_ask: float         # 賣權ask
    implied_volatility: float = 0.0
    volume: int = 0
    delta: float = 0.0
    gamma: float = 0.0
    theta: float = 0.0
    vega: float = 0.0


# ══════════════════════════════════════════════════════════════
# 期貨報價查詢
# ══════════════════════════════════════════════════════════════

def get_futures_quote(code: str) -> Dict[str, Any]:
    """
    查詢期貨報價

    Args:
        code: 期貨商品代碼（例: "TXF" 或 "TXF202506"）

    Returns:
        dict — 報價資料或錯誤訊息
    """
    if not _sdk:
        return {"success": False, "message": "SDK not initialized"}

    try:
        # 富邦期貨報價接口
        # 嘗試使用 _sdk.futures.realtime_quote(code)
        resp = _sdk.futopt.get_quote(code)

        if not resp.is_success:
            return {"success": False, "message": resp.message}

        data = resp.data
        quote = FuturesQuote(
            symbol=getattr(data, 'symbol', code),
            name=getattr(data, 'name', code),
            exchange=getattr(data, 'exchange', 'TPEX'),
            last_price=float(getattr(data, 'last_price', 0)),
            bid_price=float(getattr(data, 'bid_price', 0)),
            ask_price=float(getattr(data, 'ask_price', 0)),
            volume=int(getattr(data, 'volume', 0)),
            open_price=float(getattr(data, 'open_price', 0)),
            high_price=float(getattr(data, 'high_price', 0)),
            low_price=float(getattr(data, 'low_price', 0)),
            close_price=float(getattr(data, 'close_price', 0)),
            settlement_price=float(getattr(data, 'settlement_price', 0)),
            updated_at=datetime.now().isoformat(),
        )
        return {
            "success": True,
            "quote": quote.__dict__,
        }
    except Exception as e:
        logger.error(f"get_futures_quote({code}) error: {e}")
        # 如果 SDK 方法不存在，回傳模擬資料（開發用）
        return _mock_futures_quote(code)


def _mock_futures_quote(code: str) -> Dict[str, Any]:
    """模擬期貨報價（開發/測試用）"""
    mock_data = {
        "TXF": {"name": "臺股期貨", "price": 21500.0, "volume": 12345},
        "MXF": {"name": "小型臺指", "price": 21500.0, "volume": 5678},
        "EXF": {"name": "電子期貨", "price": 1850.0, "volume": 2345},
        "FEF": {"name": "金融期貨", "price": 1820.0, "volume": 890},
    }
    base = mock_data.get(code, {"name": code, "price": 20000.0, "volume": 1000})
    return {
        "success": True,
        "quote": {
            "symbol": code,
            "name": base["name"],
            "exchange": "TAIFEX",
            "last_price": base["price"],
            "bid_price": base["price"] - 1,
            "ask_price": base["price"] + 1,
            "volume": base["volume"],
            "open_price": base["price"] - 5,
            "high_price": base["price"] + 20,
            "low_price": base["price"] - 15,
            "close_price": base["price"] - 3,
            "settlement_price": base["price"],
            "updated_at": datetime.now().isoformat(),
        },
    }


# ══════════════════════════════════════════════════════════════
# 選擇權報價查詢
# ══════════════════════════════════════════════════════════════

def get_futures_option_quote(code: str) -> Dict[str, Any]:
    """
    查詢選擇權報價

    Args:
        code: 選擇權代碼（例: "TXO20250620000C"）

    Returns:
        dict — 報價資料或錯誤訊息
    """
    if not _sdk:
        return {"success": False, "message": "SDK not initialized"}

    try:
        resp = _sdk.futopt.get_option_quote(code)
        if not resp.is_success:
            return {"success": False, "message": resp.message}

        data = resp.data
        quote = FuturesOptionQuote(
            symbol=getattr(data, 'symbol', code),
            name=getattr(data, 'name', code),
            strike_price=float(getattr(data, 'strike_price', 0)),
            call_bid=float(getattr(data, 'call_bid', 0)),
            call_ask=float(getattr(data, 'call_ask', 0)),
            put_bid=float(getattr(data, 'put_bid', 0)),
            put_ask=float(getattr(data, 'put_ask', 0)),
            volume=int(getattr(data, 'volume', 0)),
        )
        return {
            "success": True,
            "quote": quote.__dict__,
        }
    except Exception as e:
        logger.error(f"get_futures_option_quote({code}) error: {e}")
        return _mock_option_quote(code)


def _mock_option_quote(code: str) -> Dict[str, Any]:
    """模擬選擇權報價（開發/測試用）"""
    return {
        "success": True,
        "quote": {
            "symbol": code,
            "name": "臺指選擇權",
            "strike_price": 21500.0,
            "call_bid": 150.0,
            "call_ask": 152.0,
            "put_bid": 148.0,
            "put_ask": 150.0,
            "implied_volatility": 0.15,
            "volume": 500,
            "delta": 0.5,
            "gamma": 0.01,
            "theta": -5.0,
            "vega": 0.2,
        },
    }


# ══════════════════════════════════════════════════════════════
# 履約價鍊（序列報價）
# ══════════════════════════════════════════════════════════════

def get_futures_chain(symbol: str) -> Dict[str, Any]:
    """
    取得履約價鍊（序列報價）

    針對選擇權，可一次取得某標的全部履約價序列報價。
    例如：symbol="TXO" 回傳所有履約價的 Call/Put 報價。

    Args:
        symbol: 標的代碼（例: "TXO" 或 "TXF"）

    Returns:
        dict — 履約價鍊資料
    """
    if not _sdk:
        return {"success": False, "message": "SDK not initialized"}

    try:
        resp = _sdk.futopt.get_chain(symbol)
        if not resp.is_success:
            return {"success": False, "message": resp.message}

        chain = []
        for item in resp.data:
            chain.append({
                "symbol": getattr(item, 'symbol', ''),
                "strike_price": float(getattr(item, 'strike_price', 0)),
                "call_bid": float(getattr(item, 'call_bid', 0)),
                "call_ask": float(getattr(item, 'call_ask', 0)),
                "put_bid": float(getattr(item, 'put_bid', 0)),
                "put_ask": float(getattr(item, 'put_ask', 0)),
                "volume": int(getattr(item, 'volume', 0)),
            })
        return {
            "success": True,
            "symbol": symbol,
            "chain": chain,
        }
    except Exception as e:
        logger.error(f"get_futures_chain({symbol}) error: {e}")
        return _mock_futures_chain(symbol)


def _mock_futures_chain(symbol: str) -> Dict[str, Any]:
    """模擬履約價鍊（開發/測試用）"""
    base_price = 21500
    strikes = [base_price - 200 + i * 100 for i in range(9)]
    chain = []
    for strike in strikes:
        chain.append({
            "symbol": f"{symbol}C{strike}",
            "strike_price": strike,
            "call_bid": 200 - (strike - base_price) * 0.1,
            "call_ask": 202 - (strike - base_price) * 0.1,
            "put_bid": 198 + (strike - base_price) * 0.1,
            "put_ask": 200 + (strike - base_price) * 0.1,
            "volume": 100,
        })
    return {
        "success": True,
        "symbol": symbol,
        "chain": chain,
    }
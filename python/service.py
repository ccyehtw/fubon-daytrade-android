#!/usr/bin/env python3
"""
富邦證券 - Python Service (Phase 2)
新增期貨報價/下單 + 條件單引擎
"""

import asyncio
import json
import logging
import os
from dataclasses import dataclass, asdict
from typing import Optional, List, Dict, Any

from fastapi import FastAPI, HTTPException, Request, WebSocket, Header, Depends
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

# 富邦 SDK
try:
    from fubon_neo.sdk import FubonSDK
    from fubon_neo.constant import OrderType, PriceType, TimeInForce, BSAction, MarketType
    FUBON_SDK_AVAILABLE = True
except ImportError:
    FUBON_SDK_AVAILABLE = False
    print("Warning: fubon-neo-api not installed")

# Logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# FastAPI App
app = FastAPI(title="Fubon DayTrade Service", version="1.1.0")

# CORS 設定：僅允許已知 Android App origins（防止惡意網站盜用認證）
# 生產環境應設為 app 的實際 origin，開發環境可用 ["http://localhost:*"]
# Fix #8: 過濾空白字串，確保沒有空字串被允許
_origins_raw = os.environ.get("ALLOWED_ORIGINS", "http://localhost:*,http://10.0.2.2:*,http://127.0.0.1:*")
ALLOWED_ORIGINS = [o.strip() for o in _origins_raw.split(",") if o.strip()]
if not ALLOWED_ORIGINS:
    ALLOWED_ORIGINS = ["http://localhost:*"]  # 安全默认值

app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=False,  # False because we use static API Key auth, not Cookie-based
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
)

# Global SDK instance
sdk: Optional[FubonSDK] = None
ws_manager: Optional[Any] = None  # WebSocketManager singleton
accounts_cache: List[Dict[str, str]] = []
_stock_account = None  # 證券帳戶（登入時快取）

# ──────────────────────────────────────────────────────────────
# API Key 驗證（Fix #1）
# ──────────────────────────────────────────────────────────────
_ALLOWED_API_KEYS: set[str] = set()
_BACKUP_API_KEY: Optional[str] = None


def _load_api_keys():
    global _ALLOWED_API_KEYS, _BACKUP_API_KEY
    env_keys = os.environ.get("ALLOWED_API_KEYS", "")
    if env_keys:
        _ALLOWED_API_KEYS = {k.strip() for k in env_keys.split(",") if k.strip()}
    _BACKUP_API_KEY = os.environ.get("BACKUP_API_KEY") or None


def verify_api_key(x_api_key: Optional[str] = Header(None)) -> str:
    """驗證 API Key，開發模式無 key 時直接通過"""
    if not _ALLOWED_API_KEYS and not _BACKUP_API_KEY:
        _load_api_keys()
    if not _ALLOWED_API_KEYS and not _BACKUP_API_KEY:
        return "dev-mode"
    if not x_api_key:
        raise HTTPException(status_code=401, detail="Missing X-API-Key header")
    if x_api_key in _ALLOWED_API_KEYS or x_api_key == _BACKUP_API_KEY:
        return x_api_key
    raise HTTPException(status_code=403, detail="Invalid API Key")

# 條件單引擎（單例）
condition_engine = None

# 當日沖銷服務（單例）
daytrade_service = None


def get_daytrade_service():
    """延遲初始化 DayTradeService（單例）"""
    global daytrade_service
    if daytrade_service is None:
        from daytrade_service import DayTradeService
        daytrade_service = DayTradeService()
    return daytrade_service


def get_ws_manager():
    """延遲初始化 WebSocketManager（單例）"""
    global ws_manager
    if ws_manager is None:
        from ws_manager import WebSocketManager
        ws_manager = WebSocketManager()
    return ws_manager


def get_condition_engine():
    """延遲初始化條件單引擎"""
    global condition_engine
    if condition_engine is None:
        from condition_engine import ConditionEngine
        condition_engine = ConditionEngine()
        condition_engine.load_from_db()
    return condition_engine


def get_quotes_broadcast_service():
    """延遲初始化 QuotesBroadcastService（單例）"""
    from quotes_broadcast_service import QuotesBroadcastService
    qbs = QuotesBroadcastService()
    qbs.set_ws_manager(get_ws_manager())
    if sdk:
        qbs.set_sdk(sdk)
    return qbs


# ========== Data Models ==========

@dataclass
class AccountInfo:
    account_id: str
    account_type: str  # "stock" or "futopt"
    display_name: str


class LoginRequest(BaseModel):
    personal_id: str
    api_key: str
    cert_path: str
    cert_password: Optional[str] = None


class LoginResponse:
    def __init__(self, success: bool, accounts: List[Dict] = None, message: str = None):
        self.success = success
        self.accounts = accounts or []
        self.message = message

    def to_dict(self):
        return {
            "success": self.success,
            "accounts": self.accounts,
            "message": self.message
        }


# ========== 期貨報價 Request/Response Models ==========

class FuturesQuoteRequest(BaseModel):
    code: str  # 期貨代碼（例: TXF 或 TXF202506）


class FuturesOptionQuoteRequest(BaseModel):
    code: str  # 選擇權代碼


class FuturesChainRequest(BaseModel):
    symbol: str  # 標的代碼（例: TXO）


# ========== 當日沖銷 Request/Response Models ==========

class StockEntryRequest(BaseModel):
    symbol: str
    entry_mode: str = "breakdown_buy"   # "breakdown_buy" | "breakout_sell"
    price: float
    quantity: int
    stop_loss_pct: float = 2.0
    track_levels: int = 1
    product_type: str = "stock"
    account_id: str = ""
    tick_size: float = 0.1


class StockExitRequest(BaseModel):
    symbol: str
    account_id: str = ""   # 帳號驗證（防止他人平倉）
    reason: str = "manual"  # "stop_loss" | "breakout_exit" | "breakdown_exit" | "manual"


class StockPriceUpdateRequest(BaseModel):
    symbol: str
    current_price: float


class StockPnlRequest(BaseModel):
    prices: Dict[str, float]  # symbol → current_price


# ========== 期貨下單 Request/Response Models ==========

class FuturesOrderRequest(BaseModel):
    # 支援 account (舊) 和 account_id (新) 兩種命名
    account: Optional[str] = None
    account_id: Optional[str] = None
    futures_code: Optional[str] = None  # 舊命名（向後相容）
    symbol: Optional[str] = None        # 新命名（通用代碼）
    price: Optional[float] = None       # 委託價格（None = 市價）
    quantity: int = 1
    bs: Optional[str] = None           # 舊版期貨下單（"buy" | "sell"）
    # 雙模式參數（可選）
    entry_mode: Optional[str] = None   # "breakdown_buy" | "breakout_sell"
    stop_loss_pct: Optional[float] = None
    track_levels: Optional[int] = None
    tick_size: Optional[float] = None   # 最小報價單位


class FuturesConditionOrderRequest(BaseModel):
    account: str
    futures_code: str
    trigger_price: float
    trigger_type: str     # "above" | "below" | "change_up" | "change_down"
    order_price: Optional[float] = None
    quantity: int
    bs: str = "buy"


class CancelOrderRequest(BaseModel):
    order_id: str


# ========== 條件單 Request/Response Models ==========

class ConditionOrderRequest(BaseModel):
    symbol: str
    trigger_price: float
    trigger_type: str   # "above" | "below" | "change_up" | "change_down" | "volume_above"
    quantity: int
    bs: str = "buy"     # "buy" | "sell"
    order_price: Optional[float] = None


class ConditionEvaluateRequest(BaseModel):
    symbol: str
    last_price: float
    volume: int = 0
    close_price: Optional[float] = None
    settlement_price: Optional[float] = None


# ========== SDK Login Framework ==========

async def sdk_login(personal_id: str, api_key: str, cert_path: str, cert_password: Optional[str] = None) -> LoginResponse:
    """
    使用 FubonSDK.apikey_login() 登入
    """
    global sdk, accounts_cache

    if not FUBON_SDK_AVAILABLE:
        return LoginResponse(
            success=False,
            message="Fubon SDK not available. Install: pip install fubon-neo-api"
        )

    try:
        sdk = FubonSDK()
        password = cert_password if cert_password else personal_id
        # 登入時不輸出敏感資料，僅記錄「嘗試登入」事件
        masked_pid = personal_id[0] + "***" + personal_id[-2:] if len(personal_id) > 4 else "***"
        logger.info(f"嘗試登入: personal_id={masked_pid}, api_key=***, cert_path={cert_path}")
        result = sdk.apikey_login(personal_id, api_key, cert_path, password)
        logger.info(f"Login result: {result}")

        if not result.is_success:
            return LoginResponse(
                success=False,
                message=result.message or "登入失敗"
            )

        accounts = []
        if hasattr(result, 'data') and result.data:
            for acc in result.data:
                acc_type = getattr(acc, 'account_type', 'unknown')
                if acc_type == "futopt":
                    display_name = f"期貨 {getattr(acc, 'account', 'N/A')}"
                elif acc_type == "stock":
                    display_name = f"證券 {getattr(acc, 'account', 'N/A')}"
                else:
                    display_name = f"{acc_type} {getattr(acc, 'account', 'N/A')}"

                accounts.append({
                    "account_id": getattr(acc, 'account', ''),
                    "account_type": acc_type,
                    "display_name": display_name
                })

        accounts_cache = accounts

        # 初始化其他模組的 SDK
        try:
            from futures_quote import init_sdk as fq_init
            fq_init(sdk, result.data)
        except Exception as e:
            logger.warning(f"futures_quote init error: {e}")
        try:
            from futures_order import init_sdk as fo_init
            fo_init(sdk, result.data)
        except Exception as e:
            logger.warning(f"futures_order init error: {e}")

        # 快取證券帳戶（供 /stock/positions 使用）
        global _stock_account
        _stock_account = None
        for acc in result.data:
            if getattr(acc, 'account_type', '') == 'stock':
                _stock_account = acc
                logger.info(f"Cached stock account: {getattr(acc, 'account', 'N/A')} branch: {getattr(acc, 'branch_no', 'N/A')}")
                break

        return LoginResponse(
            success=True,
            accounts=accounts,
            message="登入成功"
        )

    except Exception as e:
        logger.error(f"Login exception: {e}")
        return LoginResponse(
            success=False,
            message=str(e)
        )


# ========== API Routes ==========

@app.get("/")
async def root():
    return {"service": "Fubon DayTrade Service", "status": "running", "version": "1.1.0", "sdk_available": FUBON_SDK_AVAILABLE}


@app.get("/health")
async def health():
    return {"status": "healthy", "sdk_ready": sdk is not None}


@app.post("/api/login")
async def login(req: LoginRequest):
    """Login endpoint - calls FubonSDK.apikey_login()"""
    result = await sdk_login(
        personal_id=req.personal_id,
        api_key=req.api_key,
        cert_path=req.cert_path,
        cert_password=req.cert_password
    )
    return result.to_dict()


@app.get("/api/accounts")
async def get_accounts():
    """Get cached accounts"""
    return {"accounts": accounts_cache}


# ══════════════════════════════════════════════════════════════
# 當日沖銷端點（雙模式建倉/平倉）
# ══════════════════════════════════════════════════════════════

@app.post("/stock/entry", dependencies=[Depends(verify_api_key)])
async def stock_entry(req: StockEntryRequest):
    """
    股票/期貨建倉（支援雙模式）

    POST /stock/entry
    Body: {"symbol": "2330", "entry_mode": "breakdown_buy",
           "price": 605.0, "quantity": 2000, "stop_loss_pct": 2.0,
           "track_levels": 1, "account_id": "961P/20125", "tick_size": 0.1}
    """
    try:
        svc = get_daytrade_service()
        result = svc.entry(
            symbol=req.symbol,
            entry_mode=req.entry_mode,
            price=req.price,
            quantity=req.quantity,
            stop_loss_pct=req.stop_loss_pct,
            track_levels=req.track_levels,
            product_type=req.product_type,
            account_id=req.account_id,
            tick_size=req.tick_size,
        )
        return result
    except Exception as e:
        logger.error(f"/stock/entry error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/stock/exit", dependencies=[Depends(verify_api_key)])
async def stock_exit(req: StockExitRequest):
    """
    股票/期貨平倉

    POST /stock/exit
    Body: {"symbol": "2330", "reason": "breakout_exit"}
    """
    try:
        svc = get_daytrade_service()
        result = svc.close_position(req.symbol, reason=req.reason, account_id=req.account_id)
        return result
    except Exception as e:
        logger.error(f"/stock/exit error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/stock/position/{symbol}")
async def stock_position(symbol: str):
    """
    查詢特定商品持倉狀態

    GET /stock/position/2330
    """
    try:
        svc = get_daytrade_service()
        result = svc.get_position(symbol)
        if result is None:
            raise HTTPException(status_code=404, detail=f"找不到 {symbol} 持倉")
        return {"success": True, "position": result}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"/stock/position error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/stock/positions")
async def stock_positions():
    """
    查詢所有未平倉持倉（當日沖內部持倉 + 券商真實庫存）

    GET /stock/positions
    """
    try:
        svc = get_daytrade_service()
        daytrade_positions = svc.get_all_positions()

        # 尝试读取券商真实库存
        real_inventory = []
        if sdk and _stock_account:
            try:
                inv_resp = sdk.accounting.inventories(_stock_account)
                if inv_resp.is_success and inv_resp.data:
                    for item in inv_resp.data:
                        stock_no = getattr(item, 'stock_no', '')
                        qty = getattr(item, 'tradable_qty', 0)
                        if qty > 0:
                            real_inventory.append({
                                "symbol": stock_no,
                                "name": "",
                                "quantity": qty,
                                "type": "inventory",
                            })
            except Exception as e:
                logger.warning(f"inventory query failed: {e}")

        # 合併：當日沖持倉 + 券商庫存
        positions = daytrade_positions + real_inventory
        return {"success": True, "positions": positions, "count": len(positions)}
    except Exception as e:
        logger.error(f"/stock/positions error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/stock/price")
async def stock_price_update(req: StockPriceUpdateRequest):
    """
    饋入即時報價（更新持倉的 highest/lowest_since_entry）

    POST /stock/price
    Body: {"symbol": "2330", "current_price": 610.0}
    """
    try:
        svc = get_daytrade_service()
        svc.update_price(req.symbol, req.current_price)
        # 同時檢查是否觸發平倉條件
        should_close, reason = svc.check_exit(req.symbol, req.current_price)
        return {
            "success": True,
            "symbol": req.symbol,
            "current_price": req.current_price,
            "exit_triggered": should_close,
            "exit_reason": reason,
        }
    except Exception as e:
        logger.error(f"/stock/price error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/stock/pnl")
async def stock_pnl(req: StockPnlRequest):
    """
    計算所有持倉的未實現 + 已實現損益

    POST /stock/pnl
    Body: {"prices": {"2330": 610.0, "2317": 105.5}}
    """
    try:
        svc = get_daytrade_service()
        result = svc.calculate_pnl(req.prices)
        return {"success": True, **result}
    except Exception as e:
        logger.error(f"/stock/pnl error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/stock/close_all", dependencies=[Depends(verify_api_key)])
async def stock_close_all():
    """
    手動平掉所有未平倉持倉

    POST /stock/close_all
    """
    try:
        svc = get_daytrade_service()
        result = svc.auto_close_all(reason="manual")
        return {"success": True, **result}
    except Exception as e:
        logger.error(f"/stock/close_all error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ══════════════════════════════════════════════════════════════
# 期貨報價端點
# ══════════════════════════════════════════════════════════════

@app.post("/futures/quote")
async def futures_quote(req: FuturesQuoteRequest):
    """
    期貨報價查詢

    POST /futures/quote
    Body: {"code": "TXF"}
    """
    try:
        from futures_quote import get_futures_quote
        result = get_futures_quote(req.code)
        return result
    except Exception as e:
        logger.error(f"/futures/quote error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/futures/option/quote")
async def futures_option_quote(req: FuturesOptionQuoteRequest):
    """
    選擇權報價查詢

    POST /futures/option/quote
    Body: {"code": "TXO20250621500C"}
    """
    try:
        from futures_quote import get_futures_option_quote
        result = get_futures_option_quote(req.code)
        return result
    except Exception as e:
        logger.error(f"/futures/option/quote error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/futures/chain")
async def futures_chain(req: FuturesChainRequest):
    """
    履約價鍊查詢

    POST /futures/chain
    Body: {"symbol": "TXO"}
    """
    try:
        from futures_quote import get_futures_chain
        result = get_futures_chain(req.symbol)
        return result
    except Exception as e:
        logger.error(f"/futures/chain error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ══════════════════════════════════════════════════════════════
# 期貨下單端點
# ══════════════════════════════════════════════════════════════

@app.post("/futures/order", dependencies=[Depends(verify_api_key)])
async def futures_order(req: FuturesOrderRequest):
    """
    期貨下單（市價/限價 + 雙模式）

    POST /futures/order
    Body: {"account": "1247180/futopt/15901", "futures_code": "TXF202506",
           "price": 21500.0, "quantity": 1, "bs": "buy"}
    Body (雙模式): {"account_id": "...", "symbol": "TXF", "entry_mode": "breakdown_buy",
                   "quantity": 1, "stop_loss_pct": 2.0, "track_levels": 3}
    """
    try:
        # 支援 account_id (新) 和 account (舊) 兩種命名
        account = req.account_id or req.account or ""

        # 支援 symbol (新) 和 futures_code (舊) 兩種命名
        futures_code = req.symbol or req.futures_code or ""

        # 雙模式：若指定了 entry_mode，走 DayTradeService 流程
        if req.entry_mode:
            svc = get_daytrade_service()
            result = svc.entry(
                symbol=futures_code,
                entry_mode=req.entry_mode,
                price=req.price or 0.0,
                quantity=req.quantity,
                stop_loss_pct=req.stop_loss_pct or 2.0,
                track_levels=req.track_levels or 3,
                product_type="futures",
                account_id=account,
                tick_size=req.tick_size or 1.0,  # 期貨最小報價 1 點
            )
            return result

        # 傳統期貨下單
        from futures_order import place_futures_order
        result = place_futures_order(
            account=account,
            futures_code=futures_code,
            price=req.price,
            quantity=req.quantity,
            bs=req.bs
        )
        return result
    except Exception as e:
        logger.error(f"/futures/order error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/futures/condition/order", dependencies=[Depends(verify_api_key)])
async def futures_condition_order(req: FuturesConditionOrderRequest):
    """
    期貨條件單（存 SQLite，由條件單引擎評估觸發）

    POST /futures/condition/order
    Body: {"account": "...", "futures_code": "TXF", "trigger_price": 21600,
           "trigger_type": "above", "order_price": 21650, "quantity": 1, "bs": "buy"}
    """
    try:
        from futures_order import place_futures_condition_order
        result = place_futures_condition_order(
            account=req.account,
            futures_code=req.futures_code,
            trigger_price=req.trigger_price,
            trigger_type=req.trigger_type,
            order_price=req.order_price,
            quantity=req.quantity,
            bs=req.bs
        )
        return result
    except Exception as e:
        logger.error(f"/futures/condition/order error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/futures/cancel", dependencies=[Depends(verify_api_key)])
async def futures_cancel(req: CancelOrderRequest):
    """
    取消期貨委託

    POST /futures/cancel
    Body: {"order_id": "FU1234"}
    """
    try:
        from futures_order import cancel_futures_order
        result = cancel_futures_order(req.order_id)
        return result
    except Exception as e:
        logger.error(f"/futures/cancel error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/futures/positions")
async def futures_positions():
    """
    取得期貨持倉

    GET /futures/positions
    """
    try:
        from futures_order import get_futures_positions
        result = get_futures_positions()
        # 將 get_futures_positions 的 positions 陣列(map) ，
        # 轉為與 /stock/positions 一致的格式（加入 direction / entry_mode / realized_pnl）
        if result.get("success") and "positions" in result:
            return {
                "success": True,
                "positions": [
                    {
                        "symbol": p.get("symbol", ""),
                        "direction": p.get("direction", "BUY"),
                        "quantity": p.get("quantity", 0),
                        "avg_price": p.get("avg_price", 0.0),
                        "entry_mode": p.get("entry_mode", ""),
                        "realized_pnl": p.get("realized_pnl", 0.0),
                        "current_price": p.get("current_price") or p.get("avg_price", 0.0),
                    }
                    for p in result["positions"]
                ],
                "count": len(result["positions"]),
            }
        return result
    except Exception as e:
        logger.error(f"/futures/positions error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/futures/margin")
async def futures_margin(account_id: str = ""):
    """
    取得期貨帳戶保證金餘額

    GET /futures/margin  （login 後可用，account_id 參數已廢棄，改用登入後快取的期貨帳戶）
    """
    try:
        from futures_order import get_futures_margin
        # account_id 參數已廢棄（改用登入時快取的 _futopt_account）
        result = get_futures_margin("")
        if result.get("success"):
            return result
        raise HTTPException(status_code=400, detail=result.get("message", "查詢失敗"))
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"/futures/margin error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ══════════════════════════════════════════════════════════════
# 條件單引擎端點
# ══════════════════════════════════════════════════════════════

@app.post("/condition/order", dependencies=[Depends(verify_api_key)])
async def condition_order(req: ConditionOrderRequest):
    """
    新增條件單

    POST /condition/order
    Body: {"symbol": "2330", "trigger_price": 605.0,
           "trigger_type": "above", "quantity": 1000, "bs": "buy"}
    """
    try:
        from condition_engine import ConditionOrder
        engine = get_condition_engine()

        from uuid import uuid4
        cond_id = f"cond_{uuid4().hex[:12]}"

        order = ConditionOrder(
            id=cond_id,
            symbol=req.symbol,
            trigger_price=req.trigger_price,
            trigger_type=req.trigger_type,
            quantity=req.quantity,
            bs=req.bs,
            order_price=req.order_price,
        )
        engine.add_condition_order(order)
        return {
            "success": True,
            "condition_id": cond_id,
            "message": "條件單已新增"
        }
    except Exception as e:
        logger.error(f"/condition/order error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/condition/orders")
async def condition_orders(status: Optional[str] = None):
    """
    查詢所有條件單

    GET /condition/orders
    Query: ?status=active
    """
    try:
        engine = get_condition_engine()
        orders = engine.list_condition_orders(status=status)
        return {"success": True, "conditions": orders}
    except Exception as e:
        logger.error(f"/condition/orders error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/condition/order/{cond_id}", dependencies=[Depends(verify_api_key)])
async def condition_delete(cond_id: str):
    """
    刪除條件單

    DELETE /condition/order/{cond_id}
    """
    try:
        engine = get_condition_engine()
        removed = engine.remove_condition_order(cond_id)
        if removed:
            return {"success": True, "message": f"條件單 {cond_id} 已移除"}
        return {"success": False, "message": f"條件單 {cond_id} 不存在"}
    except Exception as e:
        logger.error(f"/condition/order/{cond_id} delete error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/condition/evaluate", dependencies=[Depends(verify_api_key)])
async def condition_evaluate(req: ConditionEvaluateRequest):
    """
    報價推送時呼叫（評估條件單）

    POST /condition/evaluate
    Body: {"symbol": "2330", "last_price": 605.0, "volume": 1500,
           "close_price": 600.0, "settlement_price": 600.0}

    此端點由 Quotes WebSocket 推送呼叫，
    引擎會評估所有 status="active" 的條件單，
    若有觸發則回傳被觸發的條件單列表。
    """
    try:
        engine = get_condition_engine()
        quote_data = {
            "symbol": req.symbol,
            "last_price": req.last_price,
            "volume": req.volume,
            "close_price": req.close_price,
            "settlement_price": req.settlement_price,
        }
        triggered = engine.evaluate_all_conditions(quote_data)
        return {
            "success": True,
            "symbol": req.symbol,
            "triggered": triggered,
            "triggered_count": len(triggered),
        }
    except Exception as e:
        logger.error(f"/condition/evaluate error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ══════════════════════════════════════════════════════════════
# 排程服務端點 + 報價轉播服務端點
# ══════════════════════════════════════════════════════════════

@app.post("/scheduler/start", dependencies=[Depends(verify_api_key)])
async def scheduler_start():
    """
    啟動排程服務

    POST /scheduler/start
    會初始化 SchedulerService 並註冊定時任務：
      - 08:30 盤前條件單預掃
      - 13:20 股票當日沖自動平倉
      - 13:30 期貨自動平倉
      - 每分鐘 漲跌停監控
    """
    try:
        from scheduler_service import scheduler_service
        from daytrade_service import DayTradeService

        # 注入元件
        if 'scheduler_service' not in dir():
            # 第一次：建立 SchedulerService 單例
            pass

        # 注入 DayTradeService（若已初始化）
        try:
            from fubon_client import FubonClient
            # 使用全域 fubon_client，若有的話
            global fubon_client_for_scheduler
            if fubon_client_for_scheduler:
                dts = DayTradeService(fubon_client_for_scheduler)
                scheduler_service.set_daytrade_service(dts)
        except Exception as e:
            logger.warning(f"DayTradeService injection skipped: {e}")

        # 注入 ConditionEngine
        scheduler_service.set_condition_engine(get_condition_engine())

        # 注入 LimitUpDownService
        try:
            from limit_up_down_service import LimitUpDownService
            lud_svc = LimitUpDownService()
            if 'fubon_client_for_scheduler' in dir() and fubon_client_for_scheduler:
                lud_svc.set_fubon_client(fubon_client_for_scheduler)
            scheduler_service.set_limit_up_down_service(lud_svc)
        except Exception as e:
            logger.warning(f"LimitUpDownService injection skipped: {e}")

        # 注入期貨下單模組
        try:
            import futures_order
            scheduler_service.set_futures_order_module(futures_order)
        except Exception as e:
            logger.warning(f"futures_order injection skipped: {e}")

        scheduler_service.start()

        return {
            "success": True,
            "message": "排程服務已啟動",
            "status": scheduler_service.status,
            "schedule": {
                "08:30": "條件單引擎預掃 (ConditionEngine.evaluate_all)",
                "13:20": "股票當日沖自動平倉 (DayTradeService.auto_squaring_check)",
                "13:30": "期貨自動平倉",
                "每分鐘": "漲跌停監控 (LimitUpDownService.check_limit_up_down)",
            }
        }
    except Exception as e:
        logger.error(f"/scheduler/start error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/scheduler/stop", dependencies=[Depends(verify_api_key)])
async def scheduler_stop():
    """
    停止排程服務

    POST /scheduler/stop
    """
    try:
        from scheduler_service import scheduler_service
        scheduler_service.stop()
        return {
            "success": True,
            "message": "排程服務已停止",
            "status": scheduler_service.status,
        }
    except Exception as e:
        logger.error(f"/scheduler/stop error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/scheduler/status")
async def scheduler_status():
    """
    查詢排程狀態

    GET /scheduler/status
    """
    try:
        from scheduler_service import scheduler_service
        return {
            "status": scheduler_service.status,
            "is_running": scheduler_service._running,
            "auto_square_enabled": scheduler_service.is_auto_square_enabled(),
        }
    except Exception as e:
        logger.error(f"/scheduler/status error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/scheduler/auto_square_result")
async def scheduler_auto_square_result():
    """
    查詢最近一次自動平倉結果

    GET /scheduler/auto_square_result
    """
    try:
        from scheduler_service import scheduler_service
        result = scheduler_service.get_auto_square_result()
        if result:
            return {"success": True, "result": result}
        return {"success": True, "result": None, "message": "尚無記錄"}
    except Exception as e:
        logger.error(f"/scheduler/auto_square_result error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/scheduler/trigger_now", dependencies=[Depends(verify_api_key)])
async def scheduler_trigger_now():
    """
    手動觸發一次自動平倉（測試用）

    POST /scheduler/trigger_now
    會立即執行股票與期貨的自動平倉
    """
    try:
        from scheduler_service import scheduler_service
        result = scheduler_service.trigger_now()
        return {"success": True, "result": result}
    except Exception as e:
        logger.error(f"/scheduler/trigger_now error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


class AutoSquareToggleRequest(BaseModel):
    enabled: bool


@app.post("/scheduler/auto_square_toggle", dependencies=[Depends(verify_api_key)])
async def scheduler_auto_square_toggle(req: AutoSquareToggleRequest):
    """
    開關自動平倉功能（Fix #4）

    POST /scheduler/auto_square_toggle
    Body: {"enabled": true}
    建議在 Android UI 提供開關，讓用戶自行決定是否啟用 13:20 自動平倉
    """
    try:
        from scheduler_service import scheduler_service
        scheduler_service.set_auto_square_enabled(req.enabled)
        return {
            "success": True,
            "auto_square_enabled": req.enabled,
            "message": f"自動平倉已{'開啟' if req.enabled else '關閉'}"
        }
    except Exception as e:
        logger.error(f"/scheduler/auto_square_toggle error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/scheduler/limit_up_down_results")
async def scheduler_limit_up_down_results():
    """
    查詢最近漲跌停平倉記錄

    GET /scheduler/limit_up_down_results
    """
    try:
        from scheduler_service import scheduler_service
        lud_svc = scheduler_service._limit_up_down_service
        if lud_svc:
            return {"success": True, "results": lud_svc.get_recent_results()}
        return {"success": True, "results": []}
    except Exception as e:
        logger.error(f"/scheduler/limit_up_down_results error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ══════════════════════════════════════════════════════════════
# 報價轉播 REST API
# ══════════════════════════════════════════════════════════════

class QuotesSubscribeRequest(BaseModel):
    symbol: str
    product_type: str = "stock"   # "stock" | "futures"


class QuotesUnsubscribeRequest(BaseModel):
    symbol: str


class QuotesBroadcastRequest(BaseModel):
    symbol: Optional[str] = None


@app.post("/quotes/subscribe")
async def quotes_subscribe(req: QuotesSubscribeRequest):
    """
    訂閱股票/期貨報價

    POST /quotes/subscribe
    Body: {"symbol": "2330", "product_type": "stock"}
    """
    try:
        qbs = get_quotes_broadcast_service()
        qbs.add_subscription(req.symbol, product_type=req.product_type)
        return {
            "success": True,
            "message": f"已訂閱 {req.symbol}",
            "subscriptions": qbs.get_subscriptions(),
        }
    except Exception as e:
        logger.error(f"/quotes/subscribe error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/quotes/unsubscribe")
async def quotes_unsubscribe(req: QuotesUnsubscribeRequest):
    """取消訂閱"""
    try:
        qbs = get_quotes_broadcast_service()
        qbs.remove_subscription(req.symbol)
        return {
            "success": True,
            "message": f"已取消訂閱 {req.symbol}",
            "subscriptions": qbs.get_subscriptions(),
        }
    except Exception as e:
        logger.error(f"/quotes/unsubscribe error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/quotes/subscriptions")
async def quotes_subscriptions():
    """查詢目前所有訂閱"""
    try:
        qbs = get_quotes_broadcast_service()
        return {"success": True, "subscriptions": qbs.get_subscriptions()}
    except Exception as e:
        logger.error(f"/quotes/subscriptions error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/quotes/broadcast", dependencies=[Depends(verify_api_key)])
async def quotes_broadcast_now(req: QuotesBroadcastRequest):
    """手動觸發一次廣播（用於測試）"""
    try:
        qbs = get_quotes_broadcast_service()
        if not req.symbol:
            return {"success": False, "message": "請提供 symbol"}
        sym = req.symbol.upper()
        info = qbs.get_subscriptions().get(sym, {})
        if info.get("product_type") == "futures":
            quote = await qbs._fetch_futures_quote(sym)
        else:
            quote = await qbs._fetch_stock_quote(sym)
        if quote and qbs._ws_manager:
            await qbs._ws_manager.broadcast_quote(sym, quote)
            return {"success": True, "message": f"{sym} 廣播完成", "quote": quote}
        return {"success": False, "message": f"{sym} 未訂閱"}
    except Exception as e:
        logger.error(f"/quotes/broadcast error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.on_event("startup")
async def startup_quotes_broadcast():
    """服務啟動時自動啟動 QuotesBroadcastService"""
    try:
        qbs = get_quotes_broadcast_service()
        qbs.start()
        logger.info("[Startup] QuotesBroadcastService 已啟動")
    except Exception as e:
        logger.warning(f"[Startup] QuotesBroadcastService 啟動失敗: {e}")


# ══════════════════════════════════════════════════════════════
# WebSocket 即時推送端點
# ══════════════════════════════════════════════════════════════

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    """
    WebSocket 端點 — 接收 Android App 連線

    支援訊息格式：
      1. {"event": "ping"} → 回 pong
      2. {"event": "subscribe", "symbols": ["2330", "TXF"], "topics": ["order_update"]}
         → 訂閱股票報價 + 事件通知
      3. {"event": "unsubscribe", "symbols": ["2330"]}
         → 取消訂閱股票

    推送格式（Server → Client）：
      {"event": "quote", "data": {...}, "timestamp": "..."}
      {"event": "order_update", "data": {...}, "timestamp": "..."}
    """
    manager = get_ws_manager()

    # 自動產生 client_id
    import uuid
    client_id = str(uuid.uuid4())[:12]

    await manager.connect(client_id, websocket)

    try:
        await websocket.send_json({
            "event": "connected",
            "client_id": client_id,
            "message": "已連線，請發送 subscribe 事件訂閱報價",
        })

        while True:
            data = await websocket.receive_text()
            try:
                msg = json.loads(data)
                event = msg.get("event", "")

                if event == "ping":
                    await websocket.send_json({
                        "event": "pong",
                        "timestamp": datetime.now().isoformat(),
                    })

                elif event == "subscribe":
                    # 訂閱股票報價 + 事件 topics
                    symbols = msg.get("symbols", [])
                    topics = msg.get("topics", [])
                    manager.subscribe(client_id, symbols=symbols, topics=topics)

                    # 若有訂閱股票，註冊到 QuotesBroadcastService
                    if symbols:
                        qbs = get_quotes_broadcast_service()
                        for sym in symbols:
                            # 自動判斷是股票還是期貨
                            prod_type = "futures" if sym.upper().startswith(("TXF", "MXF", "EXF", "FEF", "TXO")) else "stock"
                            qbs.add_subscription(sym, product_type=prod_type)

                    await websocket.send_json({
                        "event": "subscribed",
                        "symbols": symbols,
                        "topics": topics,
                    })

                elif event == "unsubscribe":
                    symbols = msg.get("symbols", [])
                    manager.unsubscribe(client_id, symbols=symbols)

                    # 從 QuotesBroadcastService 移除（若無其他客戶端訂閱）
                    if symbols:
                        qbs = get_quotes_broadcast_service()
                        for sym in symbols:
                            qbs.remove_subscription(sym)

                    await websocket.send_json({
                        "event": "unsubscribed",
                        "symbols": symbols,
                    })

                else:
                    await websocket.send_json({
                        "event": "ack",
                        "original_event": event,
                    })

            except json.JSONDecodeError:
                if data == "ping":
                    await websocket.send_json({"event": "pong"})

    except WebSocketDisconnect:
        await manager.disconnect(client_id)
        # 移除 QuotesBroadcastService 的訂閱（所有符號）
        qbs = get_quotes_broadcast_service()
        for sym in list(qbs.get_subscriptions().keys()):
            qbs.remove_subscription(sym)
    except Exception as e:
        logger.error(f"WebSocket error for {client_id}: {e}")
        await manager.disconnect(client_id)


# ══════════════════════════════════════════════════════════════
# Notification 端點
# ══════════════════════════════════════════════════════════════

class TokenRegisterRequest(BaseModel):
    client_id: str
    platform: str  # "firebase" | "line" | "telegram"
    token: str
    metadata: Optional[Dict[str, Any]] = None


class ManualNotificationRequest(BaseModel):
    title: str
    body: str
    event_type: str = "manual"
    data: Optional[Dict[str, Any]] = None


@app.post("/notification/register")
async def register_notification_token(req: TokenRegisterRequest):
    """
    註冊 client push token（for Firebase/LINE/Telegram）

    POST /notification/register
    Body: {"client_id": "user123", "platform": "firebase", "token": "..."}
    """
    try:
        from notification_service import get_notification_service
        ns = get_notification_service()

        success = ns.register_client_token(
            client_id=req.client_id,
            platform=req.platform,
            token=req.token,
            metadata=req.metadata,
        )

        # 注入 ws_manager
        ns.set_websocket_manager(get_ws_manager())

        return {
            "success": success,
            "message": f"Token registered for {req.client_id} ({req.platform})",
        }
    except Exception as e:
        logger.error(f"/notification/register error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/notification/send", dependencies=[Depends(verify_api_key)])
async def send_notification(req: ManualNotificationRequest):
    """
    手動發送通知（管理後台）

    POST /notification/send
    Body: {"title": "Test", "body": "Hello", "event_type": "manual"}
    """
    try:
        from notification_service import get_notification_service
        ns = get_notification_service()
        ns.set_websocket_manager(get_ws_manager())

        success = ns.send_custom_notification(
            title=req.title,
            body=req.body,
            event_type=req.event_type,
            data=req.data,
        )

        return {
            "success": success,
            "message": f"Notification sent: {req.title}",
        }
    except Exception as e:
        logger.error(f"/notification/send error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/notification/clients")
async def get_notification_clients():
    """
    取得已連線的 WebSocket clients 概覽
    """
    manager = get_ws_manager()
    return manager.get_clients_summary()


# ══════════════════════════════════════════════════════════════
# 健康檢查增強
# ══════════════════════════════════════════════════════════════

@app.get("/health")
async def health_check():
    """
    健康檢查端點

    GET /health
    Returns: {"status": "healthy", "sdk_ready": bool, "ws_clients": int}
    """
    manager = get_ws_manager()
    return {
        "status": "healthy",
        "sdk_ready": sdk is not None,
        "ws_clients": manager.get_connection_count(),
        "version": "1.2.0",
    }


# ========== Main ==========

if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("PORT", "8080"))
    logger.info(f"Starting Fubon DayTrade Service on port {port}")

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=port,
        log_level="info"
    )
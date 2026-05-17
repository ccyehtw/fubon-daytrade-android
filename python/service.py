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

from fastapi import FastAPI, HTTPException, Request
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
ALLOWED_ORIGINS = os.environ.get("ALLOWED_ORIGINS", "http://localhost:*,http://10.0.2.2:*,http://127.0.0.1:*").split(",")

app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=False,  # False because we use static API Key auth, not Cookie-based
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
)

# Global SDK instance
sdk: Optional[FubonSDK] = None
accounts_cache: List[Dict[str, str]] = []

# 條件單引擎（單例）
condition_engine = None


def get_condition_engine():
    """延遲初始化條件單引擎"""
    global condition_engine
    if condition_engine is None:
        from condition_engine import ConditionEngine
        condition_engine = ConditionEngine()
        condition_engine.load_from_db()
    return condition_engine


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


# ========== 期貨下單 Request/Response Models ==========

class FuturesOrderRequest(BaseModel):
    account: str          # 期貨帳號
    futures_code: str     # 期貨商品代碼
    price: Optional[float] # 委託價格（None = 市價）
    quantity: int         # 委託口數
    bs: str = "buy"       # "buy" | "sell"


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
        logger.info(f"嘗試登入: personal_id={masked_pid}, api_key={api_key[:8]}..., cert_path={cert_path}")
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
                    display_name = f"期貨 {getattr(acc, 'account_id', 'N/A')}"
                elif acc_type == "stock":
                    display_name = f"證券 {getattr(acc, 'account_id', 'N/A')}"
                else:
                    display_name = f"{acc_type} {getattr(acc, 'account_id', 'N/A')}"

                accounts.append({
                    "account_id": getattr(acc, 'account_id', ''),
                    "account_type": acc_type,
                    "display_name": display_name
                })

        accounts_cache = accounts

        # 初始化其他模組的 SDK
        try:
            from futures_quote import init_sdk as fq_init
            fq_init(sdk)
        except Exception as e:
            logger.warning(f"futures_quote init error: {e}")
        try:
            from futures_order import init_sdk as fo_init
            fo_init(sdk)
        except Exception as e:
            logger.warning(f"futures_order init error: {e}")

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

@app.post("/futures/order")
async def futures_order(req: FuturesOrderRequest):
    """
    期貨下單（市價/限價）

    POST /futures/order
    Body: {"account": "1247180/futopt/15901", "futures_code": "TXF202506",
           "price": 21500.0, "quantity": 1, "bs": "buy"}
    """
    try:
        from futures_order import place_futures_order
        result = place_futures_order(
            account=req.account,
            futures_code=req.futures_code,
            price=req.price,
            quantity=req.quantity,
            bs=req.bs
        )
        return result
    except Exception as e:
        logger.error(f"/futures/order error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/futures/condition/order")
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


@app.post("/futures/cancel")
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
        return result
    except Exception as e:
        logger.error(f"/futures/positions error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ══════════════════════════════════════════════════════════════
# 條件單引擎端點
# ══════════════════════════════════════════════════════════════

@app.post("/condition/order")
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


@app.delete("/condition/order/{cond_id}")
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


@app.post("/condition/evaluate")
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
# 排程服務端點（Phase 4-3）
# ══════════════════════════════════════════════════════════════

@app.post("/scheduler/start")
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


@app.post("/scheduler/stop")
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


@app.post("/scheduler/trigger_now")
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


# ========== Main ==========
# WebSocket 即時推送端點
# ══════════════════════════════════════════════════════════════

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    """
    WebSocket 端點 — 接收 Android App 連線

    Android App 可透過此端訂閱即時事件：
    - order_update: 成交通知
    - condition_triggered: 條件單觸發
    - auto_square: 自動平倉
    - quote_alert: 報價警報

    客戶端範例（JavaScript）：
        const ws = new WebSocket("ws://host:port/ws");
        ws.onmessage = (event) => {
            const data = JSON.parse(event.data);
            console.log(data.event, data.data);
        };
    """
    manager = get_ws_manager()
    client_id = await manager.connect(websocket)

    try:
        while True:
            # 接收客戶端訊息（ping/pong 或訂閱控制）
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
                    # 客戶端訂閱特定事件（目前廣播已包含所有事件，此處預留）
                    await websocket.send_json({
                        "event": "subscribed",
                        "topics": msg.get("topics", []),
                    })
                else:
                    # 回覆收到了
                    await websocket.send_json({
                        "event": "ack",
                        "original_event": event,
                    })
            except json.JSONDecodeError:
                # 非 JSON 訊息，當作 ping 處理
                if data == "ping":
                    await websocket.send_json({"event": "pong"})
    except WebSocketDisconnect:
        await manager.disconnect(client_id)
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


@app.post("/notification/send")
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
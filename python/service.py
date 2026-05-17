#!/usr/bin/env python3
"""
富邦證券 - Python Service (Phase 1)
提供登入框架 + Fubon SDK 整合
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
app = FastAPI(title="Fubon DayTrade Service", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Global SDK instance
sdk: Optional[FubonSDK] = None
accounts_cache: List[Dict[str, str]] = []


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


# ========== SDK Login Framework ==========

async def sdk_login(personal_id: str, api_key: str, cert_path: str, cert_password: Optional[str] = None) -> LoginResponse:
    """
    使用 FubonSDK.apikey_login() 登入
    
    注意：Python SDK 只能用位置參數，不能用關鍵字參數！
    
    Args:
        personal_id: 身分證字號
        api_key: API Key
        cert_path: 憑證檔案路徑
        cert_password: 憑證密碼（預設為 personal_id）
    
    Returns:
        LoginResponse with accounts list
    """
    global sdk, accounts_cache

    if not FUBON_SDK_AVAILABLE:
        return LoginResponse(
            success=False,
            message="Fubon SDK not available. Install: pip install fubon-neo-api"
        )

    try:
        # Initialize SDK
        sdk = FubonSDK()
        
        # Use cert_password or default to personal_id
        password = cert_password if cert_password else personal_id
        
        logger.info(f"Attempting login with personal_id={personal_id}, api_key={api_key[:8]}..., cert_path={cert_path}")
        
        # Call SDK login - positional arguments only!
        result = sdk.apikey_login(personal_id, api_key, cert_path, password)
        
        logger.info(f"Login result: {result}")
        
        if not result.is_success:
            return LoginResponse(
                success=False,
                message=result.message or "登入失敗"
            )
        
        # Parse accounts from result
        accounts = []
        if hasattr(result, 'data') and result.data:
            for acc in result.data:
                acc_type = getattr(acc, 'account_type', 'unknown')
                
                # Determine account type from account_type field
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
    return {"service": "Fubon DayTrade Service", "status": "running", "sdk_available": FUBON_SDK_AVAILABLE}


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
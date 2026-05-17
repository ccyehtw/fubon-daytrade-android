# fubon_client.py — 富邦新一代 API SDK 封裝類
# 對應富邦 FubonSDK v2.2.8，支援證券 / 期貨下單與帳務查詢

from fubon_neo.sdk import FubonSDK, Order
from fubon_neo.constant import (
    TimeInForce, OrderType, PriceType, MarketType, BSAction
)
from dataclasses import dataclass
from typing import Optional, List, Dict, Any
from datetime import datetime
import logging

logger = logging.getLogger(__name__)


@dataclass
class FubonAccount:
    """帳號資料結構"""
    id: str          # 帳號識別碼
    broker_id: str    # 券商代碼
    account_type: str # 帳戶類型（ securities / futures ）


class FubonClient:
    """
    富邦新一代 API SDK 封裝類

    使用範例:
        client = FubonClient()
        client.login("N123127908", "your_api_key", "./cert.pfx", "your_cert_pass")
        info = client.get_account_info()
        client.place_order("2330", 600.0, 1000, order_type="limit")
    """

    def __init__(self):
        self._sdk = FubonSDK()
        self._accounts: List[FubonAccount] = []
        self._stock_account = None
        self._futures_account = None
        self._connected = False

    # ──────────────────────────────────────────────────────────────
    # 登入 / 初始化
    # ──────────────────────────────────────────────────────────────

    def login(self, personal_id: str, api_key: str,
              cert_path: str, cert_pass: str) -> List[FubonAccount]:
        """
        登入富邦 API

        Args:
            personal_id: 身分證字號（範例: N123127908）
            api_key:     API Key（38位Hex，0586E47E4932C8A4A4D27AE52910384B2EBBC9D6B8773487527FE42CFF328E5C）
            cert_path:   憑證檔案路徑（.pfx）
            cert_pass:   憑證密碼

        Returns:
            List[FubonAccount] — 所有登入帳號（含證券 / 期貨）
        """
        logger.info(f"嘗試登入富邦 API，personal_id={personal_id}")
        accounts_resp = self._sdk.login(personal_id, api_key, cert_path, cert_pass)

        if not accounts_resp.is_success:
            raise RuntimeError(
                f"登入失敗: {accounts_resp.message}"
            )

        self._accounts = []
        for acct in accounts_resp.data:
            logger.info(f"登入成功，帳號: {acct}")
            # 根據帳號格式自動識別證券或期貨帳號
            # 證券格式: 4數字+斜線+英文+數字 (例: 1247180/futopt/15901)
            # 期貨帳號包含 futopt/fut/future 等關鍵字
            acct_str = str(acct)
            if "futopt" in acct_str.lower() or "fut" in acct_str.lower() or "future" in acct_str.lower():
                acct_type = "futures"
            else:
                acct_type = "securities"
            self._accounts.append(FubonAccount(
                id=acct_str,
                broker_id="",
                account_type=acct_type
            ))

        # 預設取第一個帳號為現貨帳號
        if self._accounts:
            self._stock_account = self._accounts[0]

        self._connected = True
        logger.info(f"共登入 {len(self._accounts)} 個帳號")
        return self._accounts

    # ──────────────────────────────────────────────────────────────
    # 帳號資訊
    # ──────────────────────────────────────────────────────────────

    def get_account_info(self) -> Dict[str, Any]:
        """
        取得帳號資訊

        Returns:
            dict — 包含帳號、帳務、持倉等摘要資訊
        """
        if not self._connected:
            raise RuntimeError("尚未登入，請先呼叫 login()")

        result = {
            "accounts": [acct.__dict__ for acct in self._accounts],
            "stock_account": self._stock_account.id if self._stock_account else None,
            "futures_account": self._futures_account.id if self._futures_account else None,
        }

        # 嘗試取得庫存資料
        if self._stock_account:
            try:
                inv_resp = self._sdk.accounting.inventories(self._stock_account.id)
                if inv_resp.is_success:
                    result["inventory"] = inv_resp.data
            except Exception as e:
                logger.warning(f"取得庫存失敗: {e}")

        return result

    # ──────────────────────────────────────────────────────────────
    # 股票下單
    # ──────────────────────────────────────────────────────────────

    def place_order(
        self,
        stock_no: str,
        price: Optional[float],
        quantity: int,
        order_type: str = "limit",
        buy_sell: str = "buy",
        time_in_force: str = "rod"
    ) -> Dict[str, Any]:
        """
        股票下單

        Args:
            stock_no:     股票代碼（例: "2330"）
            price:        委託價格（None = 市價）
            quantity:     委託數量（股數，1000=1張）
            order_type:   "limit" | "market" | "peek" | "market_range"（預設: limit）
            buy_sell:     "buy" | "sell"（預設: buy）
            time_in_force: "rod" | "ioc" | "fok"（預設: rod）

        Returns:
            dict — 下單回報，內容包含 order_no, status 等
        """
        if not self._connected:
            raise RuntimeError("尚未登入")

        if self._stock_account is None:
            raise RuntimeError("無可用現貨帳號")

        # 轉換 buy_sell
        bs_action = BSAction.Buy if buy_sell.lower() == "buy" else BSAction.Sell

        # 轉換 price_type
        price_type_map = {
            "limit":         PriceType.Limit,
            "market":        PriceType.Market,
            "peek":          PriceType.Peek,
            "market_range":  PriceType.MarketRange,
        }
        ptype = price_type_map.get(order_type.lower(), PriceType.Limit)

        # 轉換 time_in_force
        tif_map = {
            "rod": TimeInForce.ROD,
            "ioc": TimeInForce.IOC,
            "fok": TimeInForce.FOK,
        }
        tif = tif_map.get(time_in_force.lower(), TimeInForce.ROD)

        # 建立委託單
        order = Order(
            buy_sell=bs_action,
            symbol=stock_no,
            price=str(price) if price is not None else None,
            quantity=quantity,
            market_type=MarketType.Common,
            price_type=ptype,
            time_in_force=tif,
            order_type=OrderType.Stock,
            user_def="fubon_client"
        )

        logger.info(
            f"股票下單: {bs_action.name} {stock_no} x {quantity} @ "
            f"{price if price else '市價'} ({ptype.name})"
        )

        resp = self._sdk.stock.place_order(self._stock_account.id, order)

        if not resp.is_success:
            logger.error(f"下單失敗: {resp.message}")
            return {"success": False, "message": resp.message}

        # 取得第一筆委託單回傳
        order_data = resp.data[0] if resp.data else {}
        return {
            "success": True,
            "order_no": getattr(order_data, "order_no", None),
            "seq_no":   getattr(order_data, "seq_no", None),
            "status":   getattr(order_data, "status", None),
            "data":     order_data,
        }

    # ──────────────────────────────────────────────────────────────
    # 期貨下單
    # ──────────────────────────────────────────────────────────────

    def place_futures_order(
        self,
        futures_code: str,
        price: Optional[float],
        quantity: int,
        buy_sell: str = "buy"
    ) -> Dict[str, Any]:
        """
        期貨下單

        Args:
            futures_code: 期貨商品代碼（例: "TXF202506"）
            price:        委託價格（None = 市價）
            quantity:     委託口數
            buy_sell:     "buy" | "sell"（預設: buy）

        Returns:
            dict — 下單回報
        """
        if not self._connected:
            raise RuntimeError("尚未登入")

        # 期貨使用不同的模組，須確認已設定期貨帳號
        # 此為簡化實作，實際請依富邦 SDK 期貨模組調用
        logger.warning("期貨下單模組需依據實際期貨帳號 1247180/futopt/15901 設定")
        raise NotImplementedError(
            "期貨下單請使用 fubon_neo.futures 模組，請確認期貨帳號已啟用"
        )

    # ──────────────────────────────────────────────────────────────
    # 取消委託
    # ──────────────────────────────────────────────────────────────

    def cancel_order(self, order_id: str) -> Dict[str, Any]:
        """
        取消委託

        Args:
            order_id: 委託書號（例: "x0003"）

        Returns:
            dict — 取消結果
        """
        if not self._connected:
            raise RuntimeError("尚未登入")

        # 取得委託單
        orders_resp = self._sdk.stock.get_order_results(self._stock_account.id)
        if not orders_resp.is_success:
            return {"success": False, "message": "查詢委託單失敗"}

        target = None
        for o in orders_resp.data:
            if o.order_no == order_id:
                target = o
                break

        if target is None:
            return {"success": False, "message": f"找不到委託單 {order_id}"}

        cancel_resp = self._sdk.stock.cancel_order(self._stock_account.id, target)
        logger.info(f"取消委託 {order_id}: {cancel_resp}")

        return {
            "success": cancel_resp.is_success,
            "order_no": order_id,
            "message": getattr(cancel_resp, "message", None),
        }

    # ──────────────────────────────────────────────────────────────
    # 查詢委託狀態
    # ──────────────────────────────────────────────────────────────

    def get_order_status(self, order_id: str) -> Dict[str, Any]:
        """
        查詢委託狀態

        Args:
            order_id: 委託書號（例: "x0003"）

        Returns:
            dict — 委託單詳細資訊
        """
        if not self._connected:
            raise RuntimeError("尚未登入")

        orders_resp = self._sdk.stock.get_order_results(self._stock_account.id)
        if not orders_resp.is_success:
            return {"success": False, "message": "查詢委託單失敗"}

        for o in orders_resp.data:
            if o.order_no == order_id:
                return {
                    "success": True,
                    "order_no":   o.order_no,
                    "seq_no":     o.seq_no,
                    "symbol":     o.symbol,
                    "buy_sell":   o.buy_sell,
                    "price":      o.price,
                    "quantity":   o.quantity,
                    "after_qty":  o.after_qty,
                    "status":     o.status,
                    "user_def":   o.user_def,
                }

        return {"success": False, "message": f"找不到委託單 {order_id}"}

    # ──────────────────────────────────────────────────────────────
    # 查詢當日歷史委託
    # ──────────────────────────────────────────────────────────────

    def get_today_orders(self) -> List[Dict[str, Any]]:
        """取得當日所有委託單"""
        if not self._connected:
            raise RuntimeError("尚未登入")

        resp = self._sdk.stock.get_order_results(self._stock_account.id)
        if not resp.is_success:
            return []

        return [
            {
                "order_no": o.order_no,
                "seq_no":   o.seq_no,
                "symbol":   o.symbol,
                "buy_sell": o.buy_sell,
                "price":    o.price,
                "quantity": o.quantity,
                "after_qty":o.after_qty,
                "status":   o.status,
            }
            for o in resp.data
        ]

    # ──────────────────────────────────────────────────────────────
    # 斷線
    # ──────────────────────────────────────────────────────────────

    def disconnect(self):
        """關閉 SDK 連線"""
        self._connected = False
        logger.info("富邦 SDK 已斷線")
# 富邦 API Enum 常數對照表

本檔案為富邦新一代 API（v2.2.8）Python SDK 的 Enum 常數整理，供 Android App 開發團隊快速查閱。

---

## 已驗證常數（2026-05-17）

```python
from fubon_neo.constant import (
    PriceType, TimeInForce, OrderType, MarketType, BSAction
)
```

### OrderType（委託類別）

| Enum 值 | Python 属性 | 說明 |
|---------|------------|------|
| `1` | `OrderType.Stock` | 現股 |
| `2` | `OrderType.DayTrade` | ⚡ **當日沖銷** |
| `3` | `OrderType.Margin` | 融資 |
| `4` | `OrderType.SBL` | 融券 |
| `5` | `OrderType.Short` | 借券 |

### PriceType（市價別）

| Enum 值 | Python 属性 | 說明 |
|---------|------------|------|
| `0` | `PriceType.Limit` | 限價 |
| `1` | `PriceType.Market` | ⚡ **市價** |
| `2` | `PriceType.Reference` | 參考價 |
| `3` | `PriceType.LimitUp` | ⚡ **漲停價**（達漲停平倉）|
| `4` | `PriceType.LimitDown` | ⚡ **跌停價**（達跌停平倉）|

### TimeInForce（委託時效）

| Enum 值 | Python 属性 | 說明 |
|---------|------------|------|
| `0` | `TimeInForce.ROD` | 當日委託（Rest of Day）|
| `1` | `TimeInForce.IOC` | ⚡ **立即成交否則取消**（自動平倉）|
| `2` | `TimeInForce.FOK` | 全部成交否則取消 |

### MarketType（市場別）

| Enum 值 | Python 属性 | 說明 |
|---------|------------|------|
| `0` | `MarketType.Common` | 普通股（整股）|
| `1` | `MarketType.Emg` | 盤中臨時買賣 |
| `2` | `MarketType.Fixing` | 盤定交易 |
| `3` | `MarketType.IntradayOdd` | ⚡ **盤中零股** |
| `4` | `MarketType.Odd` | ⚡ **盤後零股** |

### BSAction（買賣別）

| Enum 值 | Python 属性 | 說明 |
|---------|------------|------|
| `0` | `BSAction.Buy` | 買進 |
| `1` | `BSAction.Sell` | 賣出 |

---

## 常見下單組合

### 當日沖銷買進（市價 IOC）

```python
order = Order(
    buy_sell      = BSAction.Buy,
    symbol        = "2330",
    price         = None,                      # 市價單不設價格
    quantity      = 2000,                      # 2 張
    market_type   = MarketType.Common,
    price_type    = PriceType.Market,           # 市價
    time_in_force = TimeInForce.IOC,            # 立即成交否則取消
    order_type    = OrderType.DayTrade,         # ⚡ 當日沖銷
)
result = sdk.stock.place_order(account, order)
```

### 現股限價 ROD

```python
order = Order(
    buy_sell      = BSAction.Buy,
    symbol        = "2330",
    price         = "2270",                     # 限價
    quantity      = 2000,
    market_type   = MarketType.Common,
    price_type    = PriceType.Limit,
    time_in_force = TimeInForce.ROD,
    order_type    = OrderType.Stock,
)
```

### 期貨買進（市價 IOC）

```python
order = Order(
    buy_sell      = BSAction.Buy,
    symbol        = "TXF",
    price         = None,
    quantity      = 1,                          # 1 口
    market_type   = MarketType.Common,
    price_type    = PriceType.Market,
    time_in_force = TimeInForce.IOC,
    order_type    = OrderType.Stock,            # 期貨用 Stock（非 DayTrade）
)
result = sdk.futopt.place_order(account_futopt, order)
```

---

## 行情資料結構

### 股票現價查詢（WebSocket 訂閱）

```python
stock = sdk.marketdata.websocket_client.stock
stock.connect()

stock.subscribe({
    "channel": "trades",
    "symbol": "2330",
    "intradayOddLot": True,
})
```

### 取得個股報價（REST）

```python
ticker = sdk.marketdata.rest_client.stock.intraday.ticker(symbol='2330')
# ticker keys: symbol, name, price, change, changePercent,
#              bid, ask, volume, tickSize,
#              limitUpPrice, limitDownPrice,
#              canDayTrade, referencePrice, ...
```

---

*最後更新：2026-05-17（富邦 API v2.2.8）*
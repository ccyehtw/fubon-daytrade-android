# 富邦證券（當沖）/ 期貨條件下單介面 - Android App 規格書

```
文件版本：v1.0
建立日期：2026-05-17
適用平台：Android (minSdk 26 / targetSdk 34)
主要語言：Kotlin 1.9+
UI Framework：Jetpack Compose
後端通訊：富邦新一代 API v2.2.8 (Python SDK)
```

---

## 1. 整体架構

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android App                               │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────────────┐   │
│  │  登入頁     │ → │  主導航頁   │ → │  證券當沖交易頁      │   │
│  │  API Key   │   │  (TabBar)   │   │  FuturesOrderPage   │   │
│  │  憑證上傳   │   │             │   │                     │   │
│  └─────────────┘   └─────────────┘   └─────────────────────┘   │
│                          │                                      │
│                    ┌─────┴─────┐                                 │
│                    │ BottomNav │                                 │
│                    │  • 當沖   │                                 │
│                    │  • 期貨   │                                 │
│                    │  • 設定   │                                 │
│                    └───────────┘                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
               ┌──────────────────────────────┐
               │     富邦新一代 API (Python)    │
               │  ┌────────────────────────┐  │
               │  │  FubonSDK.apikey_login │  │
               │  │  stock.place_order()   │  │
               │  │  futopt.place_order()  │  │
               │  │  WebSocket 即時行情    │  │
               │  │  條件單 (SmartOrder)  │  │
               │  └────────────────────────┘  │
               └──────────────────────────────┘
```

---

## 2. 頁面清單

| 頁面 | Route | 說明 |
|------|-------|------|
| 登入頁 | `/login` | API Key 輸入 + 憑證 P12 上傳 |
| 主導航頁 | `/main` | BottomNavigation，含當沖/期貨/設定 |
| **證券當日當沖交易頁** | `/main/daytrade` | 當日沖銷核心頁面 |
| **期貨條件下單頁** | `/main/futures` | 期貨交易條件單頁面 |
| 設定頁 | `/main/settings` | 停損預設值、報價偏好等 |

---

## 3. 登入頁 (`LoginScreen`)

### 3.1 輸入欄位

| 欄位 | 類型 | 驗證規則 | 說明 |
|------|------|---------|------|
| 身分證字號 | TextInput | `^[A-Z][0-9]{9}$` | 登入 ID（自然人憑證） |
| API Key | TextInput | 64 碼 HEX | 富邦核發的 API Key |
| 憑證檔案 | FilePicker | `.p12` 副檔名 | 富邦憑證（PKCS#12）|
| 憑證密碼 | TextInput | 非空 | 預設為**登入 ID**（文件說明）|

### 3.2 按鈕

| 按鈕 | 狀態 | 動作 |
|------|------|------|
| `登入` | Enabled when all fields valid | 呼叫 `sdk.apikey_login()` |
| `上傳憑證` | — | 開啟檔案選擇器 |

### 3.3 登入流程

```
1. 使用者輸入 API Key + 憑證
2. 點擊「登入」
3. 呼叫 FubonSDK.apikey_login(personal_id, api_key, cert_path)
   ⚠️ 注意：Python SDK 只能用位置參數，不能用關鍵字參數！
4. 解析回傳的 Account 陣列：
   - accounts.data[0].account_type == "futopt"  → 期貨帳號
   - accounts.data[0].account_type == "stock"   → 證券帳號
   - 若有歸戶，回傳多帳號（需遍历找 stock/futopt）
5. 登入成功 → 跳轉至 MainScreen
   登入失敗 → 顯示錯誤訊息
```

### 3.4 錯誤處理

| 錯誤 | 訊息 |
|------|------|
| API Key 無效 | 「API Key 無效，請確認後重新輸入」 |
| 憑證過期/錯誤 | 「憑證驗證失敗，請重新上傳」 |
| 網路連線失敗 | 「網路連線失敗，請檢查網路」 |
| 帳號類別錯誤 | 「此 API Key 未綁定證券帳戶，請聯繫富邦確認歸戶狀態」|

---

## 4. 證券當日當沖交易頁 (`DayTradeScreen`)

### 4.1 頁面標題

```
┌────────────────────────────────────────────┐
│  證券當日當沖交易頁面                       │
│  [帳號: 961P / 191392] [餘額: $XXX,XXX]   │
└────────────────────────────────────────────┘
```

### 4.2 警告系統

#### 4.2.1 Deadline 警告（13:00 後觸發）

| 條件 | 視覺效果 |
|------|---------|
| `currentTime >= 13:00` | 整體介面邊框變**紅色**（`#FF0000`） |
| `currentTime >= 13:20` | 頂部顯示 `⚠️ 收盤將近！請留意平倉時間` |

#### 4.2.2 漲跌停警告

| 條件 | 視覺效果 |
|------|---------|
| 股票達**漲停** | 整體介面邊框變**紅色**，現價顯示 `#FF0000` |
| 股票達**跌停** | 整體介面邊框變**紅色**，現價顯示 `#0000FF` |
| 現價 >= `limitUpPrice` | `isLimitUp = true` |
| 現價 <= `limitDownPrice` | `isLimitDown = true` |

取得漲跌停價的方式：
```python
ticker = sdk.marketdata.rest_client.stock.intraday.ticker(symbol='2330')
# ticker['limitUpPrice']   # 漲停價
# ticker['limitDownPrice'] # 跌停價
```

### 4.3 商品輸入區

```
┌────────────────────────────────────────────────────────┐
│  股票代碼    [ 2330___________ ] [🔍查詢]              │
│  ─────────────────────────────────────────            │
│  股名        台積電                                      │
│  即時現價    $2,270  ↑+15 (+0.66%)                     │
│  漲停價      $2,495  跌停價 $2,045                      │
│  ─────────────────────────────────────────            │
│  當日可當沖  ✅ 是                                        │
└────────────────────────────────────────────────────────┘
```

| 欄位 | 說明 | 鎖定條件 |
|------|------|---------|
| 股票代碼輸入框 | 可輸入數字，離開後自動查詢名稱/現價 | **有持倉時鎖定**（灰色唯讀）|
| 查詢按鈕 | 點擊查詢個股資訊 | — |
| 股名顯示 | 股票名稱 | — |
| 即時現價 | WebSocket 即時更新 | — |
| 漲跌停價 | 從 `intraday.ticker()` 取得 | — |
| 當日可當沖 | 顯示 `canDayTrade` | — |

**持倉鎖定邏輯：**
```python
# 若目前有當日沖銷持倉（未平倉）
if has_open_daytrade_position:
    stockInput.isEnabled = False  # 鎖定
    # 顯示提示：「已有當日持倉，平倉後才可更換商品」
```

### 4.4 建倉後損益顯示

```
┌────────────────────────────────────────────────────────┐
│  建倉後損益                                            │
│  ─────────────────────────────────────────            │
│  方向        [多頭 �_LONG] / [空頭 �_SHORT]             │
│  建倉成本    $2,280.50 (= 成交價 $2,270 × 1.005)        │
│  目前市值    $2,285.00                                  │
│  損益        +$4.50 (+0.20%)                           │
│  ─────────────────────────────────────────            │
│  註：多頭成本 = 成交價 × 1.005                         │
│      空頭成本 = 成交價 × 0.995                         │
└────────────────────────────────────────────────────────┘
```

| 欄位 | 計算公式 | 顯示 |
|------|---------|------|
| 方向 | 根據第一筆成交的 `buy_sell` 判斷 | `多頭` 或 `空頭` |
| 建倉成本（多頭）| `成交均價 × 1.005` | `$$$.$$` |
| 建倉成本（空頭）| `成交均價 × 0.995` | `$$$.$$` |
| 目前市值 | `currentPrice × quantity` | `$$$.$$` |
| 損益金額 | `目前市值 - 建倉成本`（多頭）/ `建倉成本 - 目前市值`（空頭）| `+$/-$` |
| 損益比例 | `損益金額 / 建倉成本 × 100%` | `+%/-%` |

### 4.5 自動停損設定

```
┌────────────────────────────────────────────────────────┐
│  自動停損                                               │
│  ─────────────────────────────────────────            │
│  停損 %    [____2____] %  （範圍：0.1 ~ 10）           │
│  停損價    $2,226.59 (= $2,270 × (1 - 2%))             │
│  ─────────────────────────────────────────            │
│  ⚠️ 停損設定不得為 0 或空白                              │
└────────────────────────────────────────────────────────┘
```

| 欄位 | 預設值 | 範圍 | 行為 |
|------|--------|------|------|
| 停損 % | `2` | `0.1 ~ 10` | 輸入時即時計算停損價 |
| 停損價 | 自動計算 | — | 當現價 <= 停損價時觸發 |

**驗證規則：**
- `stopLossPercent == 0` → 顯示錯誤：「停損不得為 0」
- `stopLossPercent < 0` → 顯示錯誤：「停損不得為負數」
- 停損價 = `costPrice × (1 - stopLossPercent / 100)`

### 4.6 13:20 自動平倉開關

```
┌────────────────────────────────────────────────────────┐
│  13:20 自動現價平倉   [ ON/OFF ]                      │
│  ─────────────────────────────────────────            │
│  當開關為 ON：                                         │
│    若收盤前（13:25 前）仍未手動平倉，                    │
│    系統將以「市場訂單」自動平倉所有當日部位                │
└────────────────────────────────────────────────────────┘
```

### 4.7 漲跌停自動平倉

```
┌────────────────────────────────────────────────────────┐
│  漲跌停自動平倉                                         │
│  ─────────────────────────────────────────            │
│  ☑️ 做多達漲停價自動平倉                                │
│  ☑️ 做空達跌停價自動平倉                                │
│  ─────────────────────────────────────────            │
│  提示：達當日最大獲利                                    │
└────────────────────────────────────────────────────────┘
```

| 條件 | 動作 |
|------|------|
| 多頭部位 AND 現價 >= `limitUpPrice` | 自動市價平倉 |
| 空頭部位 AND 現價 <= `limitDownPrice` | 自動市價平倉 |

### 4.8 買入條件設定區

```
┌────────────────────────────────────────────────────────┐
│  買入條件                                                │
│  ─────────────────────────────────────────            │
│  價格    [○即時現價  ●自訂價格 $________]              │
│  追蹤    [1檔] [2檔] [3檔] [4檔] [5檔]                 │
│           (1 檔 = 1 個tick = 1 個最小報價單位)          │
│  ─────────────────────────────────────────            │
│  [■■■■■■■■■■  執行買進  ■■■■■■■■■■]                  │
│  (按下後按鈕變為「取消買進」，成交後自動恢復)             │
└────────────────────────────────────────────────────────┘
```

| 欄位 | 類型 | 說明 |
|------|------|------|
| 即時現價 RadioButton | Radio | 選擇以現價追蹤 |
| 自訂價格 Input | NumberInput | 自行設定基準價格 |
| 追蹤檔位 Selector | SegmentedButton (1-5) | 選擇追蹤幾檔 |
| 執行買進 Button | ToggleButton | 啟動/取消 |

**「追蹤回檔」邏輯說明：**

富邦 API 的 `PriceType.LimitUp` / `PriceType.LimitDown` 以及報價系統皆以**最小報價單位（tick）** 為基礎。「回檔」的定義為「價格由設定的高點（基準價）拉回 N 個最小報價單位」。

> ⚠️ **重要**：「回檔」不是百分比回調，而是以**報價檔位（tick）** 計算。
> - 股票（如 2330） tick = 0.1（元）
> - 期貨（如 TXF） tick = 1（點）

| 追蹤檔位 | 買進觸發條件 | 說明 |
|---------|------------|------|
| 1 檔 | 現價 <= 基準價 - 1 tick | 現價跌破基準價 1 個最小報價單位 |
| 2 檔 | 現價 <= 基準價 - 2 tick | 現價跌破基準價 2 個最小報價單位 |
| 3 檔 | 現價 <= 基準價 - 3 tick | 現價跌破基準價 3 個最小報價單位 |
| 4 檔 | 現價 <= 基準價 - 4 tick | 現價跌破基準價 4 個最小報價單位 |
| 5 檔 | 現價 <= 基準價 - 5 tick | 現價跌破基準價 5 個最小報價單位 |

```python
# 實作時需要動態取得 tick size（最小報價單位）
ticker = sdk.marketdata.rest_client.stock.intraday.ticker(symbol='2330')
tick_size = ticker.get('tickSize', 0.1)  # 股票通常為 0.1

trigger_price = base_price - (track_level * tick_size)

# 當現價 <= trigger_price 時，以市價 IOC 立即下單
order = Order(
    buy_sell      = BSAction.Buy,
    symbol        = "2330",
    price         = None,                    # 市價單不設價格
    quantity      = 2000,
    market_type   = MarketType.Common,
    price_type    = PriceType.Market,         # 市價
    time_in_force = TimeInForce.IOC,          # 立即成交否則取消
    order_type    = OrderType.DayTrade,       # 當日沖銷
)
result = sdk.stock.place_order(account, order)
```

**取得 tick size（最小報價單位）的方法：**

```python
# 股票
stock_ticker = sdk.marketdata.rest_client.stock.intraday.ticker(symbol='2330')
# stock_ticker['tickSize']  # 最小報價單位

# 期貨
futures_ticker = sdk.marketdata.rest_client.futures.intraday.ticker(symbol='TXF')
# futures_ticker['tickSize']  # 期貨最小報價單位
```

### 4.9 賣出條件設定區

```
┌────────────────────────────────────────────────────────┐
│  賣出條件                                                │
│  ─────────────────────────────────────────            │
│  價格    [○即時現價  ●自訂價格 $________]              │
│  追蹤    [1檔] [2檔] [3檔] [4檔] [5檔]                 │
│           (1 檔 = 1 個tick = 1 個最小報價單位)          │
│  ─────────────────────────────────────────            │
│  [■■■■■■■■■■  執行賣出  ■■■■■■■■■■]                  │
│  (按下後按鈕變為「取消賣出」，成交後自動恢復)             │
└────────────────────────────────────────────────────────┘
```

**「追蹤」邏輯（高點回檔賣出）：**

| 追蹤檔位 | 賣出觸發條件 |
|---------|------------|
| 1 檔 | 現價 <= 最高價 - 1 tick |
| 2 檔 | 現價 <= 最高價 - 2 tick |
| 3 檔 | 現價 <= 最高價 - 3 tick |
| 4 檔 | 現價 <= 最高價 - 4 tick |
| 5 檔 | 現價 <= 最高價 - 5 tick |

**內部維護一個 `highestPriceSinceEntry` 變數，持續追蹤進場後的最高價。**

### 4.10 當沖下單 API 呼叫

```python
from fubon_neo.sdk import Order
from fubon_neo.constant import (
    BSAction,        # Buy / Sell
    OrderType,       # Stock / DayTrade
    PriceType,       # Limit / Market / Reference
    TimeInForce,     # ROD / IOC / FOK
    MarketType       # Common
)

# 當沖買進
order = Order(
    buy_sell      = BSAction.Buy,
    symbol        = "2330",
    price         = str(market_price),  # 即時現價
    quantity      = 2000,                # 2 張
    market_type   = MarketType.Common,
    price_type    = PriceType.Market,   # 市價單
    time_in_force = TimeInForce.IOC,     # 當日沖銷用 IOC
    order_type    = OrderType.DayTrade,  # ← 關鍵：當沖
)

result = sdk.stock.place_order(account_stock, order)
```

### 4.11 WebSocket 即時行情

```python
# 初始化 WebSocket（Python 端處理）
sdk.init_realtime()
stock_ws = sdk.marketdata.websocket_client.stock

# 訂閱報價
def on_tick(data):
    # data 包含：symbol, price, bid, ask, volume, ...
    # 透過 HTTP / 輪詢 或 WebSocket 推送更新 Android UI
    emit_to_android('stock_tick', data)

stock_ws.subscribe('2330')
stock_ws.connect()
```

**Android 端接收：**
```kotlin
// 透過 WebSocket Client 或 HTTP Polling 接收
socket.on('stock_tick') { data ->
    viewModel.updatePrice(data.price)
    viewModel.checkLimitUpDown(data)
}
```

---

## 5. 期貨交易條件下單頁 (`FuturesOrderScreen`)

### 5.1 頁面標題

```
┌────────────────────────────────────────────┐
│  期貨交易條件下單頁面                         │
│  [帳號: F026901 / 1247180] [餘額: $XXX,XXX] │
└────────────────────────────────────────────┘
```

### 5.2 保證金警告

```
┌────────────────────────────────────────────────────────┐
│  ⚠️ 保證金不足警告                                      │
│  剩餘保證金：$8,500 （低於 $10,000 警示線）              │
│  ─────────────────────────────────────────            │
│  當保證金 < $10,000 → 整體介面邊框變紅                  │
└────────────────────────────────────────────────────────┘
```

| 條件 | 視覺效果 |
|------|---------|
| `margin < 10,000` | 介面邊框變**紅色**，顯示警告 |
| `margin >= 10,000` | 正常顯示 |

**取得保證金方式：**
```python
fa = sdk.futopt_accounting
margin_data = fa.query_margin_equity(account_futopt)
# margin_data['margin'] 或類似欄位
```

### 5.3 商品輸入區

```
┌────────────────────────────────────────────────────────┐
│  期貨商品    [ TXF___________ ] [🔍查詢]               │
│  ─────────────────────────────────────────            │
│  商品名稱    台指近月                                   │
│  即時指數    23,150  ↑+50 (+0.22%)                    │
│  漲停價      24,307  跌停價 22,000                     │
└────────────────────────────────────────────────────────┘
```

| 欄位 | 說明 | 鎖定條件 |
|------|------|---------|
| 期貨代碼輸入框 | 可輸入 TXF、TE、FX 等 | **有持倉時鎖定** |
| 商品名稱 | 期貨名稱 | — |
| 即時指數 | WebSocket 即時更新 | — |
| 漲跌停價 | 從行情 API 取得 | — |

### 5.4 建倉後損益顯示

```
┌────────────────────────────────────────────────────────┐
│  建倉後損益                                            │
│  ─────────────────────────────────────────            │
│  方向        [多頭 �_LONG] / [空頭 �_SHORT]             │
│  建倉成本    23,222.95 (= 成交指數 × 1.003)            │
│  目前市值    23,250.00                                  │
│  損益        +$135.00 (+0.23%)                         │
│  ─────────────────────────────────────────            │
│  註：期貨多頭成本 = 成交指數 × 1.003                    │
│      期貨空頭成本 = 成交指數 × 0.997                    │
└────────────────────────────────────────────────────────┘
```

| 欄位 | 計算公式 |
|------|---------|
| 建倉成本（多頭）| `成交均價 × 1.003` |
| 建倉成本（空頭）| `成交均價 × 0.997` |

### 5.5 自動停損設定

```
┌────────────────────────────────────────────────────────┐
│  自動停損                                               │
│  ─────────────────────────────────────────            │
│  停損 %    [____2____] %  （範圍：0.1 ~ 10）           │
│  停損指數  22,750.55 (= 23,222.95 × (1 - 2%))         │
│  ─────────────────────────────────────────            │
│  ⚠️ 停損設定不得為 0 或空白                              │
└────────────────────────────────────────────────────────┘
```

### 5.6 買入/賣出條件設定區

與證券當沖頁相同邏輯，但適用於期貨商品：

```
┌────────────────────────────────────────────────────────┐
│  買入條件                                                │
│  價格    [○即時指數  ●自訂指數 ________]               │
│  追蹤    [1檔] [2檔] [3檔] [4檔] [5檔]                 │
│  [■■■■■■■■■■  執行買入  ■■■■■■■■■■]                  │
│                                                         │
│  賣出條件                                                │
│  價格    [○即時指數  ●自訂指數 ________]               │
│  追蹤    [1檔] [2檔] [3檔] [4檔] [5檔]                 │
│  [■■■■■■■■■■  執行賣出  ■■■■■■■■■■]                  │
└────────────────────────────────────────────────────────┘
```

**期貨 tick size：** 期貨的 tick 通常為 1 點（TXF）、0.5 點（金融期）等。

### 5.7 期貨下單 API 呼叫

```python
# 期貨買進
order = Order(
    buy_sell      = BSAction.Buy,
    symbol        = "TXF",          # 期貨商品代碼
    price         = str(current_index),
    quantity      = 1,              # 1 口
    market_type   = MarketType.Common,
    price_type    = PriceType.Market,
    time_in_force = TimeInForce.IOC,
    order_type    = OrderType.Stock,  # 期貨用 Stock（非 DayTrade）
)

result = sdk.futopt.place_order(account_futopt, order)
```

---

## 6. 通用元件

### 6.1 狀態燈號

```
● 綠色（CONNECTED）  - WebSocket 已連線
● 黃色（RECONNECTING）- 連線中 / 重新連線
● 紅色（DISCONNECTED）- 連線中斷
```

### 6.2 訂單狀態顯示

| 狀態 | 顏色 | 說明 |
|------|------|------|
| `待成交` | 灰色 | 委託已送出 |
| `部分成交` | 藍色 | 部分成交 |
| `已成交` | 綠色 | 全部成交 |
| `已取消` | 黃色 | 手動取消 |
| `失敗` | 紅色 | 委託失敗 |

### 6.3 輸入驗證攔截器

```kotlin
// 所有數字輸入攔截
.onValueChange { newValue ->
    // 只允許數字和小數點
    val filtered = newValue.replace(Regex("[^0-9.]"), "")
    // 防止多個小數點
    val parts = filtered.split(".")
    if (parts.size > 2) return@onValueChange oldValue
    // 防止超過合理範圍
    val num = filtered.toDoubleOrNull() ?: 0.0
    if (num > maxAllowed) return@onValueChange oldValue
    filtered
}
```

---

## 7. 顏色主題

### 7.1 正常狀態

| 用途 | 顏色 | Hex |
|------|------|-----|
| 主色 | 深藍 | `#1565C0` |
| 次要色 | 淺藍 | `#42A5F5` |
| 背景 | 白/深灰 | `#FFFFFF` / `#121212` |
| 文字 | 深灰/白 | `#212121` / `#FFFFFF` |
| 漲（紅） | 紅 | `#D32F2F` |
| 跌（綠） | 綠 | `#388E3C` |

### 7.2 警告狀態（Deadline / 漲跌停 / 保證金不足）

| 用途 | 顏色 | Hex |
|------|------|-----|
| 警告邊框 | 紅 | `#FF0000` |
| 警告背景 | 淡紅 | `#FFEBEE` |
| 漲停價 | 紅 | `#D32F2F` |
| 跌停價 | 藍 | `#1976D2` |

---

## 8. 導航結構

```
BottomNavigation
├── 當沖 Tab        → DayTradeScreen
├── 期貨 Tab        → FuturesOrderScreen
└── 設定 Tab        → SettingsScreen

TopAppBar
├── 登入頁          → LoginScreen
└── 主頁（含 BottomNav）→ MainScreen
```

---

## 9. 依賴技術棧

| 層 | 技術 |
|----|------|
| 語言 | Kotlin 1.9 |
| UI | Jetpack Compose (Material3) |
| 架構 | MVVM + Clean Architecture |
| DI | Hilt |
| 網路 | Retrofit + OkHttp（REST）<br>OkHttp WebSocket（行情） |
| 非同步 | Kotlin Coroutines + Flow |
| 本地存儲 | DataStore Preferences |
| IPC 到 Python | ProcessBuilder（呼叫 Python 脚本）<br>或<br>gRPC / Unix Socket（效能考量） |

---

## 10. 與 Python SDK 的 IPC 方案

由於富邦 API SDK 為 Python 版本，Android App 需要透過以下方式溝通：

### 方案 A：Local HTTP Server（推薦，簡單）

```
Android App (Kotlin)
      ↓ HTTP POST/GET
Python Flask/FastAPI Server (localhost:8080)
      ↓
FubonSDK (Python)
```

```python
# server.py (運行於手機/本機)
from flask import Flask, request, jsonify
from fubon_neo.sdk import FubonSDK

app = Flask(__name__)
sdk = FubonSDK()

@app.route('/api/login', methods=['POST'])
def login():
    data = request.json
    result = sdk.apikey_login(data['personal_id'], data['api_key'], data['cert_path'])
    return jsonify({'success': result.is_success, 'accounts': [...]})

@app.route('/api/place_order', methods=['POST'])
def place_order():
    # 下單邏輯
    pass

@app.route('/api/subscribe_price', methods=['POST'])
def subscribe_price():
    # 訂閱 WebSocket 報價
    pass
```

### 方案 B：Python 子程序 + stdin/stdout JSON-RPC

```
Android → ProcessBuilder → python3 fubon_bridge.py → FubonSDK
                          ↑ stdin (JSON commands)
                          ↓ stdout (JSON responses)
```

---

## 11. 待確認事項（已從富邦 API 文件驗證）

| # | 問題 | 驗證結果 | 重要性 |
|---|------|---------|--------|
| 1 | 證券當日沖銷資格：需確認帳號 961P 是否已申請當日沖銷功能 | ⏳ 使用者自行向富邦確認，若無法登入會收到明確錯誤訊息 | ⚠️ 必要（登入時驗證）|
| 2 | API Key 是否已同時綁定「證券」與「期貨」帳號？（目前僅期貨）| ⏳ 使用者自行向富邦確認，登入失敗會回傳 `account_type` 錯誤訊息 | ⚠️ 必要（登入時驗證）|
| 3 | **「回檔」的富邦官方定義**：是否為「固定 tick 數」而非「百分比」？ | ✅ **已驗證**：富邦 API 使用**最小報價單位（tick）** 計算報價，`追蹤 1~5 檔` 即為「現價由設定基準回調 N 個最小報價單位後觸發」。`PriceType.LimitUp` / `PriceType.LimitDown` = 漲停價/跌停價 | ✅ 已確認 |
| 4 | **13:20 自動平倉**：是否可由 API 市價單達成？ | ✅ **已驗證**：`PriceType.Market`（市價單）+ `TimeInForce.IOC`（立即成交否則取消）可實現「收盤前自動市價平倉」；`sdk.stock.place_order(acc, order)` 支援 ROD/IOC/FOK 三種委託方式 | ✅ 已確認 |

### 11.1 驗證細節

**PriceType（市價別）：**
```
PriceType.Limit       # 限價
PriceType.Market       # 市價
PriceType.Reference    # 參考價
PriceType.LimitUp      # 漲停價 ← 可用於「達漲停價平倉」
PriceType.LimitDown    # 跌停價 ← 可用於「達跌停價平倉」
```

**TimeInForce（委託時效）：**
```
TimeInForce.ROD  # 當日委託（Rest of Day）
TimeInForce.IOC  # 立即成交否則取消 ← 13:20 自動平倉適用
TimeInForce.FOK  # 全部成交否則取消
```

**OrderType（委託類別）：**
```
OrderType.Stock     # 現股
OrderType.DayTrade  # 當日沖銷 ← 證券當沖用
OrderType.Margin    # 融資
OrderType.SBL       # 融券
OrderType.Short     # 借券
```

**MarketType（市場別）：**
```
MarketType.Common       # 普通股（整股）
MarketType.IntradayOdd  # 盤中零股
MarketType.Odd          # 盤後零股
```

---

## 12. 實作檢查清單

### Phase 1：基礎框架
- [ ] 專案建立（Compose + Hilt + MVVM）
- [ ] 登入頁 UI + 驗證
- [ ] 與 Python Bridge 的 HTTP 通訊
- [ ] 登入流程串接

### Phase 2：當沖頁
- [ ] 商品輸入 + 名稱查詢
- [ ] WebSocket 即時報價顯示
- [ ] 漲跌停判斷 + 警告 UI
- [ ] 13:00 Deadline 警告
- [ ] 當沖下單（`OrderType.DayTrade`）
- [ ] 持倉鎖定邏輯
- [ ] 買入條件追蹤機制（1-5 檔）
- [ ] 賣出條件追蹤機制（1-5 檔）
- [ ] 移動停損 v3.1
- [ ] 13:20 自動平倉
- [ ] 漲跌停自動平倉

### Phase 3：期貨頁
- [ ] 期貨商品輸入
- [ ] 保證金顯示 + 警告
- [ ] 期貨下單（`sdk.futopt.place_order`）
- [ ] 其餘同當沖頁邏輯

### Phase 4：穩定性
- [ ] WebSocket 斷線重連
- [ ] 網路異常處理
- [ ] 訂單狀態回調處理
- [ ] 背景服務（收盤前提醒）

---

*規格文件結束*

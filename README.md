# 富邦當日沖銷 / 期貨條件下單 Android App

![License](https://img.shields.io/badge/License-MIT-blue)
![Platform](https://img.shields.io/badge/Platform-Android-green)
![Language](https://img.shields.io/badge/Language-Kotlin-purple)

富邦證券當日沖銷現股當日沖 + 期貨條件單 Android 應用程式，採用富邦 Neo API (v2.2.8) Python SDK 作為後端核心。

---

## 📁 專案結構

```
fubon-daytrade-android/
├── app/                          # Android App (Kotlin + Jetpack Compose)
│   └── src/main/java/com/fubon/daytrade/
│       ├── MainActivity.kt
│       ├── data/
│       │   ├── model/Models.kt    # AccountInfo, StockOrder, Position
│       │   └── repository/        # FubonRepository, TradeRepository
│       ├── domain/
│       │   ├── model/Models.kt   # Domain models
│       │   └── repository/        # Repository interfaces
│       ├── di/
│       │   └── RepositoryModule.kt # Hilt DI
│       └── ui/
│           ├── components/        # QuoteCard, OrderPanel
│           ├── navigation/        # FubonNavHost, Screen routes
│           ├── screens/           # Login, DayTrade, Futures, Quote, Settings
│           ├── theme/            # Material3 color/theme
│           └── viewmodel/         # LoginViewModel, DayTradeViewModel
├── python/                        # Python Service (FastAPI + Fubon SDK)
│   ├── service.py                 # FastAPI main + HTTP endpoints
│   ├── fubon_client.py           # FubonClient SDK wrapper
│   ├── daytrade_service.py       # 當日沖 service (auto-square, P&L)
│   ├── futures_quote.py          # 期貨報價 service
│   ├── futures_order.py          # 期貨下單 service
│   ├── condition_engine.py       # 條件單觸發引擎
│   └── requirements.txt
├── docs/
│   ├── ARCHITECTURE.md           # 系統架構圖
│   ├── SEQUENCE.md               # 時序圖
│   ├── TECH_STACK.md             # 技術選型
│   └── API_ENUM_VALUES.md        # 富邦 API Enum 常數對照表
└── SPEC.md                       # 完整規格文件
```

---

## 🚀 快速開始

### 前置需求
- Android Studio Hedgehog (2023.1.1)+
- Python 3.11+
- 富邦 Neo API 帳號（個人 ID + API Key + 憑證）

### 安裝

```bash
# 1. Clone Repo
git clone https://github.com/ccyehtw/fubon-daytrade-android.git
cd fubon-daytrade-android

# 2. 安裝 Python 依賴
pip install -r python/requirements.txt

# 3. 設定憑證
cp python/.env.example python/.env
# 編輯 python/.env 填入：
#   PERSONAL_ID=你的身分證
#   API_KEY=你的API Key
#   CERT_PATH=/path/to/cert.p12

# 4. 啟動 Python Service
cd python && uvicorn service:app --host 0.0.0.0 --port 8080

# 5. 在 Android Studio 開啟 app/ 並執行
```

---

## 📦 功能进度

| Phase | 內容 | 狀態 |
|-------|------|------|
| **Phase 0** | Architect — 規格審閱、系統架構、時序圖、技術選型 | ✅ 完成 |
| **Phase 1** | 基礎框架 — App骨架、Python Service、Fubon SDK 登入 | ✅ 完成 |
| **Phase 2** | 證券當日當沖 — 報價頁、當沖下單、13:20自動平倉 | ✅ 完成 |
| **Phase 3** | 期貨條件單 — 期貨報價/下單/條件單引擎 | ✅ 完成 |
| **Phase 4** | 穩定性與後台服務 | 🚧 待實作 |

---

## 🔑 富邦 API 設定

```python
# 登入方式（請使用您的個金的身分證字號）
Personal ID : N123127908   # 個人身分證字號
API Key     : 0586E47E...  # 38位Hex API Key
Cert Pass   : Nn047556210   # 憑證密碼
```

**帳號資訊：**
- 證券：961P / 20125（當日沖銷資格）
- 期貨：1247180（futopt/15901）

---

## 🏗️ 系統架構

```
┌─────────────────────────────────────────────┐
│  Android App (Kotlin/Compose)              │
│  - LoginScreen, DayTradeScreen, FuturesScreen│
│  - BottomNavigation + MVVM + StateFlow       │
├─────────────────────────────────────────────┤
│  Python Service (FastAPI/uvicorn)           │
│  - FubonClient: login/place_order/cancel     │
│  - DayTradeService: auto_square/P&L         │
│  - FuturesQuoteService, FuturesOrderService │
│  - ConditionEngine: 條件單觸發               │
├─────────────────────────────────────────────┤
│  富邦 Neo API (Python SDK v2.2.8)           │
│  - 證券：現貨當日沖銷（13:20平倉）             │
│  - 期貨：條件單（觸價/停損/OCO）              │
└─────────────────────────────────────────────┘
```

---

## 📡 API 端點（Python Service）

### 登入與帳號
| Method | Endpoint | 說明 |
|--------|----------|------|
| POST | `/login` | 登入富邦帳號 |
| GET | `/account` | 取得帳號資訊 |

### 當日沖（證券）
| Method | Endpoint | 說明 |
|--------|----------|------|
| POST | `/stock/quote` | 查詢個股報價 |
| POST | `/stock/order` | 股票下單 |
| GET | `/stock/positions` | 當日沖持倉 |
| POST | `/stock/cancel` | 取消委託 |

### 期貨
| Method | Endpoint | 說明 |
|--------|----------|------|
| POST | `/futures/quote` | 期貨報價查詢 |
| POST | `/futures/order` | 期貨下單（市價/限價） |
| POST | `/futures/cancel` | 取消期貨委託 |
| GET | `/futures/positions` | 期貨持倉 |

### 條件單
| Method | Endpoint | 說明 |
|--------|----------|------|
| POST | `/condition/order` | 新增條件單 |
| GET | `/condition/orders` | 查詢所有條件單 |
| DELETE | `/condition/order/{id}` | 刪除條件單 |
| POST | `/condition/evaluate` | 報價推送時呼叫（評估條件）|

---

## 📊 當日沖核心邏輯

### 自動平倉時間
- **證券**：13:20（現股當日沖）
- **期貨**：13:30

### 條件單觸發類型
| 類型 | 說明 |
|------|------|
| `above` | 最新價 ≥ 觸發價 |
| `below` | 最新價 ≤ 觸發價 |
| `change_up` | 漲幅 ≥ 1%（對比昨收） |
| `change_down` | 跌幅 ≥ 1% |
| `volume` | 成交量 ≥ 設定量 |

---

## 👥 團隊分工（Agent 角色）

| Agent | 分支 | 主要產出 |
|-------|------|---------|
| Architect Agent | `agent/architect/phase0` | ARCHITECTURE.md, SEQUENCE.md, TECH_STACK.md |
| Android Developer | `agent/android/phase1` + `phase2` | Kotlin/Compose App |
| Python Developer | `agent/python/phase1` + `phase2` | FastAPI + Fubon SDK |

---

## 📄 License

MIT License
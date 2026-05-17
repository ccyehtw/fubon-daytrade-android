# Technology Stack — 富邦當日沖銷 App

---

## Android App (Kotlin)

### Language & Build
| Item | Choice | Version | Reason |
|------|--------|---------|--------|
| Language | Kotlin | 1.9+ | First-class Android language |
| Min SDK | 26 | Android 8.0 | Wide market coverage |
| Target SDK | 34 | Android 14 | Latest stable |
| Build Tool | Gradle (Kotlin DSL) | 8.x | Type-safe build scripts |

### UI Framework
| Item | Choice | Reason |
|------|--------|--------|
| UI Framework | Jetpack Compose | Declarative, modern Android UI |
| Design System | Material3 | Fubon branding + accessibility |
| Navigation | Compose Navigation | Type-safe routing |

### Architecture
| Item | Choice | Reason |
|------|--------|--------|
| Pattern | MVVM + Clean Architecture | Separation of concerns |
| DI | Hilt | Official Android DI, compile-time validation |
| Async | Kotlin Coroutines + Flow | Reactive, cancellation-friendly |
| State | StateFlow / MutableStateFlow | Lifecycle-aware state |

### Networking
| Item | Choice | Reason |
|------|--------|--------|
| HTTP Client | Retrofit + OkHttp | Type-safe REST, interceptors |
| WebSocket | OkHttp WebSocket | Unified client for HTTP + WS |
| JSON | Kotlinx Serialization | Kotlin-native, faster than Gson |

### Local Storage
| Item | Choice | Reason |
|------|--------|--------|
| Preferences | DataStore Preferences | Modern, Coroutine-based |
| Session | EncryptedSharedPreferences | Secure API key / token storage |

---

## Python SDK Service

### Runtime & Framework
| Item | Choice | Version | Reason |
|------|--------|---------|--------|
| Runtime | Python 3.10+ | 3.10+ | FubonSDK requirement |
| Web Framework | Flask (or FastAPI) | 3.x | Lightweight, easy HTTP endpoint |
| WS Framework | flask-socketio or FastAPI WebSocket | — | Real-time price push |

### Fubon SDK
| Item | Choice | Reason |
|------|--------|--------|
| SDK | fubon_neo.sdk | Official Python SDK v2.2.8 |
| API Version | v2.2.8 | Spec-defined |
| Auth | API Key + P12 Certificate |富邦新一代 API 認證方式 |

### Key Python Dependencies
```
fubon_neo >= 2.2.8
flask >= 3.0
flask-socketio >= 5.0
python-dotenv >= 1.0
```

---

## IPC (Android ↔ Python)

### Primary: Local HTTP Server (方案 A)

| Component | Technology | Detail |
|-----------|------------|--------|
| Transport | HTTP POST/GET | localhost:8080 |
| Protocol | JSON-RPC style | `{ "action": "...", "params": {...} }` |
| Real-time | WebSocket | OkHttp WS → Python → Fubon WS |
| Android HTTP | Retrofit | Type-safe, interceptor support |

### API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/login` | API Key 登入 |
| `GET` | `/api/account` | 取得帳號資訊 |
| `GET` | `/api/stock/ticker?symbol=` | 查詢個股報價 |
| `GET` | `/api/futures/ticker?symbol=` | 查詢期貨報價 |
| `POST` | `/api/stock/place_order` | 證券下單 |
| `POST` | `/api/futures/place_order` | 期貨下單 |
| `POST` | `/api/subscribe_price` | 訂閱 WebSocket 報價 |
| `GET` | `/api/margin` | 期貨保證金查詢 |

---

## Fubon Open API v2.2.8

### Trading APIs
| API | Method | Purpose |
|-----|--------|---------|
| Login | `sdk.apikey_login()` | 身份驗證 |
| Stock Order | `sdk.stock.place_order()` | 證券/當沖下單 |
| Futures Order | `sdk.futopt.place_order()` | 期貨下單 |
| Cancel | `sdk.cancel_order()` | 取消委託 |

### Market Data APIs
| API | Method | Purpose |
|-----|--------|---------|
| Intraday Ticker | `sdk.marketdata.rest_client.stock.intraday.ticker()` | 取得報價 + 漲跌停 |
| WebSocket | `sdk.marketdata.websocket_client` | 即時報價串流 |

### Order Constants (from `fubon_neo.constant`)
| Constant | Values | Usage |
|----------|--------|-------|
| `BSAction` | `Buy`, `Sell` | 買賣方向 |
| `OrderType` | `Stock`, `DayTrade`, `Margin`, `SBL` | 委託類別 |
| `PriceType` | `Limit`, `Market`, `LimitUp`, `LimitDown` | 價格類型 |
| `TimeInForce` | `ROD`, `IOC`, `FOK` | 委託時效 |
| `MarketType` | `Common`, `IntradayOdd`, `Odd` | 市場別 |

---

## DevOps / Tooling

| Category | Tool | Purpose |
|----------|------|---------|
| Version Control | Git + GitHub | Source control, PR workflow |
| CI/CD | GitHub Actions | Build + test automation |
| Issue Tracking | GitHub Issues | Task management |
| Documentation | Markdown | Spec, ADR, architecture docs |

---

## Color Palette (from SPEC)

| Token | Hex | Usage |
|-------|-----|-------|
| Primary | `#1565C0` | 深藍，按鈕/標題 |
| Secondary | `#42A5F5` | 淺藍，次要元素 |
| Price Up | `#D32F2F` | 漲（紅） |
| Price Down | `#388E3C` | 跌（綠） |
| Warning Border | `#FF0000` | Deadline / 漲跌停 / 保證金不足 |
| Warning Background | `#FFEBEE` | 警告區塊背景 |
| Limit Up Price | `#D32F2F` | 漲停價文字 |
| Limit Down Price | `#1976D2` | 跌停價文字 |

---

## Key Architecture Decisions

| # | Decision | Trade-off |
|---|----------|-----------|
| IPC-1 | **Local HTTP Server over stdin/stdout** | Simpler debugging, easier WebSocket proxy, production-ready path |
| SDK-1 | **Python SDK Service as bridge** | 富邦 only provides Python SDK; Android cannot call directly |
| UI-1 | **Jetpack Compose over XML** | Declarative UI, better state management with Flow, modern tooling |
| DI-1 | **Hilt over manual DI** | Compile-time validation, official standard, less boilerplate |
| State-1 | **StateFlow over LiveData** | Kotlin-native, better Coroutines integration, cancellation-safe |

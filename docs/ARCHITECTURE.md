# System Architecture — 富邦當日沖銷 App

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              Android App (Kotlin)                            │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │                         Presentation Layer                              │  │
│  │  ┌─────────────────┐  ┌──────────────────┐  ┌──────────────────────┐  │  │
│  │  │   LoginScreen    │  │   MainScreen     │  │  DayTradeScreen      │  │  │
│  │  │  (API Key + P12) │  │  (BottomNav)     │  │  FuturesOrderScreen  │  │  │
│  │  └────────┬─────────┘  └────────┬─────────┘  └──────────┬───────────┘  │  │
│  │           │                    │                        │              │  │
│  │  ┌────────┴────────────────────┴────────────────────────┴───────────┐  │  │
│  │  │                    ViewModel Layer (MVVM)                          │  │  │
│  │  │  LoginViewModel │ MainViewModel │ DayTradeViewModel │ FuturesVM    │  │  │
│  │  └─────────────────────────────────┬───────────────────────────────────┘  │  │
│  └────────────────────────────────────┼───────────────────────────────────────┘  │
│                                       │ Kotlin Coroutines + Flow              │
│  ┌────────────────────────────────────┼───────────────────────────────────────┐│
│  │                         Domain / Data Layer                               ││
│  │  ┌──────────────────┐  ┌─────────────┴──────────────┐  ┌───────────────┐ ││
│  │  │  FubonRepository │  │   IPC Bridge (HTTP Client)  │  │  DataStore    │ ││
│  │  │  (use cases)     │  │  Retrofit + OkHttp WS      │  │  Preferences  │ ││
│  │  └──────────────────┘  └─────────────┬──────────────┘  └───────────────┘ ││
│  └───────────────────────────────────────┼───────────────────────────────────┘│
└──────────────────────────────────────────┼────────────────────────────────────┘
                                           │ localhost:8080 (HTTP / WS)
┌──────────────────────────────────────────┼────────────────────────────────────┐
│                     Python SDK Service (Flask / FastAPI)                      │
│                                       │                                          │
│  ┌─────────────────────────────────────┴─────────────────────────────────────┐ │
│  │                           Bridge Layer (Python)                            │ │
│  │  ┌──────────────┐  ┌─────────────────┐  ┌────────────────────────────┐     │ │
│  │  │ /api/login   │  │ /api/place_order│  │ /api/subscribe_price       │     │ │
│  │  │ /api/account │  │ /api/cancel     │  │ /api/realtime (WebSocket)  │     │ │
│  │  └───────┬──────┘  └────────┬────────┘  └─────────────┬──────────────┘     │ │
│  │          │                  │                         │                    │ │
│  │  ┌───────┴──────────────────┴─────────────────────────┴────────────────┐  │ │
│  │  │                      FubonSDK Wrapper                                 │  │ │
│  │  │  • sdk.apikey_login()         • sdk.stock.place_order()              │  │ │
│  │  │  • sdk.futopt.place_order()   • sdk.marketdata.websocket_client      │  │ │
│  │  └───────────────────────────────┬──────────────────────────────────────┘  │ │
│  └───────────────────────────────────┼────────────────────────────────────────┘ │
└───────────────────────────────────────┼────────────────────────────────────────┘
                                        │ HTTPS / WSS
┌───────────────────────────────────────┼────────────────────────────────────────┐
│                            Fubon Open API v2.2.8                               │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────────────────────┐ │
│  │  Identity API  │  │  Trading API   │  │  Market Data API                  │ │
│  │  (登入/憑證)    │  │ (證券/期貨下單) │  │  (WebSocket 即時報價)            │ │
│  └────────────────┘  └────────────────┘  └────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────────┘
```

---

## Layer Responsibilities

| Layer | Component | Responsibility |
|-------|-----------|---------------|
| **Android UI** | LoginScreen / DayTradeScreen / FuturesOrderScreen | Compose UI, user input, state collection |
| **Android ViewModel** | ViewModels (Login, DayTrade, Futures) | UI state management, business logic orchestration |
| **Android Repository** | FubonRepository | Abstracts data sources, exposes Flow streams |
| **IPC Bridge** | Retrofit HTTP + OkHttp WebSocket | JSON-RPC over HTTP to Python service |
| **Python Service** | Flask/FastAPI endpoints | Wraps FubonSDK, manages sessions, WebSocket proxy |
| **FubonSDK** | fubon_neo.sdk | Official Python SDK — login, order, market data |
| **Fubon API** | Fubon Open API v2.2.8 | External trading & quote infrastructure |

---

## Data Flow

```
User Action
    ↓ (Compose UI event)
ViewModel (Kotlin Coroutines)
    ↓ (suspend function)
FubonRepository
    ↓ (HTTP POST / WebSocket)
Python SDK Service (localhost:8080)
    ↓ (FubonSDK call)
Fubon Open API
    ↓ (JSON response)
Python Service
    ↓ (HTTP response / WebSocket push)
Repository
    ↓ (Flow)
ViewModel (updates state)
    ↓ ( Compose recomposition)
UI Update
```

---

## Multi-Account Handling

- Login returns multiple accounts (stock + futopt) in `accounts.data[]`
- Android selects correct account by `account_type`: `"stock"` → 證券, `"futopt"` → 期貨
- Both accounts stored separately in `DataStore Preferences`

---

## IPC Choice: Local HTTP Server (方案 A)

Selected over ProcessBuilder + stdin/stdout for:
- **Simpler debugging** — standard HTTP tooling (curl, Postman)
- **Separation of concerns** — Android and Python are independently testable
- **WebSocket support** — native in OkHttp, proxied through Flask
- **Production path** — same interface works when service runs on a local server

Alternative (方案 B — JSON-RPC over stdin/stdout) reserved for embedded/isolated environments.

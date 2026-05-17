# 富邦證券（當日沖銷）/ 期貨條件下單 Android App

> 富邦新一代 API（v2.2.8）驅動的 Android 當沖與期貨交易應用程式

[![GitHub Issues](https://img.shields.io/github/issues/ccyehtw/fubon-daytrade-android)](https://github.com/ccyehtw/fubon-daytrade-android/issues)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## 📋 專案概述

本專案基於富邦新一代 API（Python SDK v2.2.8），以 Kotlin/Jetpack Compose 建構 Android 當日沖銷與期貨條件下單介面。

**核心功能：**
- 證券當日沖銷交易（`OrderType.DayTrade`）
- 期貨 / 選擇權條件下單（`sdk.futopt.place_order`）
- WebSocket 即時行情監控
- 移動停損（保守穩盈）、13:20 自動平倉、漲跌停自動平倉

---

## 📂 目錄結構

```
fubon-daytrade-android/
├── SPEC.md                          # 完整規格文件（v1.0）
├── README.md                        # 本檔案
├── docs/                            # 技術文件
│   └── API_ENUM_VALUES.md           # 富邦 API Enum 常數對照表
└── .github/
    └── workflows/                   # CI/CD（未來擴充）
```

---

## 🔑 富邦 API 常數對照

### OrderType（委託類別）

| 常數 | 說明 | 用途 |
|------|------|------|
| `OrderType.Stock` | 現股 | 一般現股買賣 |
| `OrderType.DayTrade` | 當日沖銷 | ⚡ 當日沖銷核心 |
| `OrderType.Margin` | 融資 | 信用交易 |
| `OrderType.SBL` | 融券 | 信用交易 |
| `OrderType.Short` | 借券 | 借券賣出 |

### PriceType（市價別）

| 常數 | 說明 | 用途 |
|------|------|------|
| `PriceType.Limit` | 限價 | 一般限價單 |
| `PriceType.Market` | 市價 | ⚡ 13:20 自動平倉用 |
| `PriceType.Reference` | 參考價 | 開盤參考價 |
| `PriceType.LimitUp` | 漲停價 | 漲停價平倉 |
| `PriceType.LimitDown` | 跌停價 | 跌停價平倉 |

### TimeInForce（委託時效）

| 常數 | 說明 | 用途 |
|------|------|------|
| `TimeInForce.ROD` | 當日委託（Rest of Day）| 一般掛單 |
| `TimeInForce.IOC` | 立即成交否則取消 | ⚡ 自動平倉用 |
| `TimeInForce.FOK` | 全部成交否則取消 | 全部成交條件單 |

### MarketType（市場別）

| 常數 | 說明 |
|------|------|
| `MarketType.Common` | 普通股（整股）|
| `MarketType.IntradayOdd` | 盤中零股 |
| `MarketType.Odd` | 盤後零股 |

### BSAction（買賣別）

| 常數 | 說明 |
|------|------|
| `BSAction.Buy` | 買進 |
| `BSAction.Sell` | 賣出 |

---

## 🗺️ 開發階段

| 階段 | 名稱 | Issue |
|------|------|-------|
| Phase 1 | 基礎框架建置 | [#1](https://github.com/ccyehtw/fubon-daytrade-android/issues/1) |
| Phase 2 | 證券當日當沖交易頁 | [#2](https://github.com/ccyehtw/fubon-daytrade-android/issues/2) |
| Phase 3 | 期貨交易條件下單頁 | [#3](https://github.com/ccyehtw/fubon-daytrade-android/issues/3) |
| Phase 4 | 穩定性與後台服務 | [#4](https://github.com/ccyehtw/fubon-daytrade-android/issues/4) |

---

## ⚙️ 技術棧

| 層 | 技術 |
|----|------|
| 語言 | Kotlin 1.9 |
| UI | Jetpack Compose (Material3) |
| 架構 | MVVM + Clean Architecture |
| DI | Hilt |
| 網路 | Retrofit + OkHttp（REST）<br>OkHttp WebSocket（行情） |
| 非同步 | Kotlin Coroutines + Flow |
| 本地存儲 | DataStore Preferences |
| 後端通訊 | Python HTTP Server Bridge → FubonSDK |

---

## 🔗 富邦 API 資源

- **富邦新─代 API 文件**：https://www.fbs.com.tw/TradeAPI/docs/
- **Python SDK 安裝**：`pip install fubon_neo`
- **Discord 社群**：富邦程式交易討論
- **聯絡窗口**：pm.trading.sec@fubon.com

---

## ⚠️ 重要提醒

1. **當日沖銷資格**：需向富邦證券单独申請，API Key 也需重新設定綁定證券帳戶
2. **帳號歸戶**：確認 API Key 已同時綁定「證券」與「期貨」帳號
3. **憑證密碼**：登入 ID（並非交易密碼）為憑證密碼
4. **風險提示**：本專案僅供技術研究，不構成投資建議

---

## 📄 授權

MIT License — 詳見 [LICENSE](LICENSE) 檔案。
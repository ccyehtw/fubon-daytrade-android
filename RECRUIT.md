# 招募開發團隊成員

本專案招募有興趣參與「富邦當日沖銷 / 期貨條件下單 Android App」開發的程式設計師。

---

## 🎯 專案背景

本專案使用 **富邦新一代 API（Python SDK v2.2.8）** 驅動，以 **Kotlin / Jetpack Compose** 建構 Android 端的當日沖銷與期貨條件下單介面。

規格文件：`SPEC.md`

---

## 👥 需求角色

| 角色 | 技術需求 | 工作內容 |
|------|---------|---------|
| **Android 開發者** | Kotlin / Jetpack Compose / MVVM | 建構 DayTradeScreen / FuturesOrderScreen |
| **Python  Backend** | Python / FubonSDK / Flask | HTTP Bridge Server、條件追蹤邏輯 |
| **Full-Stack（兩者兼顧）** | Kotlin + Python | 可獨立負責模組 |
| **技術顧問（可遠端）** | 有富邦 API 串接經驗 | 提供技術指導 |

---

## 📋 加入方式

### 一般貢獻者

1. **Fork 本專案**
   ```
   https://github.com/ccyehtw/fubon-daytrade-android/fork
   ```

2. **在 CONTRIBUTING.md 中報到**
   ```
   - 暱稱：
   - 擅長技術：
   - 想要參與的 Phase（1-4）：
   ```

3. **提交 Pull Request**
   - Branch 命名：`dev/your-name`
   - 參考 `CONTRIBUTING.md` 規範

### 核心開發者（可直接加入 Repo）

若具備以下任一條件，可申請成為具有寫入權限的核心開發者：

- 有 Android Jetpack Compose 開發經驗
- 有 Python Flask / FastAPI 後端開發經驗
- 有富邦 API 串接經驗
- 有金融交易系統開發經驗

**申請方式：**
在 [Issues](https://github.com/ccyehtw/fubon-daytrade-android/issues) 中回覆 Issue #99（志願加入），格式如下：

```
暱稱：
電子郵件：
GitHub 個人頁：
擅長技術棧：
預計貢獻內容：
可用時間（每週時數）：
```

---

## 📐 開發原則

1. **不提領程式碼**：本專案採貢獻式，需先 fork 再 PR，不直接推送到 main
2. **規格優先**：所有變更需先更新 SPEC.md 再實作
3. **Review 制度**：所有 PR 需經至少 1 人 review 才能合併
4. **安全第一**：嚴禁在程式碼中硬編碼 API Key / 憑證密碼
5. **當日沖銷**：所有實作必須符合台灣證券當日沖銷法規

---

## 📞 聯絡方式

- **Repo Issue**：https://github.com/ccyehtw/fubon-daytrade-android/issues
- **Repo 連結**：https://github.com/ccyehtw/fubon-daytrade-android

---

*最後更新：2026-05-17*
# 貢獻指南

感謝您對本專案的興趣！請在貢獻前仔細閱讀本指南。

---

## 🔀 開發流程

### 一般貢獻者（Fork 模式）

```
1. Fork this repo
         ↓
2. Create feature branch (dev/your-name/feature-name)
         ↓
3. Implement & test locally
         ↓
4. Submit Pull Request → 到 Issue 中回報
         ↓
5. Review → 合併到 main
```

### 核心開發者（直接推送）

```
1. 建立新 branch：`git checkout -b dev/your-name/feature`
         ↓
2. 實作功能
         ↓
3. 提交 PR → 等待 review
         ↓
4. 合併後刪除 branch
```

---

## 📁 程式碼規範

### Android（Kotlin）

- **命名**：使用 PascalCase（類/介面）/ camelCase（變數/函數）
- **架構**：MVVM + Clean Architecture
- **UI**：Jetpack Compose + Material3
- **DI**：Hilt（禁止手動 `new` 實例化）
- **非同步**：Kotlin Coroutines + Flow
- **格式化**：使用 `ktlint` 自動檢查

```kotlin
// ✅ 正確範例
@Composable
fun DayTradeScreen(
    viewModel: DayTradeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // ...
}

// ❌ 錯誤範例
val vm = DayTradeViewModel() // 禁止手動 new
```

### Python Backend

- **命名**：snake_case（變數/函數）/ PascalCase（類）
- **框架**：Flask / FastAPI（二選一）
- **格式化**：使用 `black` + `ruff` 檢查
- **類型標註**：所有公開函數需有 type hint

```python
# ✅ 正確範例
from typing import Optional

def calculate_stop_loss(entry_price: float, stop_pct: float = 2.0) -> Optional[float]:
    if entry_price is None or entry_price != entry_price:
        return None
    return entry_price * (1 - stop_pct / 100)

# ❌ 錯誤範例
def calc(e, p):  # 沒有 type hint
    return e * (1 - p/100)
```

---

## 📝 提交訊息格式

使用 Conventional Commits 格式：

```
<type>(<scope>): <subject>

[可選 body]

[可選 footer]
```

**Type 列表：**
| Type | 用途 |
|------|------|
| `feat` | 新功能 |
| `fix` | 錯誤修復 |
| `docs` | 文件更新 |
| `refactor` | 重構（無功能變化）|
| `test` | 測試相關 |
| `chore` | 建構/輔助工具 |

**範例：**
```
feat(daytrade): add WebSocket price subscription
fix(stopLoss): correct trailing stop calculation
docs(spec): update Phase 2 acceptance criteria
```

---

## 🐛 Bug 回報

請在 [Issues](https://github.com/ccyehtw/fubon-daytrade-android/issues) 中開立新 Issue，格式：

```markdown
## Bug 描述
[清楚描述問題]

## 重現步驟
1. 前往...
2. 點擊...
3. 發生錯誤

## 預期行為
[應該發生的情況]

## 截圖/Log
[如有相關截圖或錯誤 Log]

## 環境
- 手機型號：
- Android 版本：
- App 版本：
```

---

## 📐 SPEC.md 更新規則

1. **所有新功能實作前**：先更新 SPEC.md 的對應章節
2. **規格變更**：需在 PR 中說明為何需要此變更
3. **技术细节**：在 `docs/` 目錄下建立獨立文件引用

---

## 🚫 禁止事項

| 禁止行為 | 原因 |
|---------|------|
| 硬編碼 API Key / 密碼 | 安全風險 |
| 直接推送到 `main` | 破壞性變更 |
| 不寫測試就 PR | 品質無法保證 |
| 變更已公告的 API 合約 | 造成其他成員困擾 |

---

## ✅ PR 檢查清單（提交前自己檢查）

- [ ] 程式碼符合上述命名規範
- [ ] 有對應的單元測試
- [ ] SPEC.md 已更新（如有必要）
- [ ] commit message 符合 Conventional Commits
- [ ] 沒有硬編碼敏感資訊
- [ ] `ktlint` / `black` 格式檢查通過

---

*最後更新：2026-05-17*
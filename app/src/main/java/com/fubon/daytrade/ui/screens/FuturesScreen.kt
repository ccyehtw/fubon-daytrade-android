package com.fubon.daytrade.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fubon.daytrade.domain.model.BuySell
import com.fubon.daytrade.ui.theme.LimitDownBlue
import com.fubon.daytrade.ui.theme.LimitUpRed
import com.fubon.daytrade.ui.theme.StockDown
import com.fubon.daytrade.ui.theme.StockFlat
import com.fubon.daytrade.ui.theme.StockUp
import com.fubon.daytrade.ui.viewmodel.FuturesPosition
import com.fubon.daytrade.ui.viewmodel.FuturesViewModel

// ══════════════════════════════════════════════════════════════
// FuturesScreen — 期貨條件下單頁（雙模式完整實作）
// ══════════════════════════════════════════════════════════════

@Composable
fun FuturesScreen(
    viewModel: FuturesViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    // 初始化時檢查保證金與截止時間
    LaunchedEffect(Unit) {
        viewModel.checkMargin()
    }

    // 截止時間提醒
    LaunchedEffect(Unit) {
        viewModel.checkDeadline()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 帳號資訊（期貨帳號）──────────────────────────────
        item {
            FuturesAccountHeader(
                currentAccount = uiState.currentAccount,
                accounts = uiState.accounts,
                onSwitchAccount = { viewModel.switchAccount(it) }
            )
        }

        // ── 保證金資訊（無論是否取得都顯示狀態）────────────────
        item {
            MarginInfoCard(
                marginBalance = uiState.marginBalance,
                isWarning = uiState.marginWarning,
                isLoading = uiState.isQuoteLoading
            )
        }

        // ── 商品輸入區 ─────────────────────────────────────
        item {
            FuturesSymbolSearchSection(
                symbol = uiState.quoteSymbol,
                isLoading = uiState.isQuoteLoading,
                onSymbolChange = { viewModel.updateQuoteSymbol(it) },
                onSearch = {
                    viewModel.quoteFutures(uiState.quoteSymbol)
                    focusManager.clearFocus()
                }
            )
        }

        // ── 期貨報價顯示 ───────────────────────────────────
        uiState.currentQuote?.let { tick ->
            item {
                FuturesQuoteCard(
                    tick = tick,
                    isWarning = uiState.marginWarning
                )
            }

            // ── 雙模式進場設定區 ─────────────────────────────
            item {
                FuturesOrderPanel(
                    tick = tick,
                    isLoading = uiState.isOrderLoading,
                    onBreakdownBuy = { price, qty, stopLossPct, trackLevels ->
                        viewModel.placeBreakdownBuy(
                            symbol = tick.symbol,
                            price = price,
                            quantity = qty,
                            stopLossPct = stopLossPct,
                            trackLevels = trackLevels
                        )
                    },
                    onBreakoutSell = { price, qty, stopLossPct, trackLevels ->
                        viewModel.placeBreakoutSell(
                            symbol = tick.symbol,
                            price = price,
                            quantity = qty,
                            stopLossPct = stopLossPct,
                            trackLevels = trackLevels
                        )
                    }
                )
            }
        }

        // ── 訂單訊息 ───────────────────────────────────────
        uiState.orderMessage?.let { message ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // ── 錯誤訊息 ───────────────────────────────────────
        uiState.errorMessage?.let { error ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("關閉")
                        }
                    }
                }
            }
        }

        // ── 期貨持倉列表 ───────────────────────────────────
        item {
            Text(
                text = "期貨持倉",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (uiState.futuresPositions.isEmpty()) {
            item {
                EmptyFuturesPositionsCard()
            }
        } else {
            items(uiState.futuresPositions) { position ->
                FuturesPositionCard(
                    position = position,
                    isLoading = uiState.isOrderLoading,
                    onClose = { viewModel.closeFuturesPosition(position) }
                )
            }
        }

        // ── 持倉總計 ───────────────────────────────────────
        if (uiState.futuresPositions.isNotEmpty()) {
            item {
                FuturesSummaryCard(positions = uiState.futuresPositions)
            }
        }

        // ── 截止提醒 ───────────────────────────────────────
        item {
            DeadlineReminder()
        }

        // Bottom spacing
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 組件：帳號 header
// ══════════════════════════════════════════════════════════════

@Composable
private fun FuturesAccountHeader(
    currentAccount: com.fubon.daytrade.data.model.AccountInfo?,
    accounts: List<com.fubon.daytrade.data.model.AccountInfo>,
    onSwitchAccount: (com.fubon.daytrade.data.model.AccountInfo) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "期貨帳號",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = currentAccount?.displayName ?: "未登入",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    currentAccount?.let {
                        Text(
                            text = "帳號: ${it.accountId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            if (accounts.size > 1) {
                TextButton(onClick = { /* TODO: show account selector */ }) {
                    Text("切換")
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 組件：保證金資訊卡（整合警告 / 正常 / 載入中 三種狀態）
// ══════════════════════════════════════════════════════════════

@Composable
private fun MarginInfoCard(
    marginBalance: Double?,
    isWarning: Boolean,
    isLoading: Boolean
) {
    when {
        isLoading -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "載入保證金資訊...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        marginBalance == null -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "保證金資訊：尚未取得（請先選擇帳號）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        isWarning -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, LimitUpRed, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = LimitUpRed.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = LimitUpRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "⚠️ 保證金不足警告",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = LimitUpRed
                        )
                        Text(
                            text = "剩餘保證金: $${String.format("%,.0f", marginBalance)} （低於 $10,000 警示線）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LimitUpRed.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
        else -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(StockUp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "保證金餘額: $${String.format("%,.0f", marginBalance)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "狀態正常",
                        style = MaterialTheme.typography.labelSmall,
                        color = StockUp
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 組件：商品輸入
// ══════════════════════════════════════════════════════════════

@Composable
private fun FuturesSymbolSearchSection(
    symbol: String,
    isLoading: Boolean,
    onSymbolChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = symbol,
            onValueChange = onSymbolChange,
            label = { Text("期貨代碼") },
            placeholder = { Text("例如: TXF, TE") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = "查詢")
                    }
                }
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 組件：期貨報價卡
// ══════════════════════════════════════════════════════════════

@Composable
private fun FuturesQuoteCard(
    tick: com.fubon.daytrade.ui.viewmodel.FuturesTick,
    isWarning: Boolean
) {
    val borderModifier = if (isWarning) {
        Modifier.border(2.dp, LimitUpRed, RoundedCornerShape(12.dp))
    } else {
        Modifier
    }

    Card(
        modifier = Modifier.fillMaxWidth().then(borderModifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = tick.symbol,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "台指近月",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format("%,.0f", tick.lastPrice),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            tick.change > 0 -> LimitUpRed
                            tick.change < 0 -> LimitDownBlue
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Row {
                        Text(
                            text = "${if (tick.change >= 0) "+" else ""}${String.format("%.2f", tick.change)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                tick.change >= 0 -> LimitUpRed
                                else -> LimitDownBlue
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "(${if (tick.changePercent >= 0) "+" else ""}${String.format("%.2f", tick.changePercent)}%)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                tick.changePercent >= 0 -> LimitUpRed
                                else -> LimitDownBlue
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 漲跌停價
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                tick.limitUpPrice?.let { up ->
                    Column {
                        Text(
                            text = "漲停",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format("%,.0f", up),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = LimitUpRed
                        )
                    }
                }
                tick.limitDownPrice?.let { down ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "跌停",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format("%,.0f", down),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = LimitDownBlue
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 組件：期貨下單面板（雙模式）
// ══════════════════════════════════════════════════════════════

@Composable
private fun FuturesOrderPanel(
    tick: com.fubon.daytrade.ui.viewmodel.FuturesTick,
    isLoading: Boolean,
    onBreakdownBuy: (price: Double?, qty: Int, stopLossPct: Double, trackLevels: Int) -> Unit,
    onBreakoutSell: (price: Double?, qty: Int, stopLossPct: Double, trackLevels: Int) -> Unit
) {
    var selectedMode by remember { mutableStateOf(0) }  // 0=breakdown_buy, 1=breakout_sell
    var quantityText by remember { mutableStateOf("1") }
    var stopLossText by remember { mutableStateOf("2.0") }
    var trackLevels by remember { mutableIntStateOf(1) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "期貨條件下單",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 進場模式選擇（breakdown_buy / breakout_sell）
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                SegmentedButton(
                    selected = selectedMode == 0,
                    onClick = { selectedMode = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text("追低點買入（做多）") }
                )
                SegmentedButton(
                    selected = selectedMode == 1,
                    onClick = { selectedMode = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text("追高點回檔（做空）") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 說明文字
            val modeDescription = if (selectedMode == 0) {
                "等低點出現後，價格反彈 N 檔市價買進 → 看對方向時高點附近賣出"
            } else {
                "等高點出現後，價格回檔 N 檔市價放空 → 看對方向時低點附近回補"
            }
            Text(
                text = modeDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 停損設定
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = stopLossText,
                    onValueChange = { stopLossText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("停損%") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    suffix = { Text("%") }
                )
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.filter { c -> c.isDigit() } },
                    label = { Text("口數") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 追蹤檔位選擇
            Text(
                text = "追蹤檔位（1=積極冒險，5=保守）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (1..5).forEach { level ->
                    FilterChip(
                        selected = trackLevels == level,
                        onClick = { trackLevels = level },
                        label = { Text("${level}檔") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 執行按鈕
            val isBreakdownBuy = selectedMode == 0
            val buttonText = if (isBreakdownBuy) "執行買入（追低點）" else "執行賣出（追高點）"
            val buttonColor = if (isBreakdownBuy) LimitUpRed else LimitDownBlue

            androidx.compose.material3.Button(
                onClick = {
                    val qty = quantityText.toIntOrNull() ?: 1
                    val stopLossPct = stopLossText.toDoubleOrNull() ?: 2.0
                    if (isBreakdownBuy) {
                        onBreakdownBuy(null, qty, stopLossPct, trackLevels)
                    } else {
                        onBreakoutSell(null, qty, stopLossPct, trackLevels)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && quantityText.isNotEmpty(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = buttonColor
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (isLoading) "處理中..." else buttonText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 組件：期貨持倉卡
// ══════════════════════════════════════════════════════════════

@Composable
private fun FuturesPositionCard(
    position: FuturesPosition,
    isLoading: Boolean = false,
    onClose: () -> Unit
) {
    val directionColor = if (position.direction == BuySell.Buy) LimitUpRed else LimitDownBlue
    val directionText = if (position.direction == BuySell.Buy) "多" else "空"
    val pnlColor = when {
        position.unrealizedPnL > 0 -> StockUp
        position.unrealizedPnL < 0 -> StockDown
        else -> StockFlat
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = position.symbol,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(directionColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = directionText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = directionColor
                        )
                    }
                }
                Text(
                    text = "${position.quantity} 口",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 均價 / 現價 / 損益
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "均價",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format("%,.0f", position.avgPrice),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "現價",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format("%,.0f", position.currentPrice),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "未實現損益",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format("%+,.0f", position.unrealizedPnL),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 現價偏離建倉價警示
            DeviationWarningTag(position = position)

            // 平倉按鈕
            androidx.compose.material3.Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = directionColor
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isLoading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "平倉",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 組件：現價偏離建倉價警示標籤
// ══════════════════════════════════════════════════════════════

@Composable
private fun DeviationWarningTag(position: FuturesPosition) {
    if (position.avgPrice <= 0 || position.currentPrice <= 0) return

    val deviationPct = ((position.currentPrice - position.avgPrice) / position.avgPrice) * 100

    // 多單：現價 < 均價 × 0.98（偏離 -2% 以上）→ 警示
    // 空單：現價 > 均價 × 1.02（偏離 +2% 以上）→ 警示
    val isWarning = when (position.direction) {
        BuySell.Buy  -> deviationPct < -2.0
        BuySell.Sell -> deviationPct > 2.0
    }

    if (!isWarning) return

    val isLoss = when (position.direction) {
        BuySell.Buy  -> deviationPct < 0
        BuySell.Sell -> deviationPct > 0
    }

    val tagColor = if (isLoss) StockDown else StockUp
    val tagText = when {
        deviationPct > 0 -> "↑ +${String.format("%.1f", deviationPct)}%"
        else -> "↓ ${String.format("%.1f", deviationPct)}%"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(tagColor.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "偏離建倉價 $tagText",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = tagColor
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 組件：空持倉
// ══════════════════════════════════════════════════════════════

@Composable
private fun EmptyFuturesPositionsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "今日無期貨持倉",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "輸入期貨代碼開始交易",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 組件：持倉總計
// ══════════════════════════════════════════════════════════════

@Composable
private fun FuturesSummaryCard(positions: List<FuturesPosition>) {
    val totalUnrealizedPnL = positions.sumOf { it.unrealizedPnL }
    val totalRealizedPnL = positions.sumOf { it.realizedPnL }

    val pnlColor = when {
        totalUnrealizedPnL > 0 -> StockUp
        totalUnrealizedPnL < 0 -> StockDown
        else -> StockFlat
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "期貨持倉總計",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "未實現損益",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = String.format("%+,.0f", totalUnrealizedPnL),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "已實現損益",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = String.format("%+,.0f", totalRealizedPnL),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (totalRealizedPnL >= 0) StockUp else StockDown
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 組件：截止提醒
// ══════════════════════════════════════════════════════════════

@Composable
private fun DeadlineReminder() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "期貨結算時間 13:30，請留意及時平倉",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
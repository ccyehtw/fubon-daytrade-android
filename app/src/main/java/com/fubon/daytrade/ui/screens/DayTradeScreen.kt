package com.fubon.daytrade.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fubon.daytrade.domain.model.BuySell
import com.fubon.daytrade.ui.components.EntryMode
import com.fubon.daytrade.ui.components.OrderPanel
import com.fubon.daytrade.ui.components.QuoteCard
import com.fubon.daytrade.ui.theme.LimitDownBlue
import com.fubon.daytrade.ui.theme.LimitUpRed
import com.fubon.daytrade.ui.theme.StockDown
import com.fubon.daytrade.ui.theme.StockFlat
import com.fubon.daytrade.ui.theme.StockUp
import com.fubon.daytrade.ui.viewmodel.AutoSquareStatus
import com.fubon.daytrade.ui.viewmodel.DayTradePosition
import com.fubon.daytrade.ui.viewmodel.DayTradeViewModel
import com.fubon.daytrade.ui.viewmodel.TrackingPhase
import com.fubon.daytrade.ui.viewmodel.TrackingMode
import com.fubon.daytrade.ui.viewmodel.ConditionParams
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DayTradeScreen(
    viewModel: DayTradeViewModel = hiltViewModel(),
    onNavigateToQuote: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        // Initial load
    }

    // Auto square check every minute
    LaunchedEffect(Unit) {
        viewModel.autoSquareCheck()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Account info header
        item {
            AccountHeader(
                currentAccount = uiState.currentAccount,
                accounts = uiState.accounts,
                onSwitchAccount = { viewModel.switchAccount(it) }
            )
        }

        // Symbol search
        item {
            SymbolSearchSection(
                symbol = uiState.quoteSymbol,
                isLoading = uiState.isQuoteLoading,
                onSymbolChange = { viewModel.updateQuoteSymbol(it) },
                onSearch = {
                    viewModel.quoteStock(uiState.quoteSymbol)
                    focusManager.clearFocus()
                },
                onNavigateToQuote = onNavigateToQuote
            )
        }

        // Current quote display
        if (uiState.currentQuote != null) {
            item {
                QuoteCard(
                    stockTick = uiState.currentQuote,
                    symbol = uiState.quoteSymbol
                )
            }

            item {
                OrderPanel(
                    currentPrice = uiState.currentQuote?.price,
                    isLoading = uiState.isOrderLoading,
                    trackingPhase = uiState.trackingPhase,
                    trackingMode = uiState.trackingMode,
                    conditionParams = uiState.conditionParams,
                    hasPosition = uiState.dayTradePositions.isNotEmpty(),
                    onBuy = { price, quantity ->
                        viewModel.placeDayTradeBuy(uiState.quoteSymbol, price, quantity)
                    },
                    onSell = { price, quantity ->
                        viewModel.placeDayTradeSell(uiState.quoteSymbol, price, quantity)
                    },
                    entryMode = uiState.entryMode,
                    onEntryModeChange = { viewModel.setEntryMode(it) },
                    trackLevels = uiState.trackLevels,
                    onTrackLevelsChange = { viewModel.setTrackLevels(it) },
                    stopLossPct = uiState.stopLossPct,
                    onStopLossPctChange = { viewModel.setStopLossPct(it) },
                    onStartBreakdownBuy = { lowPrice, reboundTicks, stopLossPct, quantity ->
                        viewModel.startTracking(
                            mode = TrackingMode.BreakdownBuy,
                            lowPrice = lowPrice,
                            highPrice = null,
                            reboundTicks = reboundTicks,
                            retraceTicks = 5,
                            stopLossPct = stopLossPct.toDouble(),
                            quantity = quantity,
                            tickSize = 0.01  // 股票最小跳動 0.01 元
                        )
                    },
                    onStartBreakoutSell = { highPrice, retraceTicks, stopLossPct, quantity ->
                        viewModel.startTracking(
                            mode = TrackingMode.BreakoutSell,
                            lowPrice = null,
                            highPrice = highPrice,
                            reboundTicks = 5,
                            retraceTicks = retraceTicks,
                            stopLossPct = stopLossPct.toDouble(),
                            quantity = quantity,
                            tickSize = 0.01
                        )
                    },
                    onCancel = { viewModel.cancelTracking() },
                    onClosePosition = { viewModel.closeWithOppositeButton() }
                )
            }
        }

        // Order message
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

        // Error message
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

        // Auto square status
        item {
            AutoSquareStatusCard(
                status = uiState.autoSquareStatus,
                autoSquareTime = uiState.autoSquareTime,
                enabled = uiState.autoSquareEnabled
            )
        }

        // Day trade positions
        item {
            Text(
                text = "當日沖部位",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (uiState.dayTradePositions.isEmpty()) {
            item {
                EmptyPositionsCard()
            }
        } else {
            items(uiState.dayTradePositions) { position ->
                DayTradePositionCard(position = position)
            }
        }

        // Day trade summary
        if (uiState.dayTradePositions.isNotEmpty()) {
            item {
                DayTradeSummaryCard(positions = uiState.dayTradePositions)
            }
        }

        // Bottom spacing
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun AccountHeader(
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
                        text = "登入帳號",
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
                TextButton(onClick = { /* Show account selector */ }) {
                    Text("切換帳號")
                }
            }
        }
    }
}

@Composable
private fun SymbolSearchSection(
    symbol: String,
    isLoading: Boolean,
    onSymbolChange: (String) -> Unit,
    onSearch: () -> Unit,
    onNavigateToQuote: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = symbol,
            onValueChange = onSymbolChange,
            label = { Text("股票代碼") },
            placeholder = { Text("例如: 2330") },
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

        TextButton(onClick = onNavigateToQuote) {
            Text("報價")
        }
    }
}

@Composable
private fun AutoSquareStatusCard(
    status: AutoSquareStatus,
    autoSquareTime: String,
    enabled: Boolean
) {
    val (statusText, statusColor) = when (status) {
        AutoSquareStatus.Pending -> "等待平倉 (${autoSquareTime})" to MaterialTheme.colorScheme.secondary
        AutoSquareStatus.Triggered -> "平倉中..." to MaterialTheme.colorScheme.primary
        AutoSquareStatus.Completed -> "平倉完成" to MaterialTheme.colorScheme.tertiary
        AutoSquareStatus.Skipped -> "已跳過" to MaterialTheme.colorScheme.outline
    }

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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "13:20 自動平倉",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = statusColor
            )
        }
    }
}

@Composable
private fun EmptyPositionsCard() {
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
                text = "今日無當日沖部位",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "輸入股票代碼開始交易",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun DayTradePositionCard(position: DayTradePosition) {
    val directionColor = if (position.direction == BuySell.Buy) LimitUpRed else LimitDownBlue
    val directionText = if (position.direction == BuySell.Buy) "買" else "賣"
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
            // Header row
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
                    // 顯示進場模式標籤（如果有）
                    position.entryMode?.let { mode ->
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    when (mode) {
                                        "breakdown_buy" -> LimitUpRed.copy(alpha = 0.08f)
                                        "breakout_sell" -> LimitDownBlue.copy(alpha = 0.08f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (mode == "breakdown_buy") "追低買" else "追高賣",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = when (mode) {
                                    "breakdown_buy" -> LimitUpRed
                                    "breakout_sell" -> LimitDownBlue
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                Text(
                    text = "${position.quantity} 股",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Price info
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
                        text = String.format("%.2f", position.avgPrice),
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
                        text = String.format("%.2f", position.currentPrice),
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
                        text = String.format("%+.2f", position.unrealizedPnL),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor
                    )
                }
            }

            // Realized P&L if any
            if (position.realizedPnL != 0.0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "已實現: ${String.format("%+.2f", position.realizedPnL)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DayTradeSummaryCard(positions: List<DayTradePosition>) {
    val totalUnrealizedPnL = positions.sumOf { it.unrealizedPnL }
    val totalRealizedPnL = positions.sumOf { it.realizedPnL }
    val totalMarketValue = positions.sumOf { it.marketValue }

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
                text = "當日沖總計",
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
                        text = String.format("%+.2f", totalUnrealizedPnL),
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
                        text = String.format("%+.2f", totalRealizedPnL),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (totalRealizedPnL >= 0) StockUp else StockDown
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "持倉市值",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = String.format("%.2f", totalMarketValue),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
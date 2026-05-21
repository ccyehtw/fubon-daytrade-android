package com.fubon.daytrade.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fubon.daytrade.ui.theme.LimitDownBlue
import com.fubon.daytrade.ui.theme.LimitUpRed
import com.fubon.daytrade.ui.theme.StockDown
import com.fubon.daytrade.ui.theme.StockFlat
import com.fubon.daytrade.ui.theme.StockUp
import com.fubon.daytrade.ui.viewmodel.ConditionParams
import com.fubon.daytrade.ui.viewmodel.TrackingMode
import com.fubon.daytrade.ui.viewmodel.TrackingPhase

// ══════════════════════════════════════════════════════════════
// 雙模式 entry mode 常數
// ══════════════════════════════════════════════════════════════

enum class EntryMode(val label: String, val desc: String) {
    BREAKDOWN_BUY("追低買", "低點進場 → 高點回檔 N 檔平倉"),
    BREAKOUT_SELL("追高賣", "高點進場 → 低點反彈 N 檔平倉"),
}

@Composable
fun OrderPanel(
    currentPrice: Double?,
    isLoading: Boolean,
    onBuy: (price: Double, quantity: Int) -> Unit,
    onSell: (price: Double, quantity: Int) -> Unit,
    modifier: Modifier = Modifier,
    // 雙模式參數（可選）
    entryMode: EntryMode? = null,
    onEntryModeChange: ((EntryMode) -> Unit)? = null,
    trackLevels: Int? = null,
    onTrackLevelsChange: ((Int) -> Unit)? = null,
    stopLossPct: Float? = null,
    onStopLossPctChange: ((Float) -> Unit)? = null,
    // 條件單追蹤參數（可選）
    trackingPhase: TrackingPhase? = null,
    trackingMode: TrackingMode? = null,
    conditionParams: ConditionParams? = null,
    hasPosition: Boolean = false,
    onStartBreakdownBuy: ((lowPrice: Double, reboundTicks: Int, stopLossPct: Float, quantity: Int) -> Unit)? = null,
    onStartBreakoutSell: ((highPrice: Double, retraceTicks: Int, stopLossPct: Float, quantity: Int) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onClosePosition: (() -> Unit)? = null,
) {
    // 股票最小跳動為 0.01 元（證券）
    val tickSize = 0.01
    var priceText by remember { mutableStateOf(currentPrice?.toString() ?: "") }
    var quantityText by remember { mutableStateOf("") }
    var lowPriceText by remember { mutableStateOf("") }
    var highPriceText by remember { mutableStateOf("") }
    var reboundTicks by remember { mutableIntStateOf(5) }
    var retraceTicks by remember { mutableIntStateOf(5) }

    // Update price when currentPrice changes
    if (currentPrice != null && priceText.isEmpty()) {
        priceText = currentPrice.toString()
    }

    val isTracking = trackingPhase != null && trackingPhase != TrackingPhase.Idle
    val isPhase1 = trackingPhase == TrackingPhase.Phase1_Low_Set || trackingPhase == TrackingPhase.Phase1_High_Set
    val isPhase2 = trackingPhase == TrackingPhase.Phase2_Rebound || trackingPhase == TrackingPhase.Phase2_Retrace

    Card(
        modifier = modifier.fillMaxWidth(),
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
                text = "下單面板",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // ══ 雙模式選擇（僅當有設定時顯示）══
            if (entryMode != null && onEntryModeChange != null) {
                Spacer(modifier = Modifier.height(12.dp))
                DualModeSelector(
                    selected = entryMode,
                    onModeSelected = onEntryModeChange
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Price input
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("價格") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = !isLoading
                )

                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.filter { c -> c.isDigit() } },
                    label = { Text("數量") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !isLoading
                )
            }

            // ══ 追蹤檔位 + 停損設定（僅當有設定時顯示）══
            if (trackLevels != null && onTrackLevelsChange != null) {
                Spacer(modifier = Modifier.height(12.dp))
                TrackLevelsSelector(
                    selectedLevel = trackLevels,
                    onLevelSelected = { onTrackLevelsChange(it) },
                    entryMode = entryMode ?: EntryMode.BREAKDOWN_BUY
                )
            }

            if (stopLossPct != null && onStopLossPctChange != null) {
                Spacer(modifier = Modifier.height(8.dp))
                StopLossSlider(
                    stopLossPct = stopLossPct,
                    onStopLossPctChange = { onStopLossPctChange(it) }
                )
            }

            // ══ 條件追蹤中（Phase1 / Phase2）══
            if (isTracking) {
                // 狀態卡
                val (phaseText, phaseColor) = when (trackingPhase) {
                    TrackingPhase.Phase1_Low_Set ->
                        "📡 等待跌破低點 ${conditionParams?.lowPrice ?: "?"}..." to LimitUpRed
                    TrackingPhase.Phase1_High_Set ->
                        "📡 等待突破高點 ${conditionParams?.highPrice ?: "?"}..." to LimitDownBlue
                    TrackingPhase.Phase2_Rebound ->
                        "📈 已跌破！等反彈 ${conditionParams?.reboundTicks ?: 5} 檔" to StockUp
                    TrackingPhase.Phase2_Retrace ->
                        "📉 已突破！等回檔 ${conditionParams?.retraceTicks ?: 5} 檔" to StockDown
                    else -> "" to MaterialTheme.colorScheme.onSurface
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = phaseColor.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(phaseColor))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = phaseText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = phaseColor)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 兩顆按鈕：取消 + 平倉
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onCancel?.invoke() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isLoading
                    ) {
                        Text(text = "❌ 取消追蹤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onClosePosition?.invoke() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (trackingMode == TrackingMode.BreakdownBuy) LimitDownBlue else LimitUpRed
                        ),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isLoading && hasPosition
                    ) {
                        Text(text = if (hasPosition) "平倉" else "等待建倉...", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // 一般買入/賣出按鈕
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val price = priceText.toDoubleOrNull()
                            val quantity = quantityText.toIntOrNull()
                            if (price != null && quantity != null && quantity > 0) {
                                onBuy(price, quantity)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && priceText.toDoubleOrNull() != null && quantityText.toIntOrNull()?.let { it > 0 } == true,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LimitUpRed
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isLoading) "處理中..." else "買入",
                            style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                Button(
                    onClick = {
                        val price = priceText.toDoubleOrNull()
                        val quantity = quantityText.toIntOrNull()
                        if (price != null && quantity != null && quantity > 0) {
                            onSell(price, quantity)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading && priceText.toDoubleOrNull() != null && quantityText.toIntOrNull()?.let { it > 0 } == true,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LimitDownBlue
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isLoading) "處理中..." else "賣出",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DualModeSelector(
    selected: EntryMode,
    onModeSelected: (EntryMode) -> Unit
) {
    Column {
        Text(
            text = "進場模式",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            EntryMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onModeSelected(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = EntryMode.entries.size
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = when (mode) {
                            EntryMode.BREAKDOWN_BUY -> LimitUpRed.copy(alpha = 0.15f)
                            EntryMode.BREAKOUT_SELL -> LimitDownBlue.copy(alpha = 0.15f)
                        },
                        activeContentColor = when (mode) {
                            EntryMode.BREAKDOWN_BUY -> LimitUpRed
                            EntryMode.BREAKOUT_SELL -> LimitDownBlue
                        }
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = mode.label,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = selected.desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun TrackLevelsSelector(
    selectedLevel: Int,
    onLevelSelected: (Int) -> Unit,
    entryMode: EntryMode
) {
    val labels = when (entryMode) {
        EntryMode.BREAKDOWN_BUY -> listOf("1檔 積極", "2檔", "3檔", "4檔", "5檔 保守")
        EntryMode.BREAKOUT_SELL -> listOf("1檔 保守", "2檔", "3檔", "4檔", "5檔 積極")
    }
    val colors = when (entryMode) {
        EntryMode.BREAKDOWN_BUY -> LimitUpRed
        EntryMode.BREAKOUT_SELL -> LimitDownBlue
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "追蹤檔位",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = labels[selectedLevel - 1],
                style = MaterialTheme.typography.labelMedium,
                color = colors,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = selectedLevel.toFloat(),
            onValueChange = { onLevelSelected(it.toInt()) },
            valueRange = 1f..5f,
            steps = 3,
            colors = SliderDefaults.colors(
                thumbColor = colors,
                activeTrackColor = colors,
                inactiveTrackColor = colors.copy(alpha = 0.3f)
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("5", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StopLossSlider(
    stopLossPct: Float,
    onStopLossPctChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "停損設定",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "%.1f%%".format(stopLossPct),
                style = MaterialTheme.typography.labelMedium,
                color = LimitUpRed,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = stopLossPct,
            onValueChange = { onStopLossPctChange(it) },
            valueRange = 0.5f..5f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = LimitUpRed,
                activeTrackColor = LimitUpRed,
                inactiveTrackColor = LimitUpRed.copy(alpha = 0.3f)
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0.5%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("3%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("5%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickQuantityButton(
    quantity: Int,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = "${quantity}張",
            style = MaterialTheme.typography.labelMedium
        )
    }
}
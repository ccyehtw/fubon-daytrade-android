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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fubon.daytrade.ui.theme.LimitDownBlue
import com.fubon.daytrade.ui.theme.LimitUpRed

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
) {
    var priceText by remember { mutableStateOf(currentPrice?.toString() ?: "") }
    var quantityText by remember { mutableStateOf("") }

    // Update price when currentPrice changes
    if (currentPrice != null && priceText.isEmpty()) {
        priceText = currentPrice.toString()
    }

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

            Spacer(modifier = Modifier.height(16.dp))

            // Buy/Sell buttons
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

            // Quick quantity buttons
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(1, 5, 10, 20).forEach { qty ->
                    QuickQuantityButton(
                        quantity = qty,
                        onClick = { quantityText = qty.toString() },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
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
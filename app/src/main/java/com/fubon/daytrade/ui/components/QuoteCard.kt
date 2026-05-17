package com.fubon.daytrade.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fubon.daytrade.data.repository.StockTick
import com.fubon.daytrade.ui.theme.LimitDownBlue
import com.fubon.daytrade.ui.theme.LimitUpRed
import com.fubon.daytrade.ui.theme.StockDown
import com.fubon.daytrade.ui.theme.StockFlat
import com.fubon.daytrade.ui.theme.StockUp

@Composable
fun QuoteCard(
    stockTick: StockTick?,
    symbol: String,
    modifier: Modifier = Modifier
) {
    val isUp = (stockTick?.change ?: 0.0) > 0
    val isDown = (stockTick?.change ?: 0.0) < 0
    val isLimitUp = stockTick?.price == stockTick?.limitUpPrice
    val isLimitDown = stockTick?.price == stockTick?.limitDownPrice

    val priceColor = when {
        isLimitUp -> LimitUpRed
        isLimitDown -> LimitDownBlue
        isUp -> StockUp
        isDown -> StockDown
        else -> StockFlat
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
            // Header: Symbol and status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = symbol,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                
                // Limit up/down badge
                if (isLimitUp) {
                    LimitBadge(text = "漲停", color = LimitUpRed)
                } else if (isLimitDown) {
                    LimitBadge(text = "跌停", color = LimitDownBlue)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Price display
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = stockTick?.price?.toString() ?: "--",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = priceColor
                    )
                    Text(
                        text = "價格",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    val changeText = when {
                        stockTick == null -> "--"
                        stockTick.change >= 0 -> "+${String.format("%.2f", stockTick.change)}"
                        else -> String.format("%.2f", stockTick.change)
                    }
                    val percentText = stockTick?.changePercent?.let { 
                        if (it >= 0) "+${String.format("%.2f", it)}%" 
                        else "${String.format("%.2f", it)}%"
                    } ?: "--%"

                    Text(
                        text = changeText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = priceColor
                    )
                    Text(
                        text = percentText,
                        fontSize = 16.sp,
                        color = priceColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bid/Ask and Volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                QuoteInfoItem(label = "買價", value = stockTick?.bid?.toString() ?: "--")
                QuoteInfoItem(label = "賣價", value = stockTick?.ask?.toString() ?: "--")
                QuoteInfoItem(label = "成交量", value = stockTick?.volume?.toString() ?: "--")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Limit prices
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                QuoteInfoItem(
                    label = "漲停",
                    value = stockTick?.limitUpPrice?.toString() ?: "--",
                    valueColor = LimitUpRed
                )
                QuoteInfoItem(
                    label = "跌停",
                    value = stockTick?.limitDownPrice?.toString() ?: "--",
                    valueColor = LimitDownBlue
                )
            }
        }
    }
}

@Composable
private fun QuoteInfoItem(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

@Composable
private fun LimitBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
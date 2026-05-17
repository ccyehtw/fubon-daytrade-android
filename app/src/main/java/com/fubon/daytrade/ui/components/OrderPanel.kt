package com.fubon.daytrade.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fubon.daytrade.ui.theme.LimitDownBlue
import com.fubon.daytrade.ui.theme.LimitUpRed

@Composable
fun OrderPanel(
    currentPrice: Double?,
    isLoading: Boolean,
    onBuy: (price: Double, quantity: Int) -> Unit,
    onSell: (price: Double, quantity: Int) -> Unit,
    modifier: Modifier = Modifier
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
                        enabled = !isLoading
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickQuantityButton(
    quantity: Int,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
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
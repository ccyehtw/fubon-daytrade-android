package com.fubon.daytrade.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.fubon.daytrade.ui.components.OrderPanel
import com.fubon.daytrade.ui.components.QuoteCard
import com.fubon.daytrade.ui.viewmodel.DayTradeViewModel

@Composable
fun QuoteScreen(
    viewModel: DayTradeViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        viewModel.initQuoteScreen()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Text(
            text = "個股報價",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Symbol input
        OutlinedTextField(
            value = uiState.quoteSymbol,
            onValueChange = { viewModel.updateQuoteSymbol(it.uppercase()) },
            label = { Text("股票代碼") },
            placeholder = { Text("例如: 2330") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (uiState.quoteSymbol.isNotBlank()) {
                        viewModel.quoteStock(uiState.quoteSymbol)
                        focusManager.clearFocus()
                    }
                }
            ),
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (uiState.quoteSymbol.isNotBlank()) {
                            viewModel.quoteStock(uiState.quoteSymbol)
                            focusManager.clearFocus()
                        }
                    }
                ) {
                    Icon(Icons.Default.Search, contentDescription = "查詢")
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Loading indicator
        if (uiState.isQuoteLoading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Error message
        uiState.errorMessage?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Quote card
        if (uiState.currentQuote != null) {
            QuoteCard(
                stockTick = uiState.currentQuote,
                symbol = uiState.quoteSymbol
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Order panel
            OrderPanel(
                currentPrice = uiState.currentQuote?.price,
                isLoading = uiState.isOrderLoading,
                onBuy = { price, quantity ->
                    viewModel.placeDayTradeBuy(uiState.quoteSymbol, price, quantity)
                },
                onSell = { price, quantity ->
                    viewModel.placeDayTradeSell(uiState.quoteSymbol, price, quantity)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quote info
        if (uiState.currentQuote != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "報價資訊",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "最新更新: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(uiState.currentQuote!!.timestamp))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "漲跌: ${uiState.currentQuote!!.change}, 漲跌幅: ${String.format("%.2f", uiState.currentQuote!!.changePercent)}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "買價: ${uiState.currentQuote!!.bid}, 賣價: ${uiState.currentQuote!!.ask}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
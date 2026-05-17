package com.fubon.daytrade.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fubon.daytrade.data.model.AccountInfo
import com.fubon.daytrade.data.repository.FubonRepository
import com.fubon.daytrade.data.repository.StockTick
import com.fubon.daytrade.domain.model.BuySell
import com.fubon.daytrade.domain.model.Position
import com.fubon.daytrade.domain.model.StockOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DayTradeUiState(
    // Account info
    val currentAccount: AccountInfo? = null,
    val accounts: List<AccountInfo> = emptyList(),
    
    // Quote
    val quoteSymbol: String = "",
    val currentQuote: StockTick? = null,
    val isQuoteLoading: Boolean = false,
    
    // Positions
    val dayTradePositions: List<DayTradePosition> = emptyList(),
    
    // Orders
    val isOrderLoading: Boolean = false,
    val orderMessage: String? = null,
    
    // Auto square
    val autoSquareTime: String = "13:20",
    val autoSquareEnabled: Boolean = true,
    val autoSquareStatus: AutoSquareStatus = AutoSquareStatus.Pending,
    
    // Error
    val errorMessage: String? = null
)

data class DayTradePosition(
    val symbol: String,
    val quantity: Int,
    val avgPrice: Double,
    val direction: BuySell,
    val currentPrice: Double = 0.0,
    val realizedPnL: Double = 0.0
) {
    val unrealizedPnL: Double
        get() = when (direction) {
            BuySell.Buy -> (currentPrice - avgPrice) * quantity
            BuySell.Sell -> (avgPrice - currentPrice) * quantity
        }
    
    val marketValue: Double
        get() = currentPrice * quantity
}

enum class AutoSquareStatus {
    Pending,       // Waiting for auto square time
    Triggered,      // Auto square triggered
    Completed,      // Auto square completed
    Skipped         // Skipped (no positions)
}

@HiltViewModel
class DayTradeViewModel @Inject constructor(
    private val repository: FubonRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DayTradeUiState())
    val uiState: StateFlow<DayTradeUiState> = _uiState.asStateFlow()

    init {
        loadAccounts()
    }

    fun initQuoteScreen() {
        loadAccounts()
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            val accounts = repository.getAccounts()
            _uiState.update { it.copy(accounts = accounts) }
            if (accounts.isNotEmpty() && _uiState.value.currentAccount == null) {
                _uiState.update { it.copy(currentAccount = accounts.first()) }
            }
        }
    }

    fun updateQuoteSymbol(symbol: String) {
        _uiState.update { it.copy(quoteSymbol = symbol, errorMessage = null) }
    }

    fun quoteStock(symbol: String) {
        if (symbol.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請輸入股票代碼") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isQuoteLoading = true, errorMessage = null) }
            
            try {
                repository.subscribeStockPrice(symbol).collect { tick ->
                    _uiState.update { 
                        it.copy(
                            currentQuote = tick,
                            isQuoteLoading = false
                        ) 
                    }
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isQuoteLoading = false,
                        errorMessage = "查詢報價失敗: ${e.message}"
                    ) 
                }
            }
        }
    }

    fun placeDayTradeBuy(symbol: String, price: Double, quantity: Int) {
        placeDayTradeOrder(symbol, price, quantity, BuySell.Buy)
    }

    fun placeDayTradeSell(symbol: String, price: Double, quantity: Int) {
        placeDayTradeOrder(symbol, price, quantity, BuySell.Sell)
    }

    private fun placeDayTradeOrder(symbol: String, price: Double, quantity: Int, buySell: BuySell) {
        val account = _uiState.value.currentAccount
        if (account == null) {
            _uiState.update { it.copy(errorMessage = "請先登入") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isOrderLoading = true, orderMessage = null) }
            
            try {
                val order = StockOrder(
                    symbol = symbol,
                    price = price,
                    quantity = quantity,
                    buySell = buySell,
                    orderType = com.fubon.daytrade.domain.model.OrderType.DayTrade
                )
                
                val result = repository.placeStockOrder(
                    accountId = account.accountId,
                    symbol = symbol,
                    price = price,
                    quantity = quantity,
                    buySell = buySell.name
                )
                
                result.fold(
                    onSuccess = { orderId ->
                        _uiState.update { 
                            it.copy(
                                isOrderLoading = false,
                                orderMessage = "當日沖訂單已送出: $orderId"
                            ) 
                        }
                        // Refresh positions
                        refreshDayTradePositions()
                    },
                    onFailure = { error ->
                        _uiState.update { 
                            it.copy(
                                isOrderLoading = false,
                                errorMessage = "下單失敗: ${error.message}"
                            ) 
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isOrderLoading = false,
                        errorMessage = "網路錯誤: ${e.message}"
                    ) 
                }
            }
        }
    }

    private fun refreshDayTradePositions() {
        viewModelScope.launch {
            val account = _uiState.value.currentAccount ?: return@launch
            try {
                val positions = repository.getStockPositions(account.accountId)
                val dayTradePositions = positions.map { pos ->
                    DayTradePosition(
                        symbol = pos.symbol,
                        quantity = pos.quantity,
                        avgPrice = pos.avgPrice,
                        direction = pos.direction,
                        currentPrice = pos.currentPrice
                    )
                }
                _uiState.update { it.copy(dayTradePositions = dayTradePositions) }
            } catch (e: Exception) {
                // Silent fail for refresh
            }
        }
    }

    fun autoSquareCheck() {
        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(Calendar.MINUTE)
        
        // Check if it's 13:20
        if (currentHour == 13 && currentMinute >= 20) {
            performAutoSquare()
        } else {
            val status = if (_uiState.value.autoSquareEnabled) {
                AutoSquareStatus.Pending
            } else {
                AutoSquareStatus.Skipped
            }
            _uiState.update { it.copy(autoSquareStatus = status) }
        }
    }

    private fun performAutoSquare() {
        val positions = _uiState.value.dayTradePositions
        if (positions.isEmpty()) {
            _uiState.update { it.copy(autoSquareStatus = AutoSquareStatus.Skipped) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(autoSquareStatus = AutoSquareStatus.Triggered) }
            
            // Auto square all positions
            for (position in positions) {
                val squareBuySell = if (position.direction == BuySell.Buy) BuySell.Sell else BuySell.Buy
                placeDayTradeOrder(position.symbol, position.currentPrice, position.quantity, squareBuySell)
            }
            
            _uiState.update { it.copy(autoSquareStatus = AutoSquareStatus.Completed) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearOrderMessage() {
        _uiState.update { it.copy(orderMessage = null) }
    }

    fun switchAccount(account: AccountInfo) {
        _uiState.update { it.copy(currentAccount = account) }
        refreshDayTradePositions()
    }
}
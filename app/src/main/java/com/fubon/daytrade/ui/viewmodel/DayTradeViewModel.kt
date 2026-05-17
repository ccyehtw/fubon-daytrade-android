package com.fubon.daytrade.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fubon.daytrade.data.model.AccountInfo
import com.fubon.daytrade.data.network.NetworkResult
import com.fubon.daytrade.data.network.WebSocketClient
import com.fubon.daytrade.data.repository.FubonRepository
import com.fubon.daytrade.data.repository.RetryHelper
import com.fubon.daytrade.data.repository.StockTick
import com.fubon.daytrade.domain.model.BuySell
import com.fubon.daytrade.domain.model.Position
import com.fubon.daytrade.domain.model.StockOrder
import com.fubon.daytrade.ui.components.EntryMode
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
    
    // Dual-mode order parameters
    val entryMode: EntryMode = EntryMode.BREAKDOWN_BUY,
    val trackLevels: Int = 3,
    val stopLossPct: Float = 2.0f,
    
    // Positions
    val dayTradePositions: List<DayTradePosition> = emptyList(),
    
    // Orders
    val isOrderLoading: Boolean = false,
    val orderMessage: String? = null,
    
    // Auto square
    val autoSquareTime: String = "13:20",
    val autoSquareEnabled: Boolean = true,
    val autoSquareStatus: AutoSquareStatus = AutoSquareStatus.Pending,
    
    // Error state
    val errorMessage: String? = null,
    val errorState: ErrorState = ErrorState.None
)

/**
 * Represents different error states for the UI layer.
 */
sealed class ErrorState {
    data object None : ErrorState()
    data class NetworkError(val retry: () -> Unit) : ErrorState()
    data class TimeoutError(val retry: () -> Unit) : ErrorState()
    data class ApiError(val code: Int?, val message: String, val retry: (() -> Unit)?) : ErrorState()
}

data class DayTradePosition(
    val symbol: String,
    val quantity: Int,
    val avgPrice: Double,
    val direction: BuySell,
    val currentPrice: Double = 0.0,
    val realizedPnL: Double = 0.0,
    val entryMode: String? = null
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

    fun setEntryMode(mode: EntryMode) {
        _uiState.update { it.copy(entryMode = mode) }
    }

    fun setTrackLevels(level: Int) {
        _uiState.update { it.copy(trackLevels = level.coerceIn(1, 5)) }
    }

    fun setStopLossPct(pct: Float) {
        _uiState.update { it.copy(stopLossPct = pct.coerceIn(0.5f, 5f)) }
    }

    fun quoteStock(symbol: String) {
        if (symbol.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請輸入股票代碼") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isQuoteLoading = true, errorMessage = null) }

            try {
                // 透過 WebSocket 取得即時報價
                val wsClient = repository.getWebSocketClient()
                if (!wsClient.connected) {
                    wsClient.connect()
                }
                wsClient.subscribe(listOf(symbol))

                // 觀察股票報價 Flow（當 symbol 的報價更新時自動通知 UI）
                wsClient.stockQuotesFlow.collect { tickMap ->
                    val tick = tickMap[symbol.uppercase()]
                    if (tick != null) {
                        _uiState.update {
                            it.copy(
                                currentQuote = StockTick(
                                    symbol = tick.symbol,
                                    price = tick.last_price,
                                    change = tick.change,
                                    changePercent = tick.change_percent,
                                    volume = tick.volume,
                                    bid = tick.bid_price,
                                    ask = tick.ask_price,
                                    tickSize = 0.5,
                                    limitUpPrice = tick.limit_up_price,
                                    limitDownPrice = tick.limit_down_price,
                                    timestamp = System.currentTimeMillis()
                                ),
                                isQuoteLoading = false
                            )
                        }
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

    /**
     * 雙模式下單工廠（DayTradeService entry）
     * 根據 entryMode 決定買賣方向與進場策略
     */
    fun placeDayTradeWithEntryMode(
        symbol: String,
        price: Double,
        quantity: Int,
        entryMode: EntryMode,
        trackLevels: Int,
        stopLossPct: Float
    ) {
        val (buySell, productType) = when (entryMode) {
            EntryMode.BREAKDOWN_BUY -> BuySell.Buy to "stock"
            EntryMode.BREAKOUT_SELL -> BuySell.Sell to "stock"
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isOrderLoading = true, orderMessage = null, errorState = ErrorState.None) }

            try {
                val account = _uiState.value.currentAccount ?: throw Exception("請先登入")
                val result = repository.placeDayTradeEntry(
                    accountId = account.accountId,
                    symbol = symbol,
                    entryMode = entryMode.name.lowercase(),
                    price = price,
                    quantity = quantity,
                    stopLossPct = stopLossPct,
                    trackLevels = trackLevels,
                    productType = productType,
                    tickSize = 0.5
                )

                result.fold(
                    onSuccess = { orderId ->
                        _uiState.update {
                            it.copy(
                                isOrderLoading = false,
                                orderMessage = "[${entryMode.label}] 訂單已送出: $orderId"
                            )
                        }
                        refreshDayTradePositions()
                    },
                    onFailure = { error ->
                        val errorState = when {
                            error.message?.contains("網路連線失敗") == true -> {
                                ErrorState.NetworkError(retry = {
                                    placeDayTradeWithEntryMode(symbol, price, quantity, entryMode, trackLevels, stopLossPct)
                                })
                            }
                            error.message?.contains("連線逾時") == true -> {
                                ErrorState.TimeoutError(retry = {
                                    placeDayTradeWithEntryMode(symbol, price, quantity, entryMode, trackLevels, stopLossPct)
                                })
                            }
                            else -> {
                                ErrorState.ApiError(code = null, message = error.message ?: "下單失敗", retry = {
                                    placeDayTradeWithEntryMode(symbol, price, quantity, entryMode, trackLevels, stopLossPct)
                                })
                            }
                        }
                        _uiState.update {
                            it.copy(
                                isOrderLoading = false,
                                errorMessage = error.message,
                                errorState = errorState
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

    private fun placeDayTradeOrder(symbol: String, price: Double, quantity: Int, buySell: BuySell) {
        val account = _uiState.value.currentAccount
        if (account == null) {
            _uiState.update { it.copy(errorMessage = "請先登入") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isOrderLoading = true, orderMessage = null, errorState = ErrorState.None) }
            
            try {
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
                        // Determine error type and set appropriate state
                        val errorState = when {
                            error.message?.contains("網路連線失敗") == true -> {
                                ErrorState.NetworkError(retry = { placeDayTradeOrder(symbol, price, quantity, buySell) })
                            }
                            error.message?.contains("連線逾時") == true -> {
                                ErrorState.TimeoutError(retry = { placeDayTradeOrder(symbol, price, quantity, buySell) })
                            }
                            else -> {
                                ErrorState.ApiError(code = null, message = error.message ?: "下單失敗", retry = { placeDayTradeOrder(symbol, price, quantity, buySell) })
                            }
                        }
                        _uiState.update { 
                            it.copy(
                                isOrderLoading = false,
                                errorMessage = error.message,
                                errorState = errorState
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
                        currentPrice = pos.currentPrice,
                        realizedPnL = pos.realizedPnL ?: 0.0,
                        entryMode = pos.entryMode
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
        _uiState.update { it.copy(errorMessage = null, errorState = ErrorState.None) }
    }

    fun clearOrderMessage() {
        _uiState.update { it.copy(orderMessage = null) }
    }

    fun switchAccount(account: AccountInfo) {
        _uiState.update { it.copy(currentAccount = account) }
        refreshDayTradePositions()
    }
}
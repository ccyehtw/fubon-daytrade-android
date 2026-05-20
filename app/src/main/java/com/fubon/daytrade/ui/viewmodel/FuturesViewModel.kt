package com.fubon.daytrade.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fubon.daytrade.data.model.AccountInfo
import com.fubon.daytrade.data.network.OrderCallbackManager
import com.fubon.daytrade.data.network.OrderStatus
import com.fubon.daytrade.data.network.OrderUpdateEvent
import com.fubon.daytrade.data.network.WebSocketClient
import com.fubon.daytrade.data.repository.FubonRepository
import com.fubon.daytrade.domain.model.BuySell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import javax.inject.Inject

// ══════════════════════════════════════════════════════════════
// Futures-specific Position data class
// ══════════════════════════════════════════════════════════════

data class FuturesPosition(
    val symbol: String,
    val quantity: Int,             // 口數
    val avgPrice: Double,          // 建倉均價
    val direction: BuySell,        // Buy=做多, Sell=做空
    val currentPrice: Double = 0.0,
    val entryMode: String = "",    // "breakdown_buy" 或 "breakout_sell"
    val realizedPnL: Double = 0.0,
    // 期貨專用
    val contractSize: Int = 1,    // 每次交易口數
    val pointValue: Double = 200.0 // 每點價值（台指期 200 元）
) {
    /**
     * 未實現損益（期貨）
     * 多單：(現價 - 均價) × 口數 × 每點價值
     * 空單：(均價 - 現價) × 口數 × 每點價值
     */
    val unrealizedPnL: Double
        get() = when (direction) {
            BuySell.Buy  -> (currentPrice - avgPrice) * quantity * pointValue
            BuySell.Sell -> (avgPrice - currentPrice) * quantity * pointValue
        }

    /** 持倉市值（期貨用點數 × 口數 × 每點價值估算）*/
    val marketValue: Double
        get() = currentPrice * quantity * pointValue
}

// ══════════════════════════════════════════════════════════════
// UI State
// ══════════════════════════════════════════════════════════════

data class FuturesUiState(
    // Account info
    val currentAccount: AccountInfo? = null,
    val accounts: List<AccountInfo> = emptyList(),

    // Quote
    val quoteSymbol: String = "",
    val currentQuote: FuturesTick? = null,
    val isQuoteLoading: Boolean = false,

    // Margin
    val marginBalance: Double? = null,      // 帳戶保證金餘額
    val marginWarning: Boolean = false,      // 保證金不足警告

    // Positions
    val futuresPositions: List<FuturesPosition> = emptyList(),

    // Orders
    val isOrderLoading: Boolean = false,
    val orderMessage: String? = null,

    // Order status tracking (orderId -> status)
    val orderStatuses: Map<String, OrderStatusItem> = emptyMap(),

    // Error state
    val errorMessage: String? = null,
)

/** 期貨訂單狀態追蹤 */
data class OrderStatusItem(
    val orderId: String,
    val status: OrderStatus = OrderStatus.Unknown,
    val message: String = "",
    val symbol: String = "",
    val filledQty: Int = 0,
    val totalQty: Int = 0
)

/** 期貨 Tick（與 StockTick 分開，因為期貨報價結構不同）*/
data class FuturesTick(
    val symbol: String,
    val lastPrice: Double,
    val change: Double,
    val changePercent: Double,
    val volume: Long,
    val bid: Double,
    val ask: Double,
    val tickSize: Double = 1.0,      // 期貨通常 1 點
    val limitUpPrice: Double? = null,
    val limitDownPrice: Double? = null,
    val openInterest: Long? = null,   // 未平倉量
    val timestamp: Long = System.currentTimeMillis()
)

// ══════════════════════════════════════════════════════════════
// ViewModel
// ══════════════════════════════════════════════════════════════

@HiltViewModel
class FuturesViewModel @Inject constructor(
    private val repository: FubonRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FuturesUiState())
    val uiState: StateFlow<FuturesUiState> = _uiState.asStateFlow()

    init {
        loadAccounts()
        checkMargin()
        observeOrderUpdates()
    }

    // ──────────────────────────────────────────────────────────
    // Order status updates (from WebSocket via OrderCallbackManager)
    // ──────────────────────────────────────────────────────────

    private fun observeOrderUpdates() {
        viewModelScope.launch {
            OrderCallbackManager.orderUpdatesFlow.collect { event ->
                handleOrderUpdate(event)
            }
        }
    }

    private fun handleOrderUpdate(event: OrderUpdateEvent) {
        if (event.orderId.isBlank()) return
        _uiState.update { state ->
            val item = OrderStatusItem(
                orderId = event.orderId,
                status = event.status,
                message = event.message,
                symbol = event.symbol,
                filledQty = event.filledQty,
                totalQty = event.totalQty
            )
            state.copy(
                orderStatuses = state.orderStatuses + (event.orderId to item),
                orderMessage = when (event.status) {
                    OrderStatus.Filled -> "✅ 期貨訂單已成交: ${event.orderId}"
                    OrderStatus.PartiallyFilled -> "🔄 期貨訂單部分成交: ${event.orderId} (${event.filledQty}/${event.totalQty})"
                    OrderStatus.Cancelled -> "ℹ️ 期貨訂單已取消: ${event.orderId}"
                    OrderStatus.Failed, OrderStatus.Rejected -> "❌ 期貨訂單失敗: ${event.orderId} - ${event.message}"
                    else -> "📋 期貨訂單更新: ${event.orderId} - ${event.status}"
                }
            )
        }
    }

    // ──────────────────────────────────────────────────────────
    // Account
    // ──────────────────────────────────────────────────────────

    private fun loadAccounts() {
        viewModelScope.launch {
            val accounts = repository.getAccounts()
            _uiState.update { it.copy(accounts = accounts) }
            if (accounts.isNotEmpty() && _uiState.value.currentAccount == null) {
                // 期貨預設使用 futopt 帳號
                val futoptAccount = accounts.find { it.accountType == "futopt" } ?: accounts.first()
                _uiState.update { it.copy(currentAccount = futoptAccount) }
            }
        }
    }

    fun switchAccount(account: AccountInfo) {
        _uiState.update { it.copy(currentAccount = account) }
        refreshFuturesPositions()
        checkMargin()
    }

    // ──────────────────────────────────────────────────────────
    // 報價查詢（WebSocket）
    // ──────────────────────────────────────────────────────────

    fun updateQuoteSymbol(symbol: String) {
        _uiState.update { it.copy(quoteSymbol = symbol, errorMessage = null) }
    }

    fun quoteFutures(symbol: String) {
        if (symbol.isBlank()) {
            _uiState.update { it.copy(errorMessage = "請輸入期貨代碼") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isQuoteLoading = true, errorMessage = null) }

            try {
                val wsClient = repository.getWebSocketClient()

                // 先連線（如果尚未連線），並等待認證完成
                if (!wsClient.connected) {
                    wsClient.connect()
                    // 等待 WebSocket 認證完成（最多 10 秒）
                    withTimeoutOrNull(10_000) {
                        wsClient.eventsFlow
                            .filter { it is WsEvent.Connected }
                            .first()
                    }
                }

                // 訂閱期貨報價
                wsClient.subscribe(listOf(symbol.uppercase()))

                // 等待第一筆報價（最多 15 秒）
                val tickMap = withTimeoutOrNull(15_000) {
                    wsClient.futuresQuotesFlow
                        .map { it[symbol.uppercase()] }
                        .filterNotNull()
                        .first()
                }

                if (tickMap != null) {
                    _uiState.update {
                        it.copy(
                            currentQuote = FuturesTick(
                                symbol = tickMap.symbol,
                                lastPrice = tickMap.last_price,
                                change = tickMap.change,
                                changePercent = tickMap.change_percent,
                                volume = tickMap.volume,
                                bid = tickMap.bid_price,
                                ask = tickMap.ask_price,
                                tickSize = 1.0,  // 期貨預設 1 點
                                limitUpPrice = 0.0,
                                limitDownPrice = 0.0,
                                timestamp = System.currentTimeMillis()
                            ),
                            isQuoteLoading = false
                        )
                    }
                    // 更新持倉的現價（如果有相同 symbol 的持倉）
                    updatePositionPrice(symbol.uppercase(), tickMap.last_price)
                } else {
                    _uiState.update {
                        it.copy(
                            isQuoteLoading = false,
                            errorMessage = "查無此期貨報價，請確認代碼"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isQuoteLoading = false,
                        errorMessage = "查詢期貨報價失敗: ${e.message}"
                    )
                }
            }
        }
    }

    private fun updatePositionPrice(symbol: String, price: Double) {
        _uiState.update { state ->
            state.copy(
                futuresPositions = state.futuresPositions.map { pos ->
                    if (pos.symbol == symbol) pos.copy(currentPrice = price) else pos
                }
            )
        }
    }

    // ──────────────────────────────────────────────────────────
    // 保證金檢查
    // ──────────────────────────────────────────────────────────

    fun checkMargin() {
        viewModelScope.launch {
            val account = _uiState.value.currentAccount ?: return@launch
            try {
                // 嘗試呼叫 Python API 取得期貨保證金資訊
                val margin = repository.getFuturesMargin(account.accountId)
                val warning = margin != null && margin < 10000  // 低於 $10,000 警告
                _uiState.update {
                    it.copy(marginBalance = margin, marginWarning = warning)
                }
            } catch (e: Exception) {
                // 若無法取得保證金（API 未實作），預設不顯示警告
                _uiState.update { it.copy(marginBalance = null, marginWarning = false) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // 期貨下單（雙模式）
    // ──────────────────────────────────────────────────────────

    /**
     * 期貨建倉（breakdown_buy / breakout_sell）
     *
     * @param symbol      期貨代碼（如 TXF、TE）
     * @param price       建倉價格（null = 市價）
     * @param quantity    口數
     * @param entryMode   進場模式："breakdown_buy" 或 "breakout_sell"
     * @param stopLossPct 停損百分比（預設 2%）
     * @param trackLevels 追蹤檔位（1~5）
     */
    fun placeFuturesEntry(
        symbol: String,
        price: Double?,
        quantity: Int,
        entryMode: String,
        stopLossPct: Double = 2.0,
        trackLevels: Int = 1
    ) {
        val account = _uiState.value.currentAccount
        if (account == null) {
            _uiState.update { it.copy(errorMessage = "請先登入") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isOrderLoading = true, orderMessage = null, errorMessage = null) }

            try {
                val result = repository.placeFuturesOrder(
                    accountId = account.accountId,
                    symbol = symbol,
                    price = price,
                    quantity = quantity,
                    buySell = if (entryMode == "breakdown_buy") "buy" else "sell",
                    // 額外參數（Python API 支援時傳遞）
                    stopLossPct = stopLossPct,
                    trackLevels = trackLevels,
                    entryMode = entryMode
                )

                result.fold(
                    onSuccess = { orderId ->
                        _uiState.update {
                            it.copy(
                                isOrderLoading = false,
                                orderMessage = "$entryMode 建倉完成: $orderId"
                            )
                        }
                        refreshFuturesPositions()
                        checkMargin()  // 下單後重新檢查保證金
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isOrderLoading = false,
                                errorMessage = error.message ?: "下單失敗"
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

    // 便捷方法：買入（breakdown_buy）
    fun placeBreakdownBuy(
        symbol: String,
        price: Double?,
        quantity: Int,
        stopLossPct: Double = 2.0,
        trackLevels: Int = 1
    ) {
        placeFuturesEntry(symbol, price, quantity, "breakdown_buy", stopLossPct, trackLevels)
    }

    // 便捷方法：賣出（breakout_sell）
    fun placeBreakoutSell(
        symbol: String,
        price: Double?,
        quantity: Int,
        stopLossPct: Double = 2.0,
        trackLevels: Int = 1
    ) {
        placeFuturesEntry(symbol, price, quantity, "breakout_sell", stopLossPct, trackLevels)
    }

    // ──────────────────────────────────────────────────────────
    // 平倉
    // ──────────────────────────────────────────────────────────

    fun closeFuturesPosition(position: FuturesPosition) {
        val account = _uiState.value.currentAccount
        if (account == null) {
            _uiState.update { it.copy(errorMessage = "請先登入") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isOrderLoading = true, orderMessage = null) }

            try {
                // 平倉方向與進場方向相反
                val squareBuySell = if (position.direction == BuySell.Buy) "sell" else "buy"
                val result = repository.placeFuturesOrder(
                    accountId = account.accountId,
                    symbol = position.symbol,
                    price = null,  // 市價平倉
                    quantity = position.quantity,
                    buySell = squareBuySell
                )

                result.fold(
                    onSuccess = { orderId ->
                        _uiState.update {
                            it.copy(
                                isOrderLoading = false,
                                orderMessage = "平倉完成: $orderId"
                            )
                        }
                        refreshFuturesPositions()
                        checkMargin()
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isOrderLoading = false,
                                errorMessage = error.message ?: "平倉失敗"
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isOrderLoading = false,
                        errorMessage = "平倉錯誤: ${e.message}"
                    )
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // 持倉查詢
    // ──────────────────────────────────────────────────────────

    private fun refreshFuturesPositions() {
        viewModelScope.launch {
            val account = _uiState.value.currentAccount ?: return@launch
            try {
                val positions = repository.getFuturesPositions(account.accountId)
                val futuresPositions = positions.map { pos ->
                    FuturesPosition(
                        symbol = pos.symbol,
                        quantity = pos.quantity,
                        avgPrice = pos.avgPrice,
                        direction = pos.direction,
                        currentPrice = pos.currentPrice,
                        entryMode = pos.entryMode ?: "",
                        realizedPnL = pos.realizedPnL ?: 0.0,
                        pointValue = 200.0  // 預設台指期每點 200 元
                    )
                }
                _uiState.update { it.copy(futuresPositions = futuresPositions) }
            } catch (e: Exception) {
                // Silent fail for refresh
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // 13:30 收盤前檢查（期貨不自動平倉，但提醒用戶）
    // ──────────────────────────────────────────────────────────

    fun checkDeadline() {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)

        // 期貨結算時間大約 13:30，這裡做友善提醒
        if (hour >= 13 && minute >= 20) {
            val hasPositions = _uiState.value.futuresPositions.isNotEmpty()
            if (hasPositions) {
                _uiState.update {
                    it.copy(
                        errorMessage = "⚠️ 期貨收盤將近（13:30），請留意平倉時間"
                    )
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // UI 操作
    // ──────────────────────────────────────────────────────────

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearOrderMessage() {
        _uiState.update { it.copy(orderMessage = null) }
    }
}
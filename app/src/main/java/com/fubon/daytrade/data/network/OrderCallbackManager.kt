package com.fubon.daytrade.data.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 訂單狀態回調管理器（單例）
 *
 * 監聽 WebSocketClient.eventsFlow 中的 WsEvent.OrderUpdate，
 * 並透過 orderUpdatesFlow 廣播給感興趣的 ViewModel。
 */
object OrderCallbackManager {

    private const val TAG = "OrderCallbackManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // WebSocketClient 實例（由 Repository 注入）
    private var _wsClient: WebSocketClient? = null
    private val wsClient: WebSocketClient?
        get() = _wsClient

    // 是否已訂閱
    private var isSubscribed = false

    // 訂單更新 flow（廣播給所有監聽者）
    private val _orderUpdatesFlow = MutableSharedFlow<OrderUpdateEvent>(
        extraBufferCapacity = 100,
        replay = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val orderUpdatesFlow: SharedFlow<OrderUpdateEvent> = _orderUpdatesFlow.asSharedFlow()

    /**
     * 綁定 WebSocketClient，開始監聽 order_update 事件
     */
    fun bind(wsClient: WebSocketClient) {
        _wsClient = wsClient
        subscribeOrderUpdates()
    }

    /**
     * 解綁，停止監聽
     */
    fun unbind() {
        isSubscribed = false
        _wsClient = null
    }

    private fun subscribeOrderUpdates() {
        if (isSubscribed) return
        isSubscribed = true

        val client = wsClient ?: run {
            Log.w(TAG, "WebSocketClient 未綁定，無法訂閱訂單更新")
            return
        }

        scope.launch {
            client.eventsFlow.collect { event ->
                when (event) {
                    is WsEvent.OrderUpdate -> {
                        val orderEvent = OrderUpdateEvent.fromMap(event.data)
                        Log.d(TAG, "收到訂單更新: $orderEvent")
                        _orderUpdatesFlow.emit(orderEvent)
                    }
                    else -> { /* ignore */ }
                }
            }
        }
    }
}

/**
 * 訂單更新事件（結構化）
 */
data class OrderUpdateEvent(
    val orderId: String = "",
    val symbol: String = "",
    val status: OrderStatus = OrderStatus.Unknown,
    val message: String = "",
    val filledQty: Int = 0,
    val totalQty: Int = 0,
    val price: Double? = null,
    val buySell: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromMap(data: Map<String, Any?>): OrderUpdateEvent {
            return OrderUpdateEvent(
                orderId = data["order_id"]?.toString() ?: "",
                symbol = data["symbol"]?.toString() ?: "",
                status = OrderStatus.fromString(data["status"]?.toString() ?: ""),
                message = data["message"]?.toString() ?: data["msg"]?.toString() ?: "",
                filledQty = (data["filled_qty"] as? Number)?.toInt() ?: 0,
                totalQty = (data["total_qty"] as? Number)?.toInt() ?: 0,
                price = (data["price"] as? Number)?.toDouble(),
                buySell = data["buy_sell"]?.toString() ?: data["bs"]?.toString() ?: "",
                timestamp = (data["timestamp"] as? Number)?.toLong()
                    ?: System.currentTimeMillis()
            )
        }
    }
}

/**
 * 訂單狀態枚舉
 */
enum class OrderStatus {
    Unknown,
    Pending,       // 灰：等待成交
    PartiallyFilled, // 藍：部分成交
    Filled,        // 綠：已成交
    Cancelled,     // 黃：已取消
    Failed,        // 紅：失敗
    Rejected;      // 紅：拒絕

    companion object {
        fun fromString(status: String): OrderStatus {
            return when (status.lowercase().trim()) {
                "pending", "待成交", "等待成交", "queued" -> Pending
                "partial", "partial_filled", "部分成交", "partially_filled" -> PartiallyFilled
                "filled", "已成交", "成交", "completed" -> Filled
                "cancelled", "canceled", "已取消", "取消" -> Cancelled
                "failed", "失敗", "error" -> Failed
                "rejected", "拒絕" -> Rejected
                else -> Unknown
            }
        }
    }
}
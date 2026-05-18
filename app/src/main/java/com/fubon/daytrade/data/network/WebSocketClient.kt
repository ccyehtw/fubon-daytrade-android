package com.fubon.daytrade.data.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebSocket 客戶端（OkHttp WebSocket）
 *
 * 連線到 Python FastAPI 的 /ws 端點，訂閱即時股票/期貨報價。
 *
 * 使用方式：
 *   val client = WebSocketClient("ws://10.0.2.2:8080/ws")
 *   client.connect()
 *   client.subscribe(listOf("2330", "TXF"))
 *
 *   // 觀察報價
 *   client.quotesFlow.collect { tick ->
 *       Log.d("Quote", "${tick.symbol}: ${tick.price}")
 *   }
 */

/** 股票 Tick 資料 */
data class WsStockTick(
    val symbol: String = "",
    val name: String = "",
    val last_price: Double = 0.0,
    val bid_price: Double = 0.0,
    val ask_price: Double = 0.0,
    val change: Double = 0.0,
    val change_percent: Double = 0.0,
    val volume: Long = 0,
    val open_price: Double = 0.0,
    val high_price: Double = 0.0,
    val low_price: Double = 0.0,
    val limit_up_price: Double = 0.0,
    val limit_down_price: Double = 0.0,
    val timestamp: String = "",
    // internal
    val isMock: Boolean = false
)

/** 期貨 Tick 資料 */
data class WsFuturesTick(
    val symbol: String = "",
    val name: String = "",
    val last_price: Double = 0.0,
    val bid_price: Double = 0.0,
    val ask_price: Double = 0.0,
    val change: Double = 0.0,
    val change_percent: Double = 0.0,
    val volume: Long = 0,
    val open_price: Double = 0.0,
    val high_price: Double = 0.0,
    val low_price: Double = 0.0,
    val timestamp: String = "",
    // internal
    val isMock: Boolean = false
)

sealed class WsEvent {
    data class Quote(val symbol: String, val stockTick: WsStockTick? = null, val futuresTick: WsFuturesTick? = null) : WsEvent()
    data class OrderUpdate(val data: Map<String, Any?>) : WsEvent()
    data class ConditionTriggered(val data: Map<String, Any?>) : WsEvent()
    data class Connected(val clientId: String) : WsEvent()
    data class Disconnected(val reason: String) : WsEvent()
    data class Error(val message: String) : WsEvent()
    data object Pong : WsEvent()
}

class WebSocketClient(
    private val baseUrl: String = "ws://10.0.2.2:8080/ws"  // Android emulator localhost
) {
    private val tag = "WebSocketClient"

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)  // 自動 keep-alive ping
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = MutableStateFlow(false)
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelayMs = 3000L

    // 訂閱的 symbols
    private val subscribedSymbols = mutableSetOf<String>()
    private val subscribedTopics = mutableSetOf<String>()

    // 廣播 flow（所有事件）
    private val _eventsFlow = MutableSharedFlow<WsEvent>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val eventsFlow: SharedFlow<WsEvent> = _eventsFlow.asSharedFlow()

    // 股票報價 flow（symbol → tick）
    private val _stockQuotesFlow = MutableStateFlow<Map<String, WsStockTick>>(emptyMap())
    val stockQuotesFlow: StateFlow<Map<String, WsStockTick>> = _stockQuotesFlow.asStateFlow()

    // 期貨報價 flow（symbol → tick）
    private val _futuresQuotesFlow = MutableStateFlow<Map<String, WsFuturesTick>>(emptyMap())
    val futuresQuotesFlow: StateFlow<Map<String, WsFuturesTick>> = _futuresQuotesFlow.asStateFlow()

    // ══════════════════════════════════════════════════════════════
    // 連線管理
    // ══════════════════════════════════════════════════════════════

    fun connect() {
        if (webSocket != null) {
            Log.w(tag, "WebSocket 已在連線中")
            return
        }

        Log.d(tag, "連線到 $baseUrl")
        val request = Request.Builder()
            .url(baseUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "WebSocket 已連線")
                isConnected.value = true
                reconnectAttempts = 0
                // 連線後自動重訂閱之前的 symbols
                if (subscribedSymbols.isNotEmpty()) {
                    resubscribe()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    handleMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket 關閉中: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket 已關閉: $code $reason")
                isConnected.value = false
                this@WebSocketClient.webSocket = null
                scope.launch {
                    _eventsFlow.emit(WsEvent.Disconnected(reason))
                }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket 失敗: ${t.message}")
                isConnected.value = false
                this@WebSocketClient.webSocket = null
                scope.launch {
                    _eventsFlow.emit(WsEvent.Error(t.message ?: "Unknown error"))
                    _eventsFlow.emit(WsEvent.Disconnected(t.message ?: "Failure"))
                }
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        reconnectAttempts = maxReconnectAttempts  // 停止重連
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected.value = false
        subscribedSymbols.clear()
        subscribedTopics.clear()
        Log.d(tag, "已斷開 WebSocket 連線")
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.w(tag, "已達最大重連次數 ($maxReconnectAttempts)，停止重連")
            return
        }
        reconnectAttempts++
        Log.d(tag, "等待 ${reconnectDelayMs}ms 後嘗試第 $reconnectAttempts 次重連...")
        scope.launch {
            delay(reconnectDelayMs)
            if (!isConnected.value) {
                connect()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 訂閱管理
    // ══════════════════════════════════════════════════════════════

    fun subscribe(symbols: List<String>, topics: List<String> = emptyList()) {
        subscribedSymbols.addAll(symbols.map { it.uppercase() })
        subscribedTopics.addAll(topics)

        if (!isConnected.value) {
            Log.w(tag, "尚未連線，符號將在連線後自動訂閱")
            return
        }

        val msg = mapOf(
            "event" to "subscribe",
            "symbols" to symbols.map { it.uppercase() },
            "topics" to topics.ifEmpty { listOf("order_update", "condition_triggered", "auto_square", "quote_alert") }
        )
        send(msg)
    }

    fun unsubscribe(symbols: List<String>) {
        subscribedSymbols.removeAll(symbols.map { it.uppercase() }.toSet())
        if (!isConnected.value) return

        val msg = mapOf(
            "event" to "unsubscribe",
            "symbols" to symbols.map { it.uppercase() }
        )
        send(msg)
    }

    private fun resubscribe() {
        if (subscribedSymbols.isNotEmpty() || subscribedTopics.isNotEmpty()) {
            Log.d(tag, "重新訂閱: symbols=$subscribedSymbols, topics=$subscribedTopics")
            subscribe(subscribedSymbols.toList(), subscribedTopics.toList())
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 訊息發送
    // ══════════════════════════════════════════════════════════════

    fun sendPing() {
        send(mapOf("event" to "ping"))
    }

    private fun send(payload: Map<String, Any?>) {
        if (!isConnected.value) {
            Log.w(tag, "WebSocket 未連線，忽略發送: ${payload["event"]}")
            return
        }
        val json = gson.toJson(payload)
        webSocket?.send(json)
    }

    // ══════════════════════════════════════════════════════════════
    // 訊息處理
    // ══════════════════════════════════════════════════════════════

    private suspend fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val event = json.get("event")?.asString ?: return

            when (event) {
                "connected" -> {
                    val clientId = json.get("client_id")?.asString ?: ""
                    Log.d(tag, "已連線，client_id=$clientId")
                    _eventsFlow.emit(WsEvent.Connected(clientId))
                }

                "quote" -> {
                    val data = json.getAsJsonObject("data")
                    val symbol = data.get("symbol")?.asString ?: return
                    val name = data.get("name")?.asString ?: symbol

                    // 嘗試解析為股票或期貨報價
                    // 期貨有 "change" / "change_percent" 且 symbol 以 TXF/MXF 等開頭
                    val isFutures = symbol.uppercase(Locale.ROOT).startsWith("TXF") ||
                            symbol.uppercase(Locale.ROOT).startsWith("MXF") ||
                            symbol.uppercase(Locale.ROOT).startsWith("EXF") ||
                            symbol.uppercase(Locale.ROOT).startsWith("FEF") ||
                            symbol.uppercase(Locale.ROOT).startsWith("TXO")

                    if (isFutures) {
                        val tick = parseFuturesTick(data, symbol, name)
                        _futuresQuotesFlow.value = _futuresQuotesFlow.value.toMutableMap().apply {
                            put(symbol.uppercase(Locale.ROOT), tick)
                        }
                        _eventsFlow.emit(WsEvent.Quote(symbol.uppercase(Locale.ROOT), futuresTick = tick))
                    } else {
                        val tick = parseStockTick(data, symbol, name)
                        _stockQuotesFlow.value = _stockQuotesFlow.value.toMutableMap().apply {
                            put(symbol.uppercase(Locale.ROOT), tick)
                        }
                        _eventsFlow.emit(WsEvent.Quote(symbol.uppercase(Locale.ROOT), stockTick = tick))
                    }
                }

                "order_update" -> {
                    val data = json.getAsJsonObject("data")?.asMap<String, JsonElement>() ?: emptyMap()
                    _eventsFlow.emit(WsEvent.OrderUpdate(data))
                }

                "condition_triggered" -> {
                    val data = json.getAsJsonObject("data")?.asMap<String, JsonElement>() ?: emptyMap()
                    _eventsFlow.emit(WsEvent.ConditionTriggered(data))
                }

                "pong" -> {
                    _eventsFlow.emit(WsEvent.Pong)
                }

                "subscribed", "unsubscribed", "ack" -> {
                    Log.d(tag, "Server 回覆: $event")
                }

                else -> {
                    Log.d(tag, "未知的 WebSocket 事件: $event")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "解析 WebSocket 訊息失敗: ${e.message}, text=$text")
        }
    }

    private fun parseStockTick(data: com.google.gson.JsonObject, symbol: String, name: String): WsStockTick {
        return WsStockTick(
            symbol = symbol,
            name = name,
            last_price = data.get("last_price")?.asDouble ?: 0.0,
            bid_price = data.get("bid_price")?.asDouble ?: 0.0,
            ask_price = data.get("ask_price")?.asDouble ?: 0.0,
            change = data.get("change")?.asDouble ?: 0.0,
            change_percent = data.get("change_percent")?.asDouble ?: 0.0,
            volume = data.get("volume")?.asLong ?: 0L,
            open_price = data.get("open_price")?.asDouble ?: 0.0,
            high_price = data.get("high_price")?.asDouble ?: 0.0,
            low_price = data.get("low_price")?.asDouble ?: 0.0,
            limit_up_price = data.get("limit_up_price")?.asDouble ?: 0.0,
            limit_down_price = data.get("limit_down_price")?.asDouble ?: 0.0,
            timestamp = data.get("updated_at")?.asString ?: data.get("timestamp")?.asString ?: "",
            isMock = data.get("mock")?.asBoolean ?: false
        )
    }

    private fun parseFuturesTick(data: com.google.gson.JsonObject, symbol: String, name: String): WsFuturesTick {
        return WsFuturesTick(
            symbol = symbol,
            name = name,
            last_price = data.get("last_price")?.asDouble ?: 0.0,
            bid_price = data.get("bid_price")?.asDouble ?: 0.0,
            ask_price = data.get("ask_price")?.asDouble ?: 0.0,
            change = data.get("change")?.asDouble ?: 0.0,
            change_percent = data.get("change_percent")?.asDouble ?: 0.0,
            volume = data.get("volume")?.asLong ?: 0L,
            open_price = data.get("open_price")?.asDouble ?: 0.0,
            high_price = data.get("high_price")?.asDouble ?: 0.0,
            low_price = data.get("low_price")?.asDouble ?: 0.0,
            timestamp = data.get("updated_at")?.asString ?: data.get("timestamp")?.asString ?: "",
            isMock = data.get("mock")?.asBoolean ?: false
        )
    }

    // ══════════════════════════════════════════════════════════════
    // 輔助
    // ══════════════════════════════════════════════════════════════

    val connected: Boolean get() = isConnected.value

    fun getQuote(symbol: String): WsStockTick? {
        return _stockQuotesFlow.value[symbol.uppercase()]
    }

    fun getFuturesQuote(symbol: String): WsFuturesTick? {
        return _futuresQuotesFlow.value[symbol.uppercase()]
    }
}

// JsonObject 轉 Map 擴充
private fun com.google.gson.JsonObject.asMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    for ((key, value) in entrySet()) {
        result[key] = when {
            value.isJsonNull -> null
            value.isJsonPrimitive -> value.asJsonPrimitive.let { p ->
                when {
                    p.isNumber -> p.asNumber
                    p.isString -> p.asString
                    p.isBoolean -> p.asBoolean
                    else -> p.toString()
                }
            }
            value.isJsonArray -> value.asJsonArray.map { it?.toString() }
            value.isJsonObject -> value.asJsonObject.asMap()
            else -> value.toString()
        }
    }
    return result
}
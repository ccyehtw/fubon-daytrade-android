package com.fubon.daytrade.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import com.fubon.daytrade.data.model.AccountInfo
import com.fubon.daytrade.data.network.NetworkResult
import com.fubon.daytrade.data.network.OrderCallbackManager
import com.fubon.daytrade.data.network.WebSocketClient
import com.fubon.daytrade.domain.model.FuturesOrder
import com.fubon.daytrade.domain.model.Position
import com.fubon.daytrade.domain.model.StockOrder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "fubon_prefs")

@Singleton
class FubonRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : FubonRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .addHeader("X-API-Key", apiKey)
                .build()
            chain.proceed(req)
        }
        .build()

    private val gson = Gson()
    private val baseUrl = "http://35.238.60.31:8080" // GCP server external IP
    private val apiKey = "0586E47E4932C8A4A4D27AE52910384B2EBBC9D6B8773487527FE42CFF328E5C" // Fubon API Key

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("fubon_daytrade", Context.MODE_PRIVATE)
    }

    /** WebSocket 客戶端（單例，全域共享） */
    val wsClient: WebSocketClient by lazy {
        WebSocketClient(baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws")
    }

    init {
        // 綁定 WebSocketClient 到 OrderCallbackManager，啟動訂單狀態監聽
        OrderCallbackManager.bind(wsClient)
    }

    private val _accounts = MutableStateFlow<List<AccountInfo>>(emptyList())

    /** Read content URI as base64 string (for .p12 cert files) */
    private suspend fun readContentAsBase64(uriString: String): String? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.w("FubonRepo", "Failed to read cert from URI: $e")
            null
        }
    }

    override suspend fun login(
        personalId: String,
        apiKey: String,
        certPath: String,
        certPassword: String
    ): Result<List<AccountInfo>> = withContext(Dispatchers.IO) {
        // Wrap with retry for transient errors
        val networkResult = RetryHelper.retryTradingOperation {
            executeLoginRequest(personalId, apiKey, certPath, certPassword)
        }
        
        when (networkResult) {
            is NetworkResult.Success -> Result.success(networkResult.data)
            is NetworkResult.Error -> Result.failure(Exception(networkResult.message))
            is NetworkResult.NetworkError -> Result.failure(Exception("網路連線失敗: ${networkResult.exception.message}"))
            is NetworkResult.Timeout -> Result.failure(Exception("連線逾時，請稍後再試"))
        }
    }
    
    private suspend fun executeLoginRequest(
        personalId: String,
        apiKey: String,
        certPath: String,
        certPassword: String
    ): NetworkResult<List<AccountInfo>> = withContext(Dispatchers.IO) {
        try {
            // Read cert file as base64 from content URI (e.g. content://...)
            val certBase64 = readContentAsBase64(certPath)

            val requestBody = mutableMapOf(
                "personal_id" to personalId,
                "api_key" to apiKey,
                "cert_password" to certPassword
            )
            certBase64?.let { requestBody["cert_base64"] = it }

            val request = Request.Builder()
                .url("$baseUrl/api/login")
                .addHeader("X-API-Key", apiKey)
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val result = gson.fromJson(body, LoginResponse::class.java)
                    
                    if (result.isSuccess) {
                        val accounts = result.accounts?.map {
                            AccountInfo(
                                accountId = it.accountId,
                                accountType = it.accountType,
                                displayName = it.displayName
                            )
                        } ?: emptyList()
                        NetworkResult.Success(accounts)
                    } else {
                        // 401/403 are not retriable - bad credentials
                        NetworkResult.Error(result.message ?: "登入失敗", response.code)
                    }
                } else {
                    // 401/403 not retriable, 429 rate limited not retriable with backoff
                    if (response.code in listOf(401, 403, 422, 429)) {
                        NetworkResult.Error("HTTP ${response.code}: ${response.message}", response.code)
                    } else {
                        // 500, 502, 503, 504 are retriable
                        NetworkResult.Error("HTTP ${response.code}: ${response.message}", response.code)
                    }
                }
            }
        } catch (e: IOException) {
            NetworkResult.NetworkError(e)
        } catch (e: Exception) {
            NetworkResult.Error("登入錯誤: ${e.message}")
        }
    }

    override fun getWebSocketClient(): WebSocketClient = wsClient

    override suspend fun saveAccounts(accounts: List<AccountInfo>) {
        val json = gson.toJson(accounts)
        prefs.edit().putString("accounts", json).apply()
        _accounts.value = accounts
    }

    override suspend fun getAccounts(): List<AccountInfo> {
        val json = prefs.getString("accounts", null)
        return if (json != null) {
            val type = object : TypeToken<List<AccountInfo>>() {}.type
            gson.fromJson(json, type)
        } else {
            emptyList()
        }
    }

    override suspend fun placeStockOrder(
        accountId: String,
        symbol: String,
        price: Double?,
        quantity: Int,
        buySell: String
    ): Result<String> = withContext(Dispatchers.IO) {
        // Wrap with retry for transient errors
        val networkResult = RetryHelper.retryTradingOperation {
            executeStockOrderRequest(accountId, symbol, price, quantity, buySell)
        }
        
        when (networkResult) {
            is NetworkResult.Success -> Result.success(networkResult.data)
            is NetworkResult.Error -> Result.failure(Exception(networkResult.message))
            is NetworkResult.NetworkError -> Result.failure(Exception("網路連線失敗: ${networkResult.exception.message}"))
            is NetworkResult.Timeout -> Result.failure(Exception("連線逾時，請稍後再試"))
        }
    }
    
    private suspend fun executeStockOrderRequest(
        accountId: String,
        symbol: String,
        price: Double?,
        quantity: Int,
        buySell: String
    ): NetworkResult<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = mapOf(
                "account_id" to accountId,
                "symbol" to symbol,
                "price" to price,
                "quantity" to quantity,
                "buy_sell" to buySell,
                "order_type" to "DAY_TRADE"
            )

            val request = Request.Builder()
                .url("$baseUrl/api/stock/order")
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val result = gson.fromJson(body, OrderResponse::class.java)
                    
                    if (result.isSuccess) {
                        NetworkResult.Success(result.orderId ?: "ORDER_SUCCESS")
                    } else {
                        NetworkResult.Error(result.message ?: "下單失敗", response.code)
                    }
                } else {
                    NetworkResult.Error("HTTP ${response.code}: ${response.message}", response.code)
                }
            }
        } catch (e: IOException) {
            NetworkResult.NetworkError(e)
        } catch (e: Exception) {
            NetworkResult.Error("下單錯誤: ${e.message}")
        }
    }

    override suspend fun placeFuturesOrder(
        accountId: String,
        symbol: String,
        price: Double?,
        quantity: Int,
        buySell: String,
        entryMode: String?,
        stopLossPct: Double?,
        trackLevels: Int?
    ): Result<String> = withContext(Dispatchers.IO) {
        // Wrap with retry for transient errors
        val networkResult = RetryHelper.retryTradingOperation {
            executeFuturesOrderRequest(accountId, symbol, price, quantity, buySell, entryMode, stopLossPct, trackLevels)
        }
        
        when (networkResult) {
            is NetworkResult.Success -> Result.success(networkResult.data)
            is NetworkResult.Error -> Result.failure(Exception(networkResult.message))
            is NetworkResult.NetworkError -> Result.failure(Exception("網路連線失敗: ${networkResult.exception.message}"))
            is NetworkResult.Timeout -> Result.failure(Exception("連線逾時，請稍後再試"))
        }
    }
    
    private suspend fun executeFuturesOrderRequest(
        accountId: String,
        symbol: String,
        price: Double?,
        quantity: Int,
        buySell: String,
        entryMode: String?,
        stopLossPct: Double?,
        trackLevels: Int?
    ): NetworkResult<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = mutableMapOf(
                "account_id" to accountId,
                "symbol" to symbol,
                "price" to price,
                "quantity" to quantity,
                "bs" to buySell
            )
            // 加入雙模式參數（僅當有設定時）
            entryMode?.let { requestBody["entry_mode"] = it }
            stopLossPct?.let { requestBody["stop_loss_pct"] = it }
            trackLevels?.let { requestBody["track_levels"] = it }
            // 期貨最小報價單位
            requestBody["tick_size"] = 1.0

            val request = Request.Builder()
                .url("$baseUrl/futures/order")
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val result = gson.fromJson(body, OrderResponse::class.java)
                    
                    if (result.isSuccess) {
                        NetworkResult.Success(result.orderId ?: "ORDER_SUCCESS")
                    } else {
                        NetworkResult.Error(result.message ?: "下單失敗", response.code)
                    }
                } else {
                    NetworkResult.Error("HTTP ${response.code}: ${response.message}", response.code)
                }
            }
        } catch (e: IOException) {
            NetworkResult.NetworkError(e)
        } catch (e: Exception) {
            NetworkResult.Error("下單錯誤: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // WebSocket 即時報價（取代 Mock）
    // ══════════════════════════════════════════════════════════════

    override fun subscribeStockPrice(symbol: String): Flow<StockTick> {
        // 確保 WebSocket 已連線並訂閱
        if (!wsClient.connected) {
            wsClient.connect()
        }
        wsClient.subscribe(listOf(symbol))

        // 將 wsClient 的股票報價 Flow 轉換為 StockTick Flow
        return wsClient.stockQuotesFlow
            .map { tickMap -> tickMap[symbol.uppercase()] }
            .filterNotNull()
            .map { wsTick ->
                StockTick(
                    symbol = wsTick.symbol,
                    price = wsTick.last_price,
                    change = wsTick.change,
                    changePercent = wsTick.change_percent,
                    volume = wsTick.volume,
                    bid = wsTick.bid_price,
                    ask = wsTick.ask_price,
                    tickSize = 0.5,   // 股票預設
                    limitUpPrice = wsTick.limit_up_price,
                    limitDownPrice = wsTick.limit_down_price,
                    timestamp = System.currentTimeMillis()
                )
            }
    }

    override fun subscribeFuturesPrice(symbol: String): Flow<FuturesTick> {
        if (!wsClient.connected) {
            wsClient.connect()
        }
        wsClient.subscribe(listOf(symbol))

        return wsClient.futuresQuotesFlow
            .map { tickMap -> tickMap[symbol.uppercase()] }
            .filterNotNull()
            .map { wsTick ->
                FuturesTick(
                    symbol = wsTick.symbol,
                    price = wsTick.last_price,
                    change = wsTick.change,
                    changePercent = wsTick.change_percent,
                    volume = wsTick.volume,
                    bid = wsTick.bid_price,
                    ask = wsTick.ask_price,
                    tickSize = 1.0,   // 期貨預設
                    limitUpPrice = 0.0,
                    limitDownPrice = 0.0,
                    timestamp = System.currentTimeMillis()
                )
            }
    }

    override suspend fun getStockPositions(accountId: String): List<Position> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/stock/positions")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val result = gson.fromJson(body, PositionsResponse::class.java)
                    result.positions?.mapNotNull { pos ->
                        // 過濾掉 inventory 類型（沒有 direction/entryMode）
                        if (pos.type == "inventory") {
                            Position(
                                symbol = pos.symbol,
                                quantity = pos.quantity,
                                avgPrice = pos.avgPrice ?: 0.0,
                                direction = com.fubon.daytrade.domain.model.BuySell.Buy,
                                currentPrice = pos.currentPrice ?: 0.0,
                                entryMode = "",
                                realizedPnL = pos.realizedPnL ?: 0.0
                            )
                        } else {
                            Position(
                                symbol = pos.symbol,
                                quantity = pos.quantity,
                                avgPrice = pos.avgPrice ?: 0.0,
                                direction = if (pos.direction == "BUY") com.fubon.daytrade.domain.model.BuySell.Buy
                                           else com.fubon.daytrade.domain.model.BuySell.Sell,
                                currentPrice = pos.currentPrice ?: 0.0,
                                entryMode = pos.entryMode ?: "",
                                realizedPnL = pos.realizedPnL ?: 0.0
                            )
                        }
                    } ?: emptyList()
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getFuturesPositions(accountId: String): List<Position> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/futures/positions")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val result = gson.fromJson(body, PositionsResponse::class.java)
                    result.positions?.map { pos ->
                        Position(
                            symbol = pos.symbol,
                            quantity = pos.quantity,
                            avgPrice = pos.avgPrice ?: 0.0,
                            direction = if (pos.direction == "BUY") com.fubon.daytrade.domain.model.BuySell.Buy
                                       else com.fubon.daytrade.domain.model.BuySell.Sell,
                            currentPrice = pos.currentPrice ?: 0.0,
                            entryMode = pos.entryMode ?: "",
                            realizedPnL = pos.realizedPnL ?: 0.0
                        )
                    } ?: emptyList()
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getFuturesMargin(accountId: String): Double? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/futures/margin")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val result = gson.fromJson(body, MarginResponse::class.java)
                    result.margin
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun placeDayTradeEntry(
        accountId: String,
        symbol: String,
        entryMode: String,
        price: Double,
        quantity: Int,
        stopLossPct: Float,
        trackLevels: Int,
        productType: String,
        tickSize: Double
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(
                mapOf(
                    "account_id" to accountId,
                    "symbol" to symbol,
                    "entry_mode" to entryMode,
                    "price" to price,
                    "quantity" to quantity,
                    "stop_loss_pct" to stopLossPct,
                    "track_levels" to trackLevels,
                    "product_type" to productType,
                    "tick_size" to tickSize
                )
            )

            val request = Request.Builder()
                .url("$baseUrl/stock/entry")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val result = gson.fromJson(body, Map::class.java)
                    val orderId = (result["order_id"] as? String) ?: (result["success"].toString())
                    Result.success(orderId)
                } else {
                    Result.failure(Exception("下單失敗 (${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// Response data classes
data class LoginResponse(
    val isSuccess: Boolean,
    val accounts: List<AccountDto>?,
    val message: String?
)

data class AccountDto(
    val accountId: String,
    val accountType: String,
    val displayName: String
)

data class OrderResponse(
    val isSuccess: Boolean,
    val orderId: String?,
    val message: String?
)

data class PositionsResponse(
    val positions: List<PositionDto>?
)

data class PositionDto(
    val symbol: String,
    val quantity: Int,
    val avgPrice: Double? = null,
    val direction: String? = null,
    val currentPrice: Double? = null,
    val entryMode: String? = null,
    val realizedPnL: Double? = null,
    val type: String? = null  // "inventory" for real holdings, null for daytrade positions
)

data class MarginResponse(
    val margin: Double?
)
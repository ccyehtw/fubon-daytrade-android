package com.fubon.daytrade.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.fubon.daytrade.data.model.AccountInfo
import com.fubon.daytrade.data.network.NetworkResult
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
        .build()

    private val gson = Gson()
    private val baseUrl = "http://10.0.2.2:8080" // Android emulator localhost

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("fubon_daytrade", Context.MODE_PRIVATE)
    }

    private val _accounts = MutableStateFlow<List<AccountInfo>>(emptyList())

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
            is NetworkResult.TimeoutError -> Result.failure(Exception("連線逾時，請稍後再試"))
        }
    }
    
    private suspend fun executeLoginRequest(
        personalId: String,
        apiKey: String,
        certPath: String,
        certPassword: String
    ): NetworkResult<List<AccountInfo>> = withContext(Dispatchers.IO) {
        try {
            val requestBody = mapOf(
                "personal_id" to personalId,
                "api_key" to apiKey,
                "cert_path" to certPath,
                "cert_password" to certPassword
            )

            val request = Request.Builder()
                .url("$baseUrl/api/login")
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
                        NetworkResult.Error(result.message ?: "登入失敗", response.code())
                    }
                } else {
                    // 401/403 not retriable, 429 rate limited not retriable with backoff
                    if (response.code in listOf(401, 403, 422, 429)) {
                        NetworkResult.Error("HTTP ${response.code}: ${response.message}", response.code())
                    } else {
                        // 500, 502, 503, 504 are retriable
                        NetworkResult.Error("HTTP ${response.code}: ${response.message}", response.code())
                    }
                }
            }
        } catch (e: IOException) {
            NetworkResult.NetworkError(e)
        } catch (e: Exception) {
            NetworkResult.Error("登入錯誤: ${e.message}")
        }
    }

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
            is NetworkResult.TimeoutError -> Result.failure(Exception("連線逾時，請稍後再試"))
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
                        NetworkResult.Error(result.message ?: "下單失敗", response.code())
                    }
                } else {
                    NetworkResult.Error("HTTP ${response.code}: ${response.message}", response.code())
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
        buySell: String
    ): Result<String> = withContext(Dispatchers.IO) {
        // Wrap with retry for transient errors
        val networkResult = RetryHelper.retryTradingOperation {
            executeFuturesOrderRequest(accountId, symbol, price, quantity, buySell)
        }
        
        when (networkResult) {
            is NetworkResult.Success -> Result.success(networkResult.data)
            is NetworkResult.Error -> Result.failure(Exception(networkResult.message))
            is NetworkResult.NetworkError -> Result.failure(Exception("網路連線失敗: ${networkResult.exception.message}"))
            is NetworkResult.TimeoutError -> Result.failure(Exception("連線逾時，請稍後再試"))
        }
    }
    
    private suspend fun executeFuturesOrderRequest(
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
                "buy_sell" to buySell
            )

            val request = Request.Builder()
                .url("$baseUrl/api/futures/order")
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val result = gson.fromJson(body, OrderResponse::class.java)
                    
                    if (result.isSuccess) {
                        NetworkResult.Success(result.orderId ?: "ORDER_SUCCESS")
                    } else {
                        NetworkResult.Error(result.message ?: "下單失敗", response.code())
                    }
                } else {
                    NetworkResult.Error("HTTP ${response.code}: ${response.message}", response.code())
                }
            }
        } catch (e: IOException) {
            NetworkResult.NetworkError(e)
        } catch (e: Exception) {
            NetworkResult.Error("下單錯誤: ${e.message}")
        }
    }

    override suspend fun subscribeStockPrice(symbol: String): Flow<StockTick> {
        val flow = MutableStateFlow<StockTick?>(null)
        
        // For demo/mock purposes, generate simulated quotes
        // In production, this would connect to a WebSocket or polling mechanism
        kotlinx.coroutines.delay(100)
        
        val basePrice = when (symbol) {
            "2330" -> 1080.0
            "2317" -> 158.0
            "2454" -> 2280.0
            else -> 100.0 + (Math.random() * 50)
        }
        
        val change = (Math.random() - 0.5) * 10
        val changePercent = (change / basePrice) * 100
        
        val tick = StockTick(
            symbol = symbol,
            price = basePrice + change,
            change = change,
            changePercent = changePercent,
            volume = (Math.random() * 1000000).toLong(),
            bid = basePrice + change - 0.5,
            ask = basePrice + change + 0.5,
            tickSize = 0.5,
            limitUpPrice = basePrice * 1.1,
            limitDownPrice = basePrice * 0.9,
            timestamp = System.currentTimeMillis()
        )
        
        flow.value = tick
        
        return flow as Flow<StockTick>
    }

    override suspend fun subscribeFuturesPrice(symbol: String): Flow<FuturesTick> {
        val flow = MutableStateFlow<FuturesTick?>(null)
        
        kotlinx.coroutines.delay(100)
        
        val basePrice = when (symbol) {
            "TXF" -> 18000.0
            "FXF" -> 17000.0
            else -> 17000.0 + (Math.random() * 500)
        }
        
        val change = (Math.random() - 0.5) * 50
        val changePercent = (change / basePrice) * 100
        
        val tick = FuturesTick(
            symbol = symbol,
            price = basePrice + change,
            change = change,
            changePercent = changePercent,
            volume = (Math.random() * 50000).toLong(),
            bid = basePrice + change - 1,
            ask = basePrice + change + 1,
            tickSize = 1.0,
            limitUpPrice = basePrice * 1.05,
            limitDownPrice = basePrice * 0.95,
            timestamp = System.currentTimeMillis()
        )
        
        flow.value = tick
        
        return flow as Flow<FuturesTick>
    }

    suspend fun getStockPositions(accountId: String): List<Position> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/stock/positions?account_id=$accountId")
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
                            avgPrice = pos.avgPrice,
                            direction = if (pos.direction == "BUY") com.fubon.daytrade.domain.model.BuySell.Buy 
                                       else com.fubon.daytrade.domain.model.BuySell.Sell,
                            currentPrice = pos.currentPrice
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

    suspend fun getFuturesPositions(accountId: String): List<Position> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/futures/positions?account_id=$accountId")
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
                            avgPrice = pos.avgPrice,
                            direction = if (pos.direction == "BUY") com.fubon.daytrade.domain.model.BuySell.Buy 
                                       else com.fubon.daytrade.domain.model.BuySell.Sell,
                            currentPrice = pos.currentPrice
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
    val avgPrice: Double,
    val direction: String,
    val currentPrice: Double
)
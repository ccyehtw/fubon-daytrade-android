package com.fubon.daytrade.data.network

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Retrofit API client for Fubon trading API.
 * Configures timeouts, authentication, and centralized error handling.
 */
object FubonApiClient {
    
    private const val BASE_URL = "http://10.0.2.2:8080/" // Android emulator localhost
    private const val CONNECT_TIMEOUT_SEC = 15L
    private const val READ_TIMEOUT_SEC = 30L
    private const val WRITE_TIMEOUT_SEC = 15L
    
    private val gson = Gson()
    
    // Mutable token storage - in production, use encrypted storage or HSM
    private var authToken: String? = null
    
    /**
     * Sets the authentication token for API requests.
     */
    fun setAuthToken(token: String) {
        authToken = token
    }
    
    /**
     * Clears the authentication token (logout).
     */
    fun clearAuthToken() {
        authToken = null
    }
    
    /**
     * Creates OkHttpClient with interceptors for auth and error handling.
     */
    private fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor())
            .addInterceptor(ErrorHandlingInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                // 生產環境使用 NONE，正式環境除錯時才改為 BODY/BASIC
                // 避免 Authorization header 被輸出到日誌
                level = HttpLoggingInterceptor.Level.NONE
            })
            .build()
    }
    
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(createOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
    
    /**
     * Creates the API service instance.
     * Call this once and reuse the instance.
     */
    fun <T> createService(serviceClass: Class<T>): T {
        return retrofit.create(serviceClass)
    }
    
    /**
     * Interceptor that automatically adds auth header to all requests.
     */
    private class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            
            val token = authToken
            val newRequest = if (token != null) {
                originalRequest.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .build()
            } else {
                originalRequest.newBuilder()
                    .header("Content-Type", "application/json")
                    .build()
            }
            
            return chain.proceed(newRequest)
        }
    }
    
    /**
     * Interceptor that handles HTTP errors and converts them to user-friendly messages.
     * Returns standardized error responses for common HTTP status codes.
     */
    private class ErrorHandlingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            
            try {
                val response = chain.proceed(request)
                
                // For successful responses, pass through
                if (response.isSuccessful) {
                    return response
                }
                
                // Handle error responses with standardized message
                val errorMessage = when (response.code) {
                    401 -> "登入已過期，請重新登入"
                    403 -> "無權限執行此操作"
                    404 -> "找不到請求的資源"
                    409 -> "訂單衝突，請稍後再試"
                    422 -> "參數錯誤，請檢查輸入資料"
                    429 -> "請求過於頻繁，請稍後再試"
                    500 -> "伺服器錯誤，請稍後再試"
                    502 -> "服務暫時無法使用，請稍後再試"
                    503 -> "服務維護中，請稍後再試"
                    504 -> "伺服器響應逾時，請稍後再試"
                    else -> "HTTP ${response.code}: ${response.message}"
                }
                
                // Read error body for more details
                val errorBody = response.body?.string()
                val detailedMessage = if (!errorBody.isNullOrEmpty()) {
                    try {
                        val json = gson.fromJson(errorBody, Map::class.java)
                        (json["message"] as? String) ?: errorMessage
                    } catch (e: JsonSyntaxException) {
                        errorMessage
                    }
                } else {
                    errorMessage
                }
                
                // Return a modified response with standardized error format
                val errorResponse = """{"isSuccess":false,"message":"$detailedMessage","code":${response.code}}"""
                
                return response.newBuilder()
                    .code(response.code)
                    .body(errorResponse.toResponseBody(response.body?.contentType()))
                    .build()
                    
            } catch (e: IOException) {
                // Network-level errors (no connection, timeout, etc.)
                throw e
            }
        }
    }
}

/**
 * Extension function to wrap Retrofit call in NetworkResult.
 */
inline fun <T> retrofitCall(crossinline call: () -> retrofit2.Response<T>): NetworkResult<T> {
    return try {
        val response = call()
        if (response.isSuccessful) {
            response.body()?.let {
                NetworkResult.Success(it)
            } ?: NetworkResult.Error("Empty response body", response.code())
        } else {
            NetworkResult.Error(
                message = response.errorBody()?.string() ?: "Unknown error",
                code = response.code()
            )
        }
    } catch (e: IOException) {
        NetworkResult.NetworkError(e)
    } catch (e: Exception) {
        NetworkResult.Error(e.message ?: "Unknown error")
    }
}
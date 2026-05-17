package com.fubon.daytrade.data.repository

import com.fubon.daytrade.data.network.NetworkResult
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Helper class for implementing retry logic with exponential backoff.
 * 
 * Retry policy:
 * - Network errors (IOException): Always retry
 * - Timeout errors: Always retry  
 * - 5xx server errors (500, 502, 503, 504): Always retry
 * - 4xx client errors (401, 403, 404, 409, 422, 429): Never retry
 * - 3xx redirects: Never retry
 * 
 * Backoff formula: baseDelay * 2^(attemptNumber - 1) + jitter
 * With baseDelay=1000ms: 1s, 2s, 4s, ...
 */
object RetryHelper {
    
    private const val DEFAULT_MAX_RETRIES = 3
    private const val DEFAULT_BASE_DELAY_MS = 1000L
    private const val MAX_DELAY_MS = 10000L
    
    /**
     * Checks if a NetworkResult indicates a retriable error.
     */
    fun isRetriable(result: NetworkResult<*>): Boolean {
        return when (result) {
            is NetworkResult.NetworkError -> true
            is NetworkResult.Timeout -> true
            is NetworkResult.Error -> result.code in listOf(500, 502, 503, 504)
            is NetworkResult.Success -> false
        }
    }
    
    /**
     * Determines if a Throwable represents a retriable error.
     */
    fun isRetriable(throwable: Throwable): Boolean {
        return when (throwable) {
            is IOException -> true
            else -> false
        }
    }
    
    /**
     * Determines if HTTP status code indicates a retriable error.
     */
    fun isRetriableStatusCode(code: Int): Boolean {
        return code in 500..599 || code == 408
    }
    
    /**
     * Executes a suspended operation with automatic retry on retriable errors.
     * Uses exponential backoff between retries.
     * 
     * @param maxRetries Maximum number of retry attempts (default: 3)
     * @param baseDelayMs Base delay in milliseconds before first retry (default: 1000)
     * @param operation The suspended operation to execute
     * @return NetworkResult containing success data or error information
     */
    suspend fun <T> retryWithExponentialBackoff(
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
        operation: suspend () -> NetworkResult<T>
    ): NetworkResult<T> {
        var lastResult: NetworkResult<T> = NetworkResult.Error("Operation not executed")
        
        for (attempt in 1..maxRetries) {
            lastResult = operation()
            
            if (lastResult is NetworkResult.Success) {
                return lastResult
            }
            
            // Don't retry if it's not a retriable error
            if (!isRetriable(lastResult)) {
                return lastResult
            }
            
            // Don't wait after last attempt
            if (attempt < maxRetries) {
                val delayMs = calculateBackoffDelay(attempt, baseDelayMs)
                delay(delayMs)
            }
        }
        
        return lastResult
    }
    
    /**
     * Calculates the delay for a given attempt number using exponential backoff with jitter.
     * 
     * @param attemptNumber Current attempt number (1-based)
     * @param baseDelayMs Base delay in milliseconds
     * @return Delay in milliseconds to wait before next retry
     */
    private fun calculateBackoffDelay(attemptNumber: Int, baseDelayMs: Long): Long {
        val exponentialDelay = baseDelayMs * (1 shl (attemptNumber - 1))
        val cappedDelay = minOf(exponentialDelay, MAX_DELAY_MS)
        // Add jitter (±10%) to prevent thundering herd
        val jitterRange = cappedDelay / 10
        val jitter = (Math.random() * jitterRange * 2 - jitterRange).toLong()
        return (cappedDelay + jitter).coerceAtLeast(0)
    }
    
    /**
     * Retry configuration for specific operation types.
     */
    object RetryConfig {
        // Short retry for real-time quotes - should be fast
        val QUOTE_RETRY = RetryPolicy(maxRetries = 2, baseDelayMs = 500L)
        
        // Normal retry for trading operations
        val TRADING_RETRY = RetryPolicy(maxRetries = 3, baseDelayMs = 1000L)
        
        // Extended retry for order status queries
        val STATUS_RETRY = RetryPolicy(maxRetries = 3, baseDelayMs = 1500L)
    }
    
    /**
     * Data class holding retry configuration parameters.
     */
    data class RetryPolicy(
        val maxRetries: Int,
        val baseDelayMs: Long
    )
    
    /**
     * Convenience function for executing trading operations with appropriate retry settings.
     */
    suspend inline fun <T> retryTradingOperation(
        operation: suspend () -> NetworkResult<T>
    ): NetworkResult<T> {
        return retryWithExponentialBackoff(
            maxRetries = RetryConfig.TRADING_RETRY.maxRetries,
            baseDelayMs = RetryConfig.TRADING_RETRY.baseDelayMs,
            operation = operation
        )
    }
    
    /**
     * Convenience function for executing quote operations with fast retry settings.
     */
    suspend inline fun <T> retryQuoteOperation(
        operation: suspend () -> NetworkResult<T>
    ): NetworkResult<T> {
        return retryWithExponentialBackoff(
            maxRetries = RetryConfig.QUOTE_RETRY.maxRetries,
            baseDelayMs = RetryConfig.QUOTE_RETRY.baseDelayMs,
            operation = operation
        )
    }
}
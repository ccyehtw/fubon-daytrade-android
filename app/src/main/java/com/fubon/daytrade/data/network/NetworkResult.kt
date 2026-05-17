package com.fubon.daytrade.data.network

import java.io.IOException

/**
 * Sealed class representing the result of a network operation.
 * Provides type-safe error handling across the application.
 */
sealed class NetworkResult<out T> {
    /**
     * Successful network response with parsed data.
     */
    data class Success<T>(val data: T) : NetworkResult<T>()
    
    /**
     * API-level error (e.g., 400 Bad Request, 422 Validation Error, 500 Server Error).
     * Includes error message and optional HTTP status code.
     */
    data class Error(
        val message: String,
        val code: Int? = null
    ) : NetworkResult<Nothing>()
    
    /**
     * Network connectivity error (IOException, connection refused, etc.).
     */
    data class NetworkError(val exception: IOException) : NetworkResult<Nothing>()
    
    /**
     * Request timeout error (connect timeout or read timeout exceeded).
     */
    object Timeout : NetworkResult<Nothing>()
    
    /**
     * Returns true if this is a successful result.
     */
    val isSuccess: Boolean get() = this is Success
    
    /**
     * Returns true if this is a retriable error (network error or timeout).
     */
    val isRetriable: Boolean
        get() = this is NetworkError || this is Timeout || 
               (this is Error && code in listOf(500, 502, 503, 504))
    
    /**
     * Maps the successful data to a different type.
     */
    fun <R> map(transform: (T) -> R): NetworkResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is NetworkError -> this
        Timeout -> this
    }
    
    /**
     * Gets the data or null if error/timeout.
     */
    fun getOrNull(): T? = (this as? Success)?.data
    
    /**
     * Gets the data or throws exception if error/timeout.
     */
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw Exception(message)
        is NetworkError -> throw exception
        Timeout -> throw Exception("Request timeout")
    }
}
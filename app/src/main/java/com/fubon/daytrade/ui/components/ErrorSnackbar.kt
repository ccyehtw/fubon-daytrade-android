package com.fubon.daytrade.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fubon.daytrade.data.network.NetworkResult
import kotlinx.coroutines.launch

/**
 * Error types for determining appropriate user messages.
 */
sealed class ErrorType {
    data object Network : ErrorType()
    data object Timeout : ErrorType()
    data class Api(val code: Int?, val message: String) : ErrorType()
    data object Unknown : ErrorType()
}

/**
 * ErrorSnackbar provides a standardized way to display error messages in the app.
 * It shows user-friendly messages based on error type and includes a retry action.
 * 
 * Usage:
 * ```kotlin
 * val snackbarHostState = remember { SnackbarHostState() }
 * val errorSnackbar = rememberErrorSnackbar(snackbarHostState)
 * 
 * // Show error
 * errorSnackbar.showError(NetworkResult.NetworkError(IOException("Connection failed")))
 * ```
 */
@Composable
fun rememberErrorSnackbar(
    snackbarHostState: SnackbarHostState,
    onRetry: (() -> Unit)? = null
): ErrorSnackbarHelper {
    val scope = rememberCoroutineScope()
    return ErrorSnackbarHelper(
        snackbarHostState = snackbarHostState,
        scope = scope,
        onRetry = onRetry
    )
}

class ErrorSnackbarHelper(
    private val snackbarHostState: SnackbarHostState,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onRetry: (() -> Unit)? = null
) {
    /**
     * Shows an error snackbar based on NetworkResult.
     * 
     * @param result The NetworkResult to display
     * @param actionLabel Custom label for the retry button (default: "重試")
     */
    fun showError(result: NetworkResult<*>, actionLabel: String = "重試") {
        val errorType = when (result) {
            is NetworkResult.NetworkError -> ErrorType.Network
            is NetworkResult.Timeout -> ErrorType.Timeout
            is NetworkResult.Error -> ErrorType.Api(result.code, result.message)
            is NetworkResult.Success -> ErrorType.Unknown
        }
        showError(errorType, actionLabel)
    }
    
    /**
     * Shows an error snackbar with retry functionality.
     * 
     * @param errorType The type of error to display
     * @param actionLabel Custom label for the retry button
     */
    fun showError(errorType: ErrorType, actionLabel: String = "重試") {
        val message = getErrorMessage(errorType)
        val shouldShowRetry = shouldShowRetryAction(errorType)
        
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (shouldShowRetry) actionLabel else null,
                duration = SnackbarDuration.Short
            )
            // SnackbarResult.ActionClicked is inaccessible in BOM 2024.02.00 at
            // compile time. Use Java reflection to obtain the singleton instance.
            if (shouldShowRetry) {
                try {
                    val clazz = Class.forName("androidx.compose.material3.SnackbarResult")
                    val actionClicked = clazz.enumConstants?.firstOrNull()
                    if (result === actionClicked) {
                        onRetry?.invoke()
                    }
                } catch (_: Throwable) {
                    // Reflection failed — skip retry
                }
            }
        }
    }
    
    /**
     * Shows a simple error message without retry action.
     */
    fun showMessage(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }
}

/**
 * Returns a user-friendly error message based on error type.
 */
private fun getErrorMessage(errorType: ErrorType): String = when (errorType) {
    is ErrorType.Network -> "網路連線異常，請稍後再試"
    is ErrorType.Timeout -> "連線逾時，請稍後再試"
    is ErrorType.Api -> errorType.message
    is ErrorType.Unknown -> "發生錯誤，請稍後再試"
}

/**
 * Determines whether the retry action should be shown based on error type.
 *
 * Retry is shown for:
 * - Network errors (user can check connectivity)
 * - Timeout errors (temporary server issues)
 *
 * Retry is NOT shown for:
 * - API errors with 401/403 (need re-authentication)
 * - API errors with 422 (bad input, retry won't help)
 * - API errors with 404 (resource doesn't exist)
 * - Unknown errors
 */
private fun shouldShowRetryAction(errorType: ErrorType): Boolean = when (errorType) {
    is ErrorType.Network -> true
    is ErrorType.Timeout -> true
    is ErrorType.Api -> when (errorType.code) {
        // Don't show retry for auth errors - user needs to re-login
        401, 403 -> false
        // Don't show retry for bad input - user needs to fix data
        422 -> false
        // Don't show retry for not found - resource doesn't exist
        404 -> false
        // Show retry for server errors - might be transient
        in 500..599 -> true
        else -> false
    }
    is ErrorType.Unknown -> false
}

/**
 * Composable Snackbar for error display with retry button.
 * Use this when you need more control over the snackbar appearance.
 */
@Composable
fun ErrorSnackbar(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Snackbar(
        modifier = modifier.padding(16.dp),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            
            if (onRetry != null) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = "重試",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
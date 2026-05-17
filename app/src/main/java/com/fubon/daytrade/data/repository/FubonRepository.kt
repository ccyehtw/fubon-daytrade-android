package com.fubon.daytrade.data.repository

import com.fubon.daytrade.data.model.AccountInfo
import com.fubon.daytrade.data.model.LoginResult

interface FubonRepository {
    suspend fun login(
        personalId: String,
        apiKey: String,
        certPath: String,
        certPassword: String
    ): Result<List<AccountInfo>>

    suspend fun saveAccounts(accounts: List<AccountInfo>)

    suspend fun getAccounts(): List<AccountInfo>

    suspend fun placeStockOrder(
        accountId: String,
        symbol: String,
        price: Double?,
        quantity: Int,
        buySell: String // "Buy" or "Sell"
    ): Result<String>

    suspend fun placeFuturesOrder(
        accountId: String,
        symbol: String,
        price: Double?,
        quantity: Int,
        buySell: String
    ): Result<String>

    suspend fun subscribeStockPrice(symbol: String): Flow<StockTick>

    suspend fun subscribeFuturesPrice(symbol: String): Flow<FuturesTick>
}

data class StockTick(
    val symbol: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val volume: Long,
    val bid: Double,
    val ask: Double,
    val tickSize: Double,
    val limitUpPrice: Double,
    val limitDownPrice: Double,
    val timestamp: Long
)

data class FuturesTick(
    val symbol: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val volume: Long,
    val bid: Double,
    val ask: Double,
    val tickSize: Double,
    val limitUpPrice: Double,
    val limitDownPrice: Double,
    val timestamp: Long
)
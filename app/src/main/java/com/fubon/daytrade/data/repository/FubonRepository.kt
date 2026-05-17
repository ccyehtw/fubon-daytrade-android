package com.fubon.daytrade.data.repository

import com.fubon.daytrade.data.model.AccountInfo
import com.fubon.daytrade.data.model.LoginResult
import com.fubon.daytrade.domain.model.Position
import com.fubon.daytrade.data.network.WebSocketClient
import com.fubon.daytrade.data.network.WsStockTick
import com.fubon.daytrade.data.network.WsFuturesTick
import kotlinx.coroutines.flow.Flow

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

    /** 訂閱股票報價（透過 WebSocket 即時接收） */
    fun subscribeStockPrice(symbol: String): Flow<StockTick>

    /** 訂閱期貨報價（透過 WebSocket 即時接收） */
    fun subscribeFuturesPrice(symbol: String): Flow<FuturesTick>

    /** 取得 WebSocket 客戶端（用於直接管理連線生命週期） */
    fun getWebSocketClient(): WebSocketClient

    suspend fun getStockPositions(accountId: String): List<Position>

    suspend fun getFuturesPositions(accountId: String): List<Position>
}

/** 股票 Tick（Repository 層內部使用） */
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

/** 期貨 Tick（Repository 層內部使用） */
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
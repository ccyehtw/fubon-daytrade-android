package com.fubon.daytrade.domain.repository

import com.fubon.daytrade.domain.model.FuturesOrder
import com.fubon.daytrade.domain.model.Position
import com.fubon.daytrade.domain.model.StockOrder
import kotlinx.coroutines.flow.Flow

interface TradeRepository {
    suspend fun placeStockOrder(order: StockOrder): Result<String>
    suspend fun placeFuturesOrder(order: FuturesOrder): Result<String>
    suspend fun getStockPositions(accountId: String): List<Position>
    suspend fun getFuturesPositions(accountId: String): List<Position>
    fun subscribeStockPrice(symbol: String): Flow<Double>
    fun subscribeFuturesPrice(symbol: String): Flow<Double>
}
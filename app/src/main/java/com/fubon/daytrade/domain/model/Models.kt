package com.fubon.daytrade.domain.model

/**
 * Domain models for Fubon DayTrade app
 */
data class StockOrder(
    val symbol: String,
    val price: Double?,
    val quantity: Int,
    val buySell: BuySell,
    val orderType: OrderType = OrderType.DayTrade
)

enum class BuySell {
    Buy, Sell
}

enum class OrderType {
    Stock, DayTrade, Margin, SBL, Short
}

data class FuturesOrder(
    val symbol: String,
    val price: Double?,
    val quantity: Int,
    val buySell: BuySell
)

data class Position(
    val symbol: String,
    val quantity: Int,
    val avgPrice: Double,
    val direction: BuySell,
    val currentPrice: Double = 0.0,
    val entryMode: String? = null,    // "breakdown_buy" 或 "breakout_sell"
    val realizedPnL: Double? = null    // 已實現損益
) {
    val profitLoss: Double
        get() = when (direction) {
            BuySell.Buy -> (currentPrice - avgPrice) * quantity
            BuySell.Sell -> (avgPrice - currentPrice) * quantity
        }
}
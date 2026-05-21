package com.fubon.daytrade.ui.viewmodel

// ══════════════════════════════════════════════════════════════
// 條件單追蹤狀態機（期貨 / 證券 共用）
// ══════════════════════════════════════════════════════════════

enum class TrackingPhase {
    Idle,             // 無追蹤，初始狀態
    Phase1_Low_Set,   // breakdown_buy：已設定低點，等價格跌破
    Phase1_High_Set,  // breakout_sell：已設定高點，等價格突破
    Phase2_Rebound,    // breakdown_buy：已跌破低點，等反彈 N 檔
    Phase2_Retrace,    // breakout_sell：已突破高點，等回檔 N 檔
}

enum class TrackingMode {
    None,
    BreakdownBuy,   // 追低點買入（看漲）
    BreakoutSell,   // 追高點賣出（看跌）
}

data class ConditionParams(
    val mode: TrackingMode,
    val lowPrice: Double? = null,       // 低點價（breakdown_buy 用）
    val highPrice: Double? = null,      // 高點價（breakout_sell 用）
    val reboundTicks: Int = 5,           // 反彈 N 檔（breakdown_buy 用）
    val retraceTicks: Int = 5,           // 回檔 N 檔（breakout_sell 用）
    val stopLossPct: Double = 2.0,       // 停損 %
    val quantity: Int = 1,               // 口數
    val tickSize: Double = 1.0           // 期貨每點（台指期 1 點）
)
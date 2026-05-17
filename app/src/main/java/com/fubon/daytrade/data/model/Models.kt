package com.fubon.daytrade.data.model

data class AccountInfo(
    val accountId: String,
    val accountType: String, // "stock" or "futopt"
    val displayName: String
)

data class LoginResult(
    val isSuccess: Boolean,
    val accounts: List<AccountInfo>,
    val message: String?
)
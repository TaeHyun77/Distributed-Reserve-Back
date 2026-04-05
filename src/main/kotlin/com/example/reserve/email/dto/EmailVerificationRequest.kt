package com.example.reserve.email.dto

data class SendCodeRequest(
    val email: String
)

data class VerifyCodeRequest(
    val email: String,
    val code: String
)

data class VerifyCodeResponse(
    val verified: Boolean
)

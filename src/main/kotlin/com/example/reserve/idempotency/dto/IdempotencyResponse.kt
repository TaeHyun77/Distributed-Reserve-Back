package com.example.reserve.idempotency.dto

data class IdempotencyResponse (
    val statusCode: Int,

    val responseBody: String
)
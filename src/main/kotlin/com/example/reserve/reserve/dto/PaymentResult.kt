package com.example.reserve.reserve.dto

data class PaymentResult(
    val totalAmount: Long,
    val rewardDiscountAmount: Long,
    val finalAmount: Long
)
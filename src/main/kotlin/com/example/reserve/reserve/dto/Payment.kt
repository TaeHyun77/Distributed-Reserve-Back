package com.example.reserve.reserve.dto

data class Payment (
    val totalAmount: Long,
    
    val rewardDiscountAmount: Long,

    val finalAmount: Long
)
package com.example.kotlin.performance.dto

import com.example.kotlin.performance.Performance

data class PerformanceRequest(
    val type: String,

    val title: String,

    val duration: Int,

    val price: Long
) {
    fun toEntity(): Performance {
        return Performance(
            type = this.type,
            title = this.title,
            duration = this.duration,
            price = this.price
        )
    }
}
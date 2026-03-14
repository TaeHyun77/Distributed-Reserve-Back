package com.example.reserve.reserve.dto

import com.example.reserve.reserve.Reserve
import java.time.LocalDateTime

data class ReserveResponse (
    val reservationNumber: String,

    val reservedBy: String,

    val reservedSeat: List<String>,

    val totalAmount: Long,

    val rewardDiscountAmount: Long,

    val finalAmount: Long,

    val createdAt: LocalDateTime? = null
) {
    companion object {
        fun from(
            reserve: Reserve,
            seatNumbers: List<String> = reserve.seatList.map { it.seatNumber }
        ): ReserveResponse {
            return ReserveResponse(
                reservationNumber = reserve.reservationNumber,
                reservedBy = reserve.member.username,
                reservedSeat = seatNumbers,
                totalAmount = reserve.totalAmount,
                rewardDiscountAmount = reserve.rewardDiscountAmount,
                finalAmount = reserve.finalAmount,
                createdAt = reserve.createdAt
            )
        }
    }
}
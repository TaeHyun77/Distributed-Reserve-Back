package com.example.reserve.reserve.dto

import java.time.LocalDateTime

// 홀드 성공 응답. heldUntil 로 클라이언트가 결제 카운트다운을 표시한다.
data class HoldResponse(
    val seatNumbers: List<String>,

    val heldUntil: LocalDateTime,
)

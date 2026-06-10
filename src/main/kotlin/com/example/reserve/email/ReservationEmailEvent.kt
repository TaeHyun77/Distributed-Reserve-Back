package com.example.reserve.email

import com.example.reserve.email.dto.ReservationEmailData

// 예약/취소 확인 이메일 발송 요청 이벤트 (트랜잭션 커밋 후 발송)
data class ReservationEmailEvent(val data: ReservationEmailData)

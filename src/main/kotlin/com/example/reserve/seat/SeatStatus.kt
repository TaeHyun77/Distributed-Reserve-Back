package com.example.reserve.seat

// 좌석 상태: FREE(빈자리) → HELD(임시 점유) → RESERVED(확정), 취소/만료 시 FREE 로 복귀
enum class SeatStatus {
    FREE, HELD, RESERVED
}

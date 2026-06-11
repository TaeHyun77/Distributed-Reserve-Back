package com.example.reserve.idempotency

// 멱등성 처리 상태
// PENDING : 선점 후 처리 중, COMPLETED: 처리 완료(성공/실패 응답 캐싱됨)
enum class IdempotencyStatus {
    PENDING,
    COMPLETED
}

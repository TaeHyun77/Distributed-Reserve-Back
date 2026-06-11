package com.example.reserve.idempotency

import com.example.reserve.BaseTime
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import java.time.LocalDateTime

@Entity
class Idempotency(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    // 멱등키 값 ( unique 제약으로 동시 진입 상호배제 )
    @Column(unique = true)
    val idempotencyKey: String,

    // HTTP 요청 메서드
    @Column(nullable = false)
    val httpMethod: String,

    // 처리 상태 ( PENDING: 처리 중, COMPLETED: 응답 캐싱됨 )
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: IdempotencyStatus,

    // 응답 상태 코드 ( 완료 시 채워짐 )
    var statusCode: Int? = null,

    // 응답 값, JSON ( 완료 시 채워짐 )
    var responseBody: String? = null,

    // 유효 기간 ( 10분으로 설정 )
    var expiresAt: LocalDateTime
): BaseTime() {
    companion object {
        const val EXPIRATION_MINUTES = 10L
    }
}

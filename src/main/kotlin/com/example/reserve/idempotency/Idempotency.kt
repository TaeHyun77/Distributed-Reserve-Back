package com.example.reserve.idempotency

import com.example.reserve.BaseTime
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import java.time.LocalDateTime

@Entity
class Idempotency(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    // 멱등키 값
    @Column(unique = true)
    val idempotencyKey: String,

    // HTTP 요청 메서드
    @Column(nullable = false)
    val httpMethod: String,

    @Column(nullable = false)
    val statusCode: Int,

    // 응답 값, JSON
    @Column(nullable = false)
    val responseBody: String,

    // 유효 기간 ( 10분으로 설정 )
    val expiresAt: LocalDateTime
): BaseTime()
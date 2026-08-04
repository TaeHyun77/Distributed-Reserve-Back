package com.example.reserve.email.outbox

import com.example.reserve.BaseTime
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(
    name = "email_outbox",
    indexes = [Index(name = "idx_outbox_next_attempt", columnList = "next_attempt_at")]
)
class EmailOutbox(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "email_outbox_id")
    val id: Long? = null,

    // ReservationEmailData 를 직렬화한 JSON 스냅샷 (확정 시점 상태를 그대로 담는다)
    @Column(columnDefinition = "TEXT", nullable = false)
    val payload: String,

    @Column(nullable = false)
    var nextAttemptAt: LocalDateTime,
) : BaseTime() {

    // 발송 실패 시 다음 시도 시각을 미래로 미룸
    fun retryAfter(next: LocalDateTime) {
        nextAttemptAt = next
    }

    companion object {
        fun pending(payload: String, now: LocalDateTime): EmailOutbox =
            EmailOutbox(payload = payload, nextAttemptAt = now)
    }
}

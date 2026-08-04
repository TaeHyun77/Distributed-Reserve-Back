package com.example.reserve.email.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface EmailOutboxRepository : JpaRepository<EmailOutbox, Long> {

    // 발송 대기 행을 선점 조회
    // FOR UPDATE SKIP LOCKED: 다중 워커가 동시에 읽어도 서로 잠긴 행을 건너뛰어 중복 발송/대기를 방지
    @Query(
        value = """
            SELECT * FROM email_outbox
            WHERE next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findDueForDispatch(
        @Param("now") now: LocalDateTime,
        @Param("batchSize") batchSize: Int,
    ): List<EmailOutbox>
}

package com.example.reserve.email

import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

// 트랜잭션 커밋 후 이메일 발송 트리거
// 별도 빈에서 EmailService를 호출(외부 호출)하므로 @Async가 정상 동작한다
@Component
class ReservationEmailEventListener(
    private val emailService: EmailService
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handle(event: ReservationEmailEvent) {
        emailService.sendReservationEmail(event.data)
    }
}

package com.example.reserve.email.outbox

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    name = ["email.outbox.scheduler.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class EmailOutboxWorker(
    private val emailOutboxService: EmailOutboxService,
) {
    @Scheduled(fixedDelayString = "\${email.outbox.poll-interval-ms:1000}")
    fun poll() {
        emailOutboxService.dispatchBatch()
    }
}

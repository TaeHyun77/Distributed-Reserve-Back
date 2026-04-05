package com.example.reserve.email

import com.example.reserve.config.Loggable
import com.example.reserve.email.dto.ReservationEmailData
import com.example.reserve.reserve.ReserveStatus
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import java.time.format.DateTimeFormatter

// 이메일 발송 서비스
@Service
class EmailService(
    private val mailSender: JavaMailSender,
    private val templateEngine: TemplateEngine
) : Loggable {

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    // 예약/취소 확인 이메일 비동기 발송
    @Async("emailTaskExecutor")
    fun sendReservationEmail(data: ReservationEmailData) {
        try {
            val subject = when (data.status) {
                ReserveStatus.RESERVED -> "[예약 확인] ${data.performanceTitle} - ${data.reservationNumber}"
                ReserveStatus.CANCELLED -> "[예약 취소] ${data.performanceTitle} - ${data.reservationNumber}"
            }

            val body = buildEmailBody(data)

            val message: MimeMessage = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, false, "UTF-8")
            helper.setTo(data.toEmail)
            helper.setSubject(subject)
            helper.setText(body, true)

            mailSender.send(message)
            log.info { "이메일 발송 완료 - 수신: ${data.toEmail}, 예약번호: ${data.reservationNumber}" }
        } catch (e: Exception) {
            log.error(e) { "이메일 발송 실패 - 수신: ${data.toEmail}, 예약번호: ${data.reservationNumber}" }
        }
    }

    // 인증번호 이메일 비동기 발송
    @Async("emailTaskExecutor")
    fun sendVerificationCodeEmail(email: String, code: String) {
        try {
            val context = Context()
            context.setVariable("code", code)
            val body = templateEngine.process("verification-email", context)

            val message: MimeMessage = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, false, "UTF-8")
            helper.setTo(email)
            helper.setSubject("[예약 시스템] 이메일 인증번호")
            helper.setText(body, true)

            mailSender.send(message)
            log.info { "인증번호 이메일 발송 완료 - 수신: $email" }
        } catch (e: Exception) {
            log.error(e) { "인증번호 이메일 발송 실패 - 수신: $email" }
        }
    }

    // Thymeleaf 템플릿으로 HTML 본문 생성
    private fun buildEmailBody(data: ReservationEmailData): String {
        val context = Context()
        context.setVariable("isReserved", data.status == ReserveStatus.RESERVED)
        context.setVariable("memberName", data.memberName)
        context.setVariable("reservationNumber", data.reservationNumber)
        context.setVariable("performanceTitle", data.performanceTitle)
        context.setVariable("performanceType", data.performanceType)
        context.setVariable("venueName", data.venueName)
        context.setVariable("venueLocation", data.venueLocation)
        context.setVariable("startTime", data.startTime.format(DATE_FORMATTER))
        context.setVariable("endTime", data.endTime.format(DATE_FORMATTER))
        context.setVariable("seatNumbers", data.seatNumbers.joinToString(", "))
        context.setVariable("totalAmount", data.totalAmount)
        context.setVariable("rewardDiscountAmount", data.rewardDiscountAmount)
        context.setVariable("finalAmount", data.finalAmount)
        context.setVariable("reservedAt", data.reservedAt?.format(DATE_FORMATTER) ?: "-")
        context.setVariable("cancelledAt", data.cancelledAt?.format(DATE_FORMATTER) ?: "-")

        return templateEngine.process("reservation-email", context)
    }
}

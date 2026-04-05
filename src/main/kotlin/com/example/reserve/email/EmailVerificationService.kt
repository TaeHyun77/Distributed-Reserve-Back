package com.example.reserve.email

import com.example.reserve.config.Loggable
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import org.redisson.api.RedissonClient
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

// 이메일 인증번호 생성/검증 서비스
@Service
class EmailVerificationService(
    private val redissonClient: RedissonClient,
    private val emailService: EmailService
) : Loggable {

    companion object {
        private const val VERIFY_KEY_PREFIX = "email:verify:"
        private const val VERIFIED_KEY_PREFIX = "email:verified:"
        private const val CODE_TTL_MINUTES = 5L
        private const val VERIFIED_TTL_MINUTES = 10L
        private const val CODE_LENGTH = 6
    }

    // 인증번호 생성 후 이메일 발송
    fun sendVerificationCode(email: String) {
        val code = generateCode()
        val bucket = redissonClient.getBucket<String>("$VERIFY_KEY_PREFIX$email")
        bucket.set(code, CODE_TTL_MINUTES, TimeUnit.MINUTES)

        emailService.sendVerificationCodeEmail(email, code)
        log.info { "인증번호 발송 완료 - 이메일: $email" }
    }

    // 인증번호 검증 후 인증 완료 토큰 저장
    fun verifyCode(email: String, code: String) {
        val bucket = redissonClient.getBucket<String>("$VERIFY_KEY_PREFIX$email")
        val storedCode = bucket.get()
            ?: throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.VERIFICATION_CODE_EXPIRED)

        if (storedCode != code) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_VERIFICATION_CODE)
        }

        // 인증 완료 토큰 저장
        val verifiedBucket = redissonClient.getBucket<String>("$VERIFIED_KEY_PREFIX$email")
        verifiedBucket.set("true", VERIFIED_TTL_MINUTES, TimeUnit.MINUTES)

        // 사용된 인증번호 삭제
        bucket.delete()
        log.info { "이메일 인증 완료 - 이메일: $email" }
    }

    // 이메일 인증 완료 여부 확인 후 토큰 삭제
    fun isEmailVerified(email: String): Boolean {
        val bucket = redissonClient.getBucket<String>("$VERIFIED_KEY_PREFIX$email")
        val verified = bucket.get() != null
        if (verified) {
            bucket.delete()
        }
        return verified
    }

    // 6자리 숫자 인증번호 생성
    private fun generateCode(): String {
        return (100000..999999).random().toString()
    }
}

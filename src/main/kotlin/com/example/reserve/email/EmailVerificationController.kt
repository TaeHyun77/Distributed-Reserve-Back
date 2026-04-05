package com.example.reserve.email

import com.example.reserve.config.Loggable
import com.example.reserve.email.dto.SendCodeRequest
import com.example.reserve.email.dto.VerifyCodeRequest
import com.example.reserve.email.dto.VerifyCodeResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/reserve/member/email")
@RestController
class EmailVerificationController(
    private val emailVerificationService: EmailVerificationService
) : Loggable {

    // 인증번호 발송
    @PostMapping("/send-code")
    fun sendCode(@RequestBody request: SendCodeRequest): ResponseEntity<Unit> {
        emailVerificationService.sendVerificationCode(request.email)
        return ResponseEntity.ok().build()
    }

    // 인증번호 검증
    @PostMapping("/verify-code")
    fun verifyCode(@RequestBody request: VerifyCodeRequest): VerifyCodeResponse {
        emailVerificationService.verifyCode(request.email, request.code)
        return VerifyCodeResponse(verified = true)
    }
}

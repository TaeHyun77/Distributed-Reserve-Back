package com.example.kotlin.member

import com.example.kotlin.config.Loggable
import com.example.kotlin.jwt.CustomUserDetails
import com.example.kotlin.member.dto.MemberRequest
import com.example.kotlin.member.dto.MemberResponse
import com.example.kotlin.reserveException.ErrorCode
import com.example.kotlin.reserveException.ReserveException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RequestMapping("/api/member")
@RestController
class MemberController(
    private val memberService: MemberService,
): Loggable {

    // 로그인 사용자 정보 조회
    @GetMapping("/info")
    fun memberInfo(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): MemberResponse {
        return memberService.memberInfo(userDetails.username)
    }

    // 사용자 회원가입
    @PostMapping("/create")
    fun saveMember(@RequestBody memberRequest: MemberRequest) {
        return memberService.saveMember(memberRequest)
    }

    // 아이디 검증 로직
    @GetMapping("/check/validation/{username}")
    fun checkUsername(@PathVariable("username") username: String): ResponseEntity<String> =
            ResponseEntity.ok(memberService.checkUsername(username))

    // 하루 한 번 리워드 지급 로직
    @PostMapping("/get/reward")
    fun earnRewardToday(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        val idempotencyKey: String = request.getHeader("idempotency-key")
            ?: throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_IN_HEADER_IDEMPOTENCY_KEY)

        return memberService.earnRewardToday(userDetails.username, idempotencyKey)
    }
}

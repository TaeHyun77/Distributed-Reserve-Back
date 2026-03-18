package com.example.reserve.member

import com.example.reserve.config.Loggable
import com.example.reserve.jwt.CustomUserDetails
import com.example.reserve.member.dto.MemberRequest
import com.example.reserve.member.dto.MemberResponse
import com.example.reserve.member.dto.MemberRewardResponse
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RequestMapping("/reserve/member")
@RestController
class MemberController(
    private val memberService: MemberService
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
    fun saveMember(@RequestBody memberRequest: MemberRequest): ResponseEntity<Unit> {
        memberService.saveMember(memberRequest)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    // 아이디 검증 로직
    @GetMapping("/check/validation/{username}")
    fun checkUsername(@PathVariable("username") username: String): String =
        memberService.checkUsername(username)

    // 하루 한 번 리워드 지급 로직
    @PostMapping("/get/reward")
    fun earnRewardToday(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): MemberRewardResponse {
        return memberService.getTodayReward(userDetails.username)
    }
}

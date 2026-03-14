package com.example.reserve.jwt

import com.example.reserve.config.Loggable
import com.example.reserve.member.MemberRepository
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import org.springframework.http.HttpStatus
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.stereotype.Service

@Service
class CustomUserDetailService(
    private val memberRepository: MemberRepository
) : UserDetailsService, Loggable {

    override fun loadUserByUsername(username: String): UserDetails {
        val member = memberRepository.findByUsername(username)
            ?: run {
                log.info { "$username 사용자를 찾을 수 없습니다." }
                throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_MEMBER_INFO)
            }
        log.info { "로그인 사용자: $username" }

        return CustomUserDetails(member)
    }
}
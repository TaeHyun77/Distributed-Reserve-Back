
package com.example.reserve.jwt

import com.example.reserve.config.Loggable
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ErrorCodeDto
import com.example.reserve.reserveException.ReserveException
import com.example.reserve.util.createCookie
import com.example.reserve.refresh.Refresh
import com.example.reserve.refresh.RefreshRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.InternalAuthenticationServiceException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

class LoginFilter(
    private val authenticationManager: AuthenticationManager,
    private val jwtUtil: JwtUtil,
    private val refreshRepository: RefreshRepository,
    private val objectMapper: ObjectMapper
): UsernamePasswordAuthenticationFilter(), Loggable {

    override fun attemptAuthentication(request: HttpServletRequest, response: HttpServletResponse): Authentication {
        val username = request.getParameter("username")
        val password = request.getParameter("password")

        // 인증 객체 생성
        val authToken = UsernamePasswordAuthenticationToken(username, password, null)

        // Security가 이 토큰을 UserDetailsService 등과 연계해서 검증 진행
        return authenticationManager.authenticate(authToken)
    }

    override fun successfulAuthentication(request: HttpServletRequest,
                                          response: HttpServletResponse,
                                          chain: FilterChain,
                                          authentication: Authentication) {

        val userDetails: CustomUserDetails = authentication.principal as CustomUserDetails

        val username = userDetails.username
        val name = userDetails.getName()
        val email = userDetails.getEmail()
        val role = authentication.authorities.first().authority

        val accessToken = jwtUtil.createToken(username, name, email, role,"access", 600_000L)
        val refreshToken = jwtUtil.createToken(username, name, email, role,"refresh", 86_400_000L)

        val refresh = Refresh(username = username,  refresh = refreshToken, expiration = 86400000L)
        refreshRepository.save(refresh)

        response.setHeader("access", accessToken)
        response.addCookie(createCookie("refresh", refreshToken))
        response.status = HttpStatus.OK.value()
    }

    override fun unsuccessfulAuthentication(
        request: HttpServletRequest,
        response: HttpServletResponse,
        failed: AuthenticationException
    ) {
        log.error(failed) { "로그인 실패: ${failed.message}" }

        val errorCode = extractErrorCode(failed)
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"

        val errorBody = ErrorCodeDto(
            code = errorCode.errorCode,
            message = errorCode.message,
            detail = null
        )
        objectMapper.writeValue(response.writer, errorBody)
    }

    private fun extractErrorCode(failed: AuthenticationException): ErrorCode {
        val cause = (failed as? InternalAuthenticationServiceException)?.cause
        if (cause is ReserveException) {
            return cause.errorCode
        }
        // 비밀번호 불일치 등 기타 인증 실패
        return ErrorCode.LOGIN_FAILED
    }
}

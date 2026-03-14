package com.example.reserve.jwt

import com.example.reserve.config.Loggable
import com.example.reserve.member.MemberRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtFilter(
    private val jwtUtil: JwtUtil,
    private val memberRepository: MemberRepository
): OncePerRequestFilter(), Loggable {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response)
            return
        }
        val accessToken = authHeader.removePrefix("Bearer ")

        try {
            if (jwtUtil.isExpired(accessToken)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Access token expired")
                return
            }
        } catch (e: Exception) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid token")
            return
        }

        val category = jwtUtil.getCategory(accessToken)
        if (category != "access") {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Not an access token")
            return
        }

        val username: String = jwtUtil.getUsername(accessToken)

        val member = memberRepository.findByUsername(username)
            ?: run {
                log.warn { "회원 정보를 찾을 수 없습니다. username: $username" }
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Member not found")
                return
            }

        val customUserDetails = CustomUserDetails(member)
        val authToken = UsernamePasswordAuthenticationToken(
            customUserDetails, null, customUserDetails.authorities
        )
        SecurityContextHolder.getContext().authentication = authToken

        filterChain.doFilter(request, response)
    }
}
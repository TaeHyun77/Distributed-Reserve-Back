package com.example.reserve.refresh

import com.example.reserve.util.createCookie
import com.example.reserve.jwt.JwtUtil
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import io.jsonwebtoken.ExpiredJwtException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReissueService(
    private val jwtUtil: JwtUtil,
    private val refreshRepository: RefreshRepository
) {

    @Transactional
    fun reToken(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        val refreshToken = extractRefreshToken(request)

        if (jwtUtil.isExpired(refreshToken)) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.EXPIRED_TOKEN)
        }

        val category = jwtUtil.getCategory(refreshToken)
        if (category != "refresh") {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_TOKEN)
        }

        val isExist = refreshRepository.existsByRefresh(refreshToken)
        if (!isExist) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_REFRESH_TOKEN)
        }

        val username = jwtUtil.getUsername(refreshToken)
        val role = jwtUtil.getRole(refreshToken)
        val name = jwtUtil.getName(refreshToken)
        val email = jwtUtil.getEmail(refreshToken)

        val newAccess = jwtUtil.createToken(
            username = username,
            name = name,
            email = email,
            role = role.name,
            category = "access",
            expired = 600_000L,
        )

        val newRefresh = jwtUtil.createToken(
            username = username,
            name = name,
            email = email,
            role = role.name,
            category = "refresh",
            expired = 86_400_000L,
        )

        refreshRepository.deleteByRefresh(refreshToken)
        createRefresh(username, newRefresh, 86_400_000L)

        response.setHeader("access", newAccess)
        response.addCookie(createCookie("refresh", newRefresh))

        return ResponseEntity.ok()
            .build()
    }

    private fun extractRefreshToken(request: HttpServletRequest): String =
        request.cookies
            ?.firstOrNull { it.name == "refresh" }
            ?.value
            ?: throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_REFRESH_TOKEN)

    @Transactional
    fun createRefresh(username: String, refresh: String, expired: Long) {
        val refresh = Refresh(username = username, refresh = refresh, expiration = expired)

        refreshRepository.save(refresh)
    }
}
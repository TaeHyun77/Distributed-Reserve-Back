package com.example.reserve.jwt

import com.example.reserve.member.Role
import io.jsonwebtoken.Jwts
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.Date
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Component
class JwtUtil(
    @Value("\${spring.jwt.secret}") secretKey: String,
    private val request: HttpServletRequest,
) {

    private fun getClaim(token: String, key: String): String {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).payload.get(key, String::class.java)
    }

    private val secretKey: SecretKey = SecretKeySpec(
        secretKey.toByteArray(StandardCharsets.UTF_8),
        "HmacSHA256"
    )

    fun getUsername(token: String): String {
        return getClaim(token, "username")
    }

    fun getRole(token: String): Role {
        val roleName = getClaim(token, "role").removePrefix("ROLE_")

        return Role.valueOf(roleName)
    }

    fun getCategory(token: String): String {
        return getClaim(token, "category")
    }

    fun getName(token: String): String {
        return getClaim(token, "name")
    }

    fun getEmail(token: String): String {
        return getClaim(token, "email")
    }

    fun isExpired(token: String) : Boolean {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).payload.expiration.before(Date())
    }

    fun createToken(username: String, name: String, email: String, role: String, category: String, expired: Long): String {
        val now = Date()
        val expiredDate = Date(now.time + expired)

        return Jwts.builder()
            .claim("username", username)
            .claim("name", name)
            .claim("email", email)
            .claim("role", role)
            .claim("category", category)
            .expiration(expiredDate) // 토큰 만료 시간
            .issuedAt(now) // 토큰의 발급 시간
            .signWith(secretKey)
            .compact()
    }
}
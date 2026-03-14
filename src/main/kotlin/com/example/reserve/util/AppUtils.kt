package com.example.reserve.util

import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest

const val REDISSON_HOST_PREFIX: String = "redis://"
private val log = KotlinLogging.logger {}

fun createCookie(key: String, value: String): Cookie {

    return Cookie(key, value).apply {
        maxAge = 12 * 60 * 60
        isHttpOnly = false
        path = "/"
    }
}

fun parsingToken(request: HttpServletRequest): String {

    val authorization = request.getHeader("Authorization")
        ?: throw ReserveException(HttpStatus.UNAUTHORIZED, ErrorCode.NOT_EXIST_AUTHORIZATION_IN_HEADER)

    if (!authorization.startsWith("Bearer ")) {
        throw ReserveException(HttpStatus.UNAUTHORIZED, ErrorCode.NOT_EXIST_AUTHORIZATION_IN_HEADER)
    }

    val token = authorization.substring(7)

    return token
}

fun String.removeSpacesAndHyphens(): String {
    log.info { "remove spaces or Hyphens" }

    if (this.contains(' ') || this.contains('-')) {
        return this.replace("[\\s-]".toRegex(), "")
    }

    return this
}

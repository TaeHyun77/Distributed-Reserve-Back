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

fun String.removeSpacesAndHyphens(): String {
    log.info { "remove spaces or Hyphens" }

    if (this.contains(' ') || this.contains('-')) {
        return this.replace("[\\s-]".toRegex(), "")
    }

    return this
}

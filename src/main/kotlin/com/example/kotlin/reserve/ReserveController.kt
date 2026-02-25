package com.example.kotlin.reserve

import com.example.kotlin.config.Loggable
import com.example.kotlin.jwt.CustomUserDetails
import com.example.kotlin.reserve.dto.ReserveRequest
import com.example.kotlin.reserve.dto.ReserveResponse
import com.example.kotlin.reserveException.ErrorCode
import com.example.kotlin.reserveException.ReserveException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/reserve")
@RestController
class ReserveController(
    private val reserveFacadeService: ReserveFacadeService,
    private val reserveService: ReserveService
): Loggable {

    @PostMapping
    fun reserveSeat(
        @RequestBody reserveRequest: ReserveRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        httpRequest: HttpServletRequest
    ): ResponseEntity<String> {
        val idempotencyKey: String = httpRequest.getHeader("idempotency-key")
            ?: throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_IN_HEADER_IDEMPOTENCY_KEY)

        log.info { "reward : ${reserveRequest.rewardDiscountAmount}" }

        return reserveFacadeService.reserveSeat(reserveRequest, userDetails.username, idempotencyKey)
    }

    // 예약 취소
    @DeleteMapping("/delete/{reserveNumber}")
    fun cancelReservation(
        @PathVariable("reserveNumber") reserveNumber: String
    ) {
        reserveFacadeService.cancelReserve(reserveNumber)
    }

    // 예약 내역
    @GetMapping("/get/list/{username}")
    fun getUserReservations(
        @PathVariable("username") username: String
    ): List<ReserveResponse> {
        return reserveService.getUserReservations(username)
    }
}

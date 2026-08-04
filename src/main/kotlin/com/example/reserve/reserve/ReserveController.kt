package com.example.reserve.reserve

import com.example.reserve.config.Loggable
import com.example.reserve.jwt.CustomUserDetails
import com.example.reserve.reserve.dto.HoldRequest
import com.example.reserve.reserve.dto.HoldResponse
import com.example.reserve.reserve.dto.ReserveRequest
import com.example.reserve.reserve.dto.ReserveResponse
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
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

@RequestMapping("/reserve")
@RestController
class ReserveController(
    private val reserveApplicationService: ReserveApplicationService,
    private val reserveService: ReserveService
): Loggable {

    // 1단계: 좌석 홀드 ( 결제창 이동 )
    @PostMapping("/hold")
    fun hold(
        @RequestBody holdRequest: HoldRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): HoldResponse {
        holdRequest.validate()
        return reserveService.hold(holdRequest, userDetails.username)
    }

    // 2단계: 결제 확정 ( 결제창 최종 결제, 멱등성 키 필요 )
    @PostMapping("/confirm")
    fun confirm(
        @RequestBody reserveRequest: ReserveRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        httpRequest: HttpServletRequest
    ): ResponseEntity<String> {
        val idempotencyKey: String = httpRequest.getHeader("idempotency-key")
            ?: throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_IN_HEADER_IDEMPOTENCY_KEY)

        reserveRequest.validate()

        return reserveApplicationService.confirmSeat(reserveRequest, userDetails.username, idempotencyKey)
    }

    // 홀드 즉시 해제 ( 결제창 이탈 )
    @PostMapping("/release")
    fun release(
        @RequestBody holdRequest: HoldRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<Void> {
        holdRequest.validate()
        reserveService.release(holdRequest, userDetails.username)
        return ResponseEntity.noContent().build()
    }

    // 예약 취소
    @DeleteMapping("/delete/{reserveNumber}")
    fun cancelReservation(
        @PathVariable("reserveNumber") reserveNumber: String,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) = reserveService.cancelReserve(reserveNumber, userDetails.username)

    // 예약 목록 반환
    @GetMapping("/get/list")
    fun getUserReservations(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): List<ReserveResponse> {
        return reserveService.getUserReservations(userDetails.username)
    }
}

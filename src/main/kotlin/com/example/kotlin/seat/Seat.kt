package com.example.kotlin.seat

import com.example.kotlin.reserve.Reserve
import com.example.kotlin.performanceSchedule.PerformanceSchedule
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne

@Entity
class Seat(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seat_id")
    val id: Long? = null,

    // 공연 정보
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performanceSchedule_id")
    val performanceSchedule: PerformanceSchedule,

    // 좌석의 예약 정보
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reserve_id")
    var reserve: Reserve? = null,

    // 좌석 번호
    val seatNumber: String,
) {
    // 예약 여부
    val isReserved: Boolean get() = reserve != null

    // 좌석 점유
    fun occupy(reserve: Reserve) {
        this.reserve = reserve
    }

    // 좌석 점유 해제
    fun release() {
        this.reserve = null
    }
}

package com.example.reserve.seat

import com.example.reserve.member.Member
import com.example.reserve.reserve.Reserve
import com.example.reserve.performanceSchedule.PerformanceSchedule
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Table(uniqueConstraints = [UniqueConstraint(columnNames = ["performanceSchedule_id", "seatNumber"])])
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

    // 좌석의 예약 정보 ( 확정 시 연결 )
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reserve_id")
    var reserve: Reserve? = null,

    // 좌석 번호
    val seatNumber: String,

    // 좌석 상태 ( FREE=빈자리, HELD=임시 점유, RESERVED=확정 )
    @Enumerated(EnumType.STRING)
    var status: SeatStatus = SeatStatus.FREE,

    // 임시 점유(홀드) 홀더
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "held_by")
    var heldBy: Member? = null,

    // 홀드 만료 시각 — 이 시각 이후의 HELD 는 만료로 간주해 다시 점유 가능 ( 지연 만료 )
    var heldUntil: LocalDateTime? = null,
) {
    // 확정 예약 여부
    val isReserved: Boolean get() = reserve != null

    // 판매 가능 여부 ( FREE 이거나, 홀드가 만료된 HELD )
    fun isSellable(now: LocalDateTime): Boolean =
        status == SeatStatus.FREE || (status == SeatStatus.HELD && heldUntil?.isBefore(now) == true)

    // 좌석 해제 ( 취소 시 확정 좌석을 FREE 로 복귀 )
    fun release() {
        this.reserve = null
        this.status = SeatStatus.FREE
        this.heldBy = null
        this.heldUntil = null
    }
}

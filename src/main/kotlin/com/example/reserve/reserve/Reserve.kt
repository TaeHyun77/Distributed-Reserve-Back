package com.example.reserve.reserve

import com.example.reserve.BaseTime
import com.example.reserve.member.Member
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import com.example.reserve.seat.Seat
import jakarta.persistence.*
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

@Entity
class Reserve(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="reserve_id")
    val id: Long? = null,

    // 예약 번호
    @Column(name = "reservation_number", unique = true)
    val reservationNumber: String,

    // 원가
    val totalAmount: Long,

    // 예약 시 사용한 리워드 금액
    val rewardDiscountAmount: Long,

    // 총 결제 금액
    val finalAmount: Long,

    // 예약한 공연 ID
    val performanceScheduleId: Long,

    @Enumerated(EnumType.STRING)
    var status: ReserveStatus,

    var cancelledAt: LocalDateTime? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    val member: Member,

    @OneToMany(mappedBy = "reserve")
    val seatList: MutableList<Seat> = mutableListOf()
): BaseTime() {

    fun cancel() {
        if (this.status == ReserveStatus.CANCELLED) {
            throw ReserveException(HttpStatus.CONFLICT, ErrorCode.ALREADY_CANCELLED)
        }
        this.status = ReserveStatus.CANCELLED
        this.cancelledAt = LocalDateTime.now()
    }
}

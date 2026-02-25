package com.example.kotlin.reserve

import com.example.kotlin.BaseTime
import com.example.kotlin.member.Member
import com.example.kotlin.seat.Seat
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany

@Entity
class Reserve(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="reserve_id")
    val id: Long? = null,

    // 예약 번호
    val reservationNumber: String,

    // 원가
    val totalAmount: Long,

    // 예약 시 사용한 리워드 금액
    val rewardDiscountAmount: Long,

    // 총 결제 금액
    val finalAmount: Long,

    // 예약한 공연 ID
    val performanceScheduleId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    val member: Member,

    @OneToMany(mappedBy = "reserve")
    val seatList: MutableList<Seat> = mutableListOf()
): BaseTime()

package com.example.kotlin.reserve.repository

import com.example.kotlin.reserve.Reserve
import org.springframework.data.jpa.repository.JpaRepository

interface ReserveRepository: JpaRepository<Reserve, Long> {

    fun findByReservationNumber(reservationNumber: String): Reserve?

    fun findByMemberUsername(username: String): List<Reserve>
}
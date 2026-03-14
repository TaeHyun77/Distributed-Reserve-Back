package com.example.reserve.reserve.repository

import com.example.reserve.reserve.Reserve
import org.springframework.data.jpa.repository.JpaRepository

interface ReserveRepository: JpaRepository<Reserve, Long> {

    fun findByReservationNumber(reservationNumber: String): Reserve?

    fun findByMemberUsername(username: String): List<Reserve>
}
package com.example.reserve.seat.repository

import com.example.reserve.seat.Seat
import org.springframework.data.jpa.repository.JpaRepository

interface SeatRepository: JpaRepository<Seat, Long>, SeatRepositoryCustom {
}
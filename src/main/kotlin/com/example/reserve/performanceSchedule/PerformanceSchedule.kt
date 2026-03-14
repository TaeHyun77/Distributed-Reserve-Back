package com.example.reserve.performanceSchedule

import com.example.reserve.performance.Performance
import com.example.reserve.venue.Venue
import com.example.reserve.seat.Seat
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import java.time.LocalDateTime

@Entity
class PerformanceSchedule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "performanceSchedule_id")
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id")
    val venue: Venue,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_id")
    val performance: Performance,

    @OneToMany(mappedBy = "performanceSchedule", cascade = [CascadeType.ALL], orphanRemoval = true)
    val seatList: List<Seat> = emptyList(),

    val startTime: LocalDateTime,

    val endTime: LocalDateTime
)
package com.example.kotlin.venue

import com.example.kotlin.BaseTime
import com.example.kotlin.performanceSchedule.PerformanceSchedule
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany

@Entity
class Venue (

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "venue_id")
    val id: Long? = null,

    val name: String,

    val location: String,

    @OneToMany(mappedBy = "venue", cascade = [CascadeType.ALL], orphanRemoval = true)
    val performanceScheduleList: List<PerformanceSchedule> = ArrayList()
): BaseTime()
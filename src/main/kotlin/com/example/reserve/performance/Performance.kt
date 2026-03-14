package com.example.reserve.performance

import com.example.reserve.BaseTime
import com.example.reserve.performanceSchedule.PerformanceSchedule
import jakarta.persistence.*

@Entity
class Performance(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "performance_id")
    val id: Long? = null,

    val type: String,

    val title: String,

    val duration: Int,

    var price: Long,

    @OneToMany(mappedBy = "performance", cascade = [CascadeType.ALL], orphanRemoval = true)
    val performanceScheduleList: List<PerformanceSchedule> = ArrayList()
): BaseTime()
package com.example.reserve.member

import com.example.reserve.BaseTime
import com.example.reserve.reserve.Reserve
import jakarta.persistence.*
import java.time.LocalDate

@Entity
class Member(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(unique = true)
    val username: String,

    val password: String,

    val name: String,

    val role: Role,

    val email: String,

    var credit: Long = 300000,

    var reward: Long = 0L,

    var lastRewardDate: LocalDate? = null,

    @OneToMany(mappedBy = "member", cascade = [CascadeType.ALL], orphanRemoval = true)
    val reserveList: List<Reserve>? = null

): BaseTime() {

    fun decreaseCreditAndReward(credit: Long, reward: Long) {
        this.credit -= credit
        this.reward -= reward
    }

    fun increaseCreditAndReward(credit: Long, reward: Long) {
        this.credit += credit
        this.reward += reward
    }

    fun hasClaimedRewardToday(today: LocalDate) = lastRewardDate == today

    fun claimDailyReward(today: LocalDate, amount: Int) {
        this.lastRewardDate = today
        this.reward += amount
    }
}
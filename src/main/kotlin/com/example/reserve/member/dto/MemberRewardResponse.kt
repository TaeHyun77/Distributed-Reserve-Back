package com.example.reserve.member.dto

import java.time.LocalDate

data class MemberRewardResponse (
    val username: String,

    val reward: Long,

    val lastRewardDate: LocalDate?,

    val granted: Boolean // 이번 요청에서 실제로 지급되었는지
)
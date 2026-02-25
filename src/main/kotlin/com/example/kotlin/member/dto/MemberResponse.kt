package com.example.kotlin.member.dto

import com.example.kotlin.member.Member
import com.example.kotlin.member.Role
import com.example.kotlin.reserve.dto.ReserveResponse
import java.time.LocalDate

data class MemberResponse(

    val id: Long? = null,

    val username: String,

    val name: String,

    val role: Role,

    val email: String,

    var lastRewardDate: LocalDate? = null,

    val credit: Long,

    val reward: Long,

    val reserveList: List<ReserveResponse>? = null
) {
    companion object {
        fun from(member: Member): MemberResponse {
            return MemberResponse(
                id = member.id,
                username = member.username,
                name = member.name,
                role = member.role,
                email = member.email,
                lastRewardDate = member.lastRewardDate,
                reward = member.reward,
                credit = member.credit,
                reserveList = member.reserveList?.map { ReserveResponse.from(it) }
            )
        }
    }
}
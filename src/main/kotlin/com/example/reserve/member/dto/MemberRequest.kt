package com.example.reserve.member.dto

import com.example.reserve.member.Member
import com.example.reserve.member.Role

data class MemberRequest (
    val username: String,

    val password: String,

    val name: String,

    val email: String,

) {
    fun toEntity(password: String): Member {
        return Member(
            username = username,
            password = password,
            name = name,
            role = Role.MEMBER,
            email = email,
        )
    }
}
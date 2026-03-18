package com.example.reserve.member

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface MemberRepository: JpaRepository<Member, Long> {

    fun findByUsername(username: String): Member?

    fun existsByUsername(username: String): Boolean

    @Modifying
    @Query("UPDATE Member m SET m.credit = 10000000, m.reward = 0 WHERE m.username LIKE 'loadtest%'")
    fun resetCreditForLoadTest()

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.username = :username")
    fun findByUsernameWithLock(username: String): Member?
}
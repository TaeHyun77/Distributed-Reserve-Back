package com.example.reserve.refresh

import org.springframework.data.jpa.repository.JpaRepository

interface RefreshRepository: JpaRepository<Refresh, Long> {

    fun existsByRefresh(refresh: String): Boolean

    fun deleteByRefresh(refresh: String): Boolean
}
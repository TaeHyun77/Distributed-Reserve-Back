package com.example.kotlin.redis.lock

import com.example.kotlin.reserveException.ErrorCode
import com.example.kotlin.reserveException.ReserveException
import org.redisson.api.RLock
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class RedisLockUtil(
    private val lockManager: LockManager
) {
    // 단일 키용 락
    fun <T> acquireLockAndRun(
        key: String,
        task: () -> T
    ): T {
        require(key.isNotBlank()) { "Lock key는 공백이 될 수 없습니다." }

        val lock = lockManager.tryLock(key)
            ?: throw ReserveException(HttpStatus.CONFLICT, ErrorCode.FAILED_TO_ACQUIRED_LOCK)

        return runWithLock(lock, task)
    }

    // 여러 키에 대한 멀티락
    fun <T> acquireMultiLockAndRun(
        keys: List<String>,
        task: () -> T
    ): T {
        if (keys.isEmpty()) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_LOCK_KEY)
        }

        val lock = lockManager.tryMultiLock(keys)
            ?: throw ReserveException(HttpStatus.CONFLICT, ErrorCode.FAILED_TO_ACQUIRED_LOCK)

        return runWithLock(lock, task)
    }

    private fun <T> runWithLock(lock: RLock, task: () -> T): T {
        return try {
            task()
        } finally {
            lockManager.unlock(lock)
        }
    }
}
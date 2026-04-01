package com.example.reserve.redis.lock

import com.example.reserve.config.Loggable
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class LockManager(
    private val redissonClient: RedissonClient
): Loggable{

    companion object {
        private const val WAIT_TIME = 5L
        private const val LEASE_TIME = 3L
    }

    // 단일 키에 대한 락, leaseTime=3초 고정
    fun tryLock(
        key: String,
        waitTime: Long = WAIT_TIME,
        timeUnit: TimeUnit = TimeUnit.SECONDS
    ): RLock? {
        val lock = redissonClient.getLock(key)

        return if (lock.tryLock(waitTime, LEASE_TIME, timeUnit)) {
            log.debug{ "Lock 획득 성공 - key: $key" }
            lock
        } else {
            log.warn { "Lock 획득 실패 - key: $key" }
            null
        }
    }

    // 여러 키( 좌석 )에 대한 멀티락
    fun tryMultiLock(
        keys: List<String>,
        waitTime: Long = WAIT_TIME,
        timeUnit: TimeUnit = TimeUnit.SECONDS
    ): RLock? {
        // RMultiLock이 all-or-nothing으로 동작하므로, 정렬하면 동일한 좌석 조합에 대해 락 획득 순서가 일관되어서 충돌 확률이 줄어들기에 적용
        val sortedKeys = keys.sorted()
        val locks = sortedKeys.map { redissonClient.getLock(it) }

        val multiLock = redissonClient.getMultiLock(*locks.toTypedArray())

        return if (multiLock.tryLock(waitTime, LEASE_TIME, timeUnit)) {
            log.debug { "MultiLock 획득 성공 - keys: $sortedKeys" }
            multiLock
        } else {
            log.warn { "MultiLock 획득 실패 - keys: $sortedKeys, waitTime: ${waitTime}${timeUnit}" }
            null
        }
    }

    // 락 해제
    fun unlock(lock: RLock?) {
        if (lock == null) return

        try {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
                log.debug { "Lock 해제 성공" }
            } else {
                log.warn { "Lock이 현재 스레드 소유가 아닙니다. - 해제 생략" }
            }
        } catch (e: Exception) {
            log.error(e) { "Lock 해제 중 예외 발생" }
        }
    }
}
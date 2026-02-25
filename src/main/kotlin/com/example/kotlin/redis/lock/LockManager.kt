package com.example.kotlin.redis.lock

import com.example.kotlin.config.Loggable
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class LockManager(
    private val redissonClient: RedissonClient
): Loggable{

    companion object {
        private const val WAIT_TIME = 3L
        private const val LEASE_TIME = 5L
    }

    // 단일 키에 대한 락
    fun tryLock(
        key: String,
        waitTime: Long = WAIT_TIME,
        leaseTime: Long = LEASE_TIME,
        timeUnit: TimeUnit = TimeUnit.SECONDS
    ): RLock? {
        val lock = redissonClient.getLock(key)

        return if (lock.tryLock(waitTime, leaseTime, timeUnit)) {
            log.info { "Lock 획득 성공 - key: $key" }
            lock
        } else {
            null
        }
    }

    // 여러 키에 대한 멀티락
    fun tryMultiLock(
        keys: List<String>,
        waitTime: Long = 3,
        leaseTime: Long = 5,
        timeUnit: TimeUnit = TimeUnit.SECONDS
    ): RLock? {

        // 특정 key들에 대한 lock 객체
        val locks = keys.map { redissonClient.getLock(it) }

        val multiLock = redissonClient.getMultiLock(*locks.toTypedArray())

        return if (multiLock.tryLock(waitTime, leaseTime, timeUnit)) {
            log.info { "MultiLock 획득 성공 - keys: $keys" }
            multiLock
        } else {
            null
        }
    }

    fun unlock(lock: RLock?) {
        if (lock == null) return
        try {
            lock.unlock()
        } catch (e: IllegalMonitorStateException) {
            log.warn { "현재 스레드가 Lock을 보유하고 있지 않음 - leaseTime 만료 가능성" }
        } catch (e: Exception) {
            log.error(e) { "Lock 해제 실패" }
        }
    }

}
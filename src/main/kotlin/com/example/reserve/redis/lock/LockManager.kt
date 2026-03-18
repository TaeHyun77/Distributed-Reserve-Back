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
    }

    // 단일 키에 대한 락
    // leaseTime을 지정하지 않음으로써, watchdog 기능 활성화
    fun tryLock(
        key: String,
        waitTime: Long = WAIT_TIME,
        timeUnit: TimeUnit = TimeUnit.SECONDS
    ): RLock? {
        val lock = redissonClient.getLock(key)

        return if (lock.tryLock(waitTime, timeUnit)) {
            log.info { "Lock 획득 성공 - key: $key" }
            lock
        } else {
            null
        }
    }

    // 여러 키에 대한 멀티락
    fun tryMultiLock(
        keys: List<String>,
        waitTime: Long = WAIT_TIME,
        timeUnit: TimeUnit = TimeUnit.SECONDS
    ): RLock? {
        // RMultiLock이 all-or-nothing으로 동작하므로, 정렬하면 동일한 좌석 조합에 대해 락 획득 순서가 일관되어서 충돌 확률이 줄어듭니다.
        val sortedKeys = keys.sorted()

        // 특정 key들에 대한 lock 객체
        val locks = sortedKeys.map { redissonClient.getLock(it) }

        val multiLock = redissonClient.getMultiLock(*locks.toTypedArray())

        return if (multiLock.tryLock(waitTime, timeUnit)) {
            log.info { "MultiLock 획득 성공 - keys: $keys" }
            multiLock
        } else {
            null
        }
    }

    // 락 해제
    fun unlock(lock: RLock?) {
        if (lock == null) return
        try {
            lock.unlock()
        } catch (e: IllegalMonitorStateException) {
            log.warn { "Lock이 이미 해제되었거나 현재 스레드 소유가 아님" }
        } catch (e: Exception) {
            log.error(e) { "Lock 해제 실패" }
        }
    }
}
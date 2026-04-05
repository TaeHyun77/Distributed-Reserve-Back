package com.example.reserve.redis.lock.config

import com.example.reserve.util.REDISSON_HOST_PREFIX
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.redisson.config.ReadMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// RedissonClient를 사용하기 위해 Config 설정을 빈으로 등록
@Configuration
class RedisConfig(
    @Value("\${spring.redis.sentinel.master}") val master: String
) {
    @Bean
    fun redissonClient(): RedissonClient {
        val config = Config()
        config.useSentinelServers()
            .setMasterName(master)
            .addSentinelAddress(
                "redis://redis-sentinel01:26379", "redis://redis-sentinel02:26379", "redis://redis-sentinel03:26379"
            )
            .setReadMode(ReadMode.MASTER)           // 읽기 일관성 보장
            .setConnectTimeout(3000)
            .setRetryAttempts(5)                    // failover 시간 커버
            .setRetryInterval(3000).timeout = 5000  // 커맨드 응답 대기 적정선

        return Redisson.create(config)
    }
}
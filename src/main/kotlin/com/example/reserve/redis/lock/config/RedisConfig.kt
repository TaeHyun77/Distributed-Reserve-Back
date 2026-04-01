package com.example.reserve.redis.lock.config

import com.example.reserve.util.REDISSON_HOST_PREFIX
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// RedissonClient를 사용하기 위해 Config 설정을 빈으로 등록
@Configuration
class RedisConfig(
    @Value("\${spring.data.redis.host}") val host: String,
    @Value("\${spring.data.redis.port}") val port: Int
) {
    @Bean
    fun redissonClient(): RedissonClient {
        val config = Config()
        config.useSingleServer().address = "$REDISSON_HOST_PREFIX$host:$port"

        return Redisson.create(config)
    }
}
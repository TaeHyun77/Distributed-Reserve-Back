package com.example.reserve.email

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

// 이메일 비동기 발송 설정
@EnableAsync(proxyTargetClass = true)
@Configuration
class EmailConfig {

    @Bean("emailTaskExecutor")
    fun emailTaskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()

        executor.corePoolSize = 2
        executor.maxPoolSize = 5
        executor.queueCapacity = 50
        executor.setThreadNamePrefix("email-")
        executor.initialize()
        return executor
    }
}

package com.example.reserve.email

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

// 이메일 비동기 발송 설정
@EnableAsync(proxyTargetClass = true)
@Configuration
class EmailConfig {

    @Bean("emailTaskExecutor")
    fun emailTaskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()

        executor.corePoolSize = 20
        executor.maxPoolSize = 50
        executor.queueCapacity = 1000
        executor.setThreadNamePrefix("email-")
        executor.setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        executor.initialize()
        return executor
    }
}

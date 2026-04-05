
package com.example.reserve.config

import com.example.reserve.jwt.CustomLogoutFilter
import com.example.reserve.jwt.JwtFilter
import com.example.reserve.jwt.JwtUtil
import com.example.reserve.jwt.LoginFilter
import com.example.reserve.member.MemberRepository
import com.example.reserve.refresh.RefreshRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import kotlin.jvm.java

@EnableWebSecurity
@Configuration
class SecurityConfig(
    private val authenticationConfiguration: AuthenticationConfiguration,
    private val jwtUtil: JwtUtil,
    private val refreshRepository: RefreshRepository,
    private val memberRepository: MemberRepository,
    private val objectMapper: ObjectMapper
) {

    @Bean
    fun authenticationManager(): AuthenticationManager =
        authenticationConfiguration.authenticationManager

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .authorizeHttpRequests {
                it
                    // 에러 페이지
                    .requestMatchers("/error").permitAll()

                    // 인증/토큰
                    .requestMatchers("/reserve/login", "/reserve/logout", "/reserve/reToken").permitAll()

                    // 회원가입, 유효성 검사, 이메일 인증
                    .requestMatchers(HttpMethod.POST, "/reserve/member/create").permitAll()
                    .requestMatchers(HttpMethod.GET, "/reserve/member/check/validation/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/reserve/member/email/**").permitAll()

                    // 공연/좌석 조회 (읽기만 공개)
                    .requestMatchers(HttpMethod.GET, "/reserve/venue/get/list").permitAll()
                    .requestMatchers(HttpMethod.GET, "/reserve/performanceSchedule/get/list/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/reserve/seat/get/list/**").permitAll()

                    // 테스트 데이터 초기화
                    .requestMatchers(HttpMethod.POST, "/reserve/init", "/reserve/init/**", "/reserve/init/admin/**").permitAll()

                    // 관리자 전용 (생성)
                    .requestMatchers(HttpMethod.POST, "/reserve/venue/create").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/reserve/performance/create").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/reserve/performanceSchedule/create").hasRole("ADMIN")

                    // 나머지 (예약, 취소, 리워드, 내 정보 등) → 인증 필요
                    .anyRequest().authenticated()
            }
            .addFilterAt(
                LoginFilter(authenticationManager(), jwtUtil, refreshRepository, objectMapper).apply {
                    setFilterProcessesUrl("/reserve/login")
                },
                UsernamePasswordAuthenticationFilter::class.java
            )
            // LoginFilter 뒤에 JwtFilter를 위치시켜서, 로그인 요청은 JwtFilter를 거치지 않게 합니다.
            .addFilterBefore(
                JwtFilter(jwtUtil, memberRepository),
                UsernamePasswordAuthenticationFilter::class.java
            )
            .addFilterBefore(CustomLogoutFilter(jwtUtil, refreshRepository), JwtFilter::class.java)
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")
                }
            }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()

        configuration.allowedOrigins = listOf("http://localhost:3000", "http://localhost:8080")
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        configuration.allowCredentials = true
        configuration.allowedHeaders = listOf("*")
        configuration.exposedHeaders = listOf("Authorization", "access")
        configuration.maxAge = 3600L

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}

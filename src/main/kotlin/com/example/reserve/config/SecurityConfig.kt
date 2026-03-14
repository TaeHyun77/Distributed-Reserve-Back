
package com.example.reserve.config

import com.example.reserve.jwt.CustomLogoutFilter
import com.example.reserve.jwt.JwtFilter
import com.example.reserve.jwt.JwtUtil
import com.example.reserve.jwt.LoginFilter
import com.example.reserve.member.MemberRepository
import com.example.reserve.refresh.RefreshRepository
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
    private val memberRepository: MemberRepository
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
                    .requestMatchers("/api/member/**").permitAll()
                    .requestMatchers("/api/seat/**").permitAll()
                    .requestMatchers("/api/performanceSchedule/**").permitAll()
                    .requestMatchers("/api/performance/**").permitAll()
                    .requestMatchers("/api/reserve").permitAll()
                    .requestMatchers("/api/reserve/**").permitAll()
                    .requestMatchers("/api/venue/**").permitAll()
                    .requestMatchers("/login", "/logout", "/api/reToken", "/api/init").permitAll()
                    .requestMatchers("/admin").hasRole("ADMIN")
                    .anyRequest().authenticated()
            }
            .addFilterAt(
                LoginFilter(authenticationManager(), jwtUtil, refreshRepository),
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

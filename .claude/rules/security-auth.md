---
globs:
  - src/main/kotlin/com/example/reserve/jwt/**
  - src/main/kotlin/com/example/reserve/config/SecurityConfig.kt
  - src/main/kotlin/com/example/reserve/refresh/**
---

# 인증/보안 규칙

## JWT 흐름
- `LoginFilter`: POST `/reserve/login` → access + refresh 토큰 발급
- `JwtFilter`: 매 요청 시 access 토큰 검증 → `memberRepository.findByUsername()` → Authentication 설정
- `CustomLogoutFilter`: refresh 토큰 폐기 (DB 삭제 + 쿠키 제거)

## 역할
- `ADMIN`: 공연장/공연/일정 생성 (`hasRole("ADMIN")`)
- `MEMBER`: 예약/취소/리워드/내 정보

## SecurityFilterChain 순서
`CustomLogoutFilter` → `JwtFilter` → `LoginFilter`(UsernamePasswordAuthenticationFilter 위치)

## 공개 엔드포인트 (permitAll)
- 로그인/로그아웃/토큰 재발급: `/reserve/login`, `/reserve/logout`, `/reserve/reToken`
- 회원가입: POST `/reserve/member/create`
- 아이디 검증: GET `/reserve/member/check/validation/**`
- 공연/좌석 조회: GET `/reserve/venue/get/list`, `performanceSchedule/get/list/**`, `seat/get/list/**`
- 테스트 초기화: POST `/reserve/init`, `/reserve/init/**`

## 컨트롤러에서 인증 사용자 접근
```kotlin
@AuthenticationPrincipal userDetails: CustomUserDetails
// userDetails.username으로 현재 사용자 식별
```

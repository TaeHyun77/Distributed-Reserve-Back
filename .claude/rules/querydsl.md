---
globs:
  - src/main/kotlin/com/example/reserve/*/repository/*Impl.kt
  - src/main/kotlin/com/example/reserve/*/repository/*Custom.kt
  - src/main/kotlin/com/example/reserve/config/QueryDslConfig.kt
---

# QueryDSL 규칙

## Custom Repository 패턴
1. `*RepositoryCustom` 인터페이스에 메서드 시그니처 정의
2. `*RepositoryImpl` 클래스에서 `JPAQueryFactory` 주입하여 구현
3. 기본 JPA Repository가 Custom 인터페이스를 상속

## Q-class 관리
- 생성 위치: `build/generated/source/kapt/main/`
- kapt 플러그인이 컴파일 시 자동 생성하며 Kotlin source set에 자동 등록
- `./gradlew clean` 시 `build/` 전체가 삭제되며 Q-class도 함께 사라짐 → `./gradlew build`로 재생성
- IntelliJ에서 `Unresolved reference 'Q...'` 발생 시 Gradle 툴 창의 재동기화(⌘⇧I) 실행

## 기존 사용처
- `PerformanceScheduleRepositoryImpl`: 공연 일정 목록 (Performance fetch join)
- `SeatRepositoryImpl`: 스케줄+좌석번호 기반 좌석 조회
- `PerformanceRepositoryImpl`: 공연 검색/목록

## 코딩 패턴
```kotlin
val qs = QEntity.entity
queryFactory.selectFrom(qs)
    .where(qs.field.eq(value))
    .fetch()
```
- fetch join: `.join(qs.relation).fetchJoin()`
- N+1 방지: 응답에 포함되는 연관 엔티티는 반드시 fetch join

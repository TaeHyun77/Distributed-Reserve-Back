# ===== 빌드 스테이지 =====
FROM amazoncorretto:17 AS builder

WORKDIR /app

# Gradle Wrapper + 설정 먼저 복사
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./

# 의존성 먼저 다운로드 (소스 변경 시 이 레이어는 캐시됨)
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon || true

# 소스 복사 후 빌드
COPY src src
RUN ./gradlew clean bootJar -x test --no-daemon

# ===== 실행 스테이지 =====
FROM amazoncorretto:17-alpine

WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

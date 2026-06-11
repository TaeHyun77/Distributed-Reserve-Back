plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.0"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "1.9.25"

	// for querydsl
	kotlin("kapt") version "1.9.25"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}


dependencies {

	// mvc
	implementation("org.springframework.boot:spring-boot-starter-web")

	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// Mysql 의존성
	implementation("mysql:mysql-connector-java:8.0.33")

	// log 의존성
	implementation("io.github.oshai:kotlin-logging-jvm:5.1.4")

	// 시큐리티
	implementation("org.springframework.boot:spring-boot-starter-security")

	// JWT
	implementation("io.jsonwebtoken:jjwt-api:0.12.3")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.3")
	implementation("org.json:json:20230227")

	// query dsl
	implementation("com.querydsl:querydsl-jpa:5.0.0:jakarta")
	kapt("com.querydsl:querydsl-apt:5.0.0:jakarta")
	kapt("jakarta.annotation:jakarta.annotation-api")
	kapt("jakarta.persistence:jakarta.persistence-api")

	// 이메일 발송
	implementation("org.springframework.boot:spring-boot-starter-mail")

	// 이메일 HTML 템플릿
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

// QueryDSL Q-class는 kapt가 build/generated/source/kapt/main에 자동 생성하고
// kapt 플러그인이 알아서 Kotlin source set에 등록한다.
// ./gradlew clean 시 build/ 디렉토리 전체가 삭제되며 Q-class도 함께 사라진다.

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

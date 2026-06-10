package com.example.reserve.reserveException

enum class ErrorCode (
    val errorCode: String,
    val message: String
){
    UNKNOWN("000_UNKNOWN", "알 수 없는 에러 발생"),


    // NOT EXIST
    NOT_EXIST_MEMBER_INFO("NOT_EXIST_MEMBER_INFO", "사용자 정보를 찾을 수 없습니다."),

    NOT_EXIST_RESERVE_INFO("NOT_EXIST_RESERVE_INFO", "예약 정보를 찾을 수 없습니다."),

    NOT_EXIST_PLACE_INFO("NOT_EXIST_PLACE_INFO", "장소 정보를 찾을 수 없습니다."),

    NOT_EXIST_PERFORMANCE_INFO("NOT_EXIST_PERFORMANCE_INFO", "공연 정보를 찾을 수 없습니다."),

    NOT_EXIST_PERFORMANCE_SCHEDULE("NOT_EXIST_PERFORMANCE_SCHEDULE", "공연 정보를 찾을 수 없습니다."),

    NOT_EXIST_SEAT_INFO("NOT_EXIST_SEAT_INFO", "좌석 정보를 찾을 수 없습니다."),

    NOT_EXIST_AUTHORIZATION_IN_HEADER("NOT_EXIST_AUTHORIZATION_IN_HEADER", "Header에 Authorization이 존재하지 않습니다."),

    NOT_EXIST_IN_HEADER_IDEMPOTENCY_KEY("NOT_EXIST_IN_HEADER_IDEMPOTENCY_KEY", "Idempotency-Key 헤더 누락"),

    NOT_EXIST_REFRESH_TOKEN("NOT_EXIST_REFRESH_TOKEN", "refresh token이 존재하지 않습니다."),

    // TOKEN
    INVALID_TOKEN("INVALID_TOKEN", "유효하지 않은 토큰"),

    EXPIRED_TOKEN("EXPIRED_TOKEN", "만료된 토큰입니다."),

    // RESERVE
    SEAT_ALREADY_RESERVED("SEAT_ALREADY_RESERVED", "이미 예약된 좌석입니다."),

    NOT_ENOUGH_CREDIT("NOT_ENOUGH_CREDIT", "보유 금액이 부족합니다."),

    ALREADY_CANCELLED("ALREADY_CANCELLED", "이미 취소된 예약 내역입니다."),


    // JOIN
    INVALID_USERNAME("INVALID_USERNAME", "유효하지 않은 username 값 입니다."),

    DUPLICATED_USERNAME("DUPLICATED_USERNAME", "중복된 username 값 입니다."),

    // ECT
    FAILED_TO_ACQUIRED_LOCK("REDIS_FAILED_TO_ACQUIRED_LOCK", "레디스에서 Lock을 획득 실패"),

    NOT_EXIST_LOCK_KEY("REDIS_NOT_EXIST_LOCK_KEY", "레디스 lock 키가 존재하지 않습니다."),

    // AUTH
    UNAUTHORIZED_ACCESS("UNAUTHORIZED_ACCESS", "해당 작업에 대한 권한이 없습니다."),

    LOGIN_FAILED("LOGIN_FAILED", "아이디 또는 비밀번호가 올바르지 않습니다.");
}
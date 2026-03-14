package com.example.reserve.member

import com.example.reserve.config.Loggable
import com.example.reserve.idempotency.IdempotencyService
import com.example.reserve.member.dto.MemberRequest
import com.example.reserve.member.dto.MemberResponse
import com.example.reserve.member.dto.MemberRewardResponse
import com.example.reserve.redis.lock.RedisLockUtil
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId

@Service
class MemberService(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val idempotencyService: IdempotencyService,
    private val redisLockUtil: RedisLockUtil

): Loggable {
    companion object {
        private const val DAILY_REWARD_AMOUNT = 200L
        private val KST = ZoneId.of("Asia/Seoul")
    }

    // 사용자 정보 반환
    fun memberInfo(username: String): MemberResponse {
        val member = getMemberByUsername(username)
        log.info { "username: $username" }

        return MemberResponse.from(member)
    }

    // 사용자 생성
    @Transactional
    fun saveMember(memberRequest: MemberRequest) {
        if (memberRepository.existsByUsername(memberRequest.username)) {
            throw ReserveException(HttpStatus.CONFLICT, ErrorCode.DUPLICATED_USERNAME)
        }

        memberRepository.save(
            memberRequest.toEntity(passwordEncoder.encode(memberRequest.password))
        )
    }

    // 사용자 아이디 유효성 검사
    fun checkUsername(username: String): String {
        val cleaned = CheckUsername.of(username).username

        if (memberRepository.existsByUsername(cleaned)) {
            throw ReserveException(HttpStatus.CONFLICT, ErrorCode.DUPLICATED_USERNAME)
        }

        return cleaned
    }

    // 리워드 지급 로직
    fun earnRewardToday(
        username: String,
        idempotencyKey: String
    ): ResponseEntity<String> {

        val today = LocalDate.now(KST)

        // username은 중복되지 않기 때문에 락 키로 사용 가능
        return redisLockUtil.acquireLockAndRun("${today}:${username}:todayReward") {
            idempotencyService.execute(idempotencyKey, "POST") {
                processRewardEarning(username, today)
            }
        }
    }

    // 리워드 지급 실행 로직
    @Transactional
    fun processRewardEarning(
        username: String,
        today: LocalDate
    ): MemberRewardResponse {

        val member = getMemberByUsername(username)

        // 오늘 이미 리워드를 받은 경우
        if (member.hasClaimedRewardToday(today)) {
            log.info { "리워드 지급 실패 - 날짜 : ${today}, 사용자: ${member.username}" }
            throw ReserveException(HttpStatus.CONFLICT, ErrorCode.REWARD_ALREADY_CLAIMED)
        }

        // 오늘 리워드를 받지 않은 경우 지급
        member.lastRewardDate = today
        member.reward += DAILY_REWARD_AMOUNT
        log.info { "리워드 지급 성공 - $today ${member.username}님에게 리워드가 지급되었습니다." }

        return MemberRewardResponse(member.username, member.reward, member.lastRewardDate)
    }

    // 사용자 이름으로 사용자 정보 반환
    fun getMemberByUsername(username: String): Member {
        return memberRepository.findByUsername(username)
            ?: throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_MEMBER_INFO)
    }

    // 사용자 이름으로 lock
    @Transactional
    fun getMemberByUsernameWithLock(username: String): Member {
        return memberRepository.findByUsernameWithLock(username)
            ?: throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_MEMBER_INFO)
    }
}

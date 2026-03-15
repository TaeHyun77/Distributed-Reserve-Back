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
): Loggable {

    companion object {
        private const val DAILY_REWARD_AMOUNT = 200
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

    // 리워드 지급 실행 로직
    @Transactional
    fun getTodayReward(username: String): MemberRewardResponse {
        val member = getMemberByUsernameWithLock(username)
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

        val granted = if (!member.hasClaimedRewardToday(today)) {
            member.claimDailyReward(today, DAILY_REWARD_AMOUNT)
            true
        } else {
            false
        }

        return MemberRewardResponse(member.username, member.reward, member.lastRewardDate, granted)
    }

    // 사용자 이름으로 사용자 정보 반환
    fun getMemberByUsername(username: String): Member {
        return memberRepository.findByUsername(username)
            ?: throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_MEMBER_INFO)
    }

    // 사용자 이름으로 lock
    fun getMemberByUsernameWithLock(username: String): Member {
        return memberRepository.findByUsernameWithLock(username)
            ?: throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_MEMBER_INFO)
    }
}
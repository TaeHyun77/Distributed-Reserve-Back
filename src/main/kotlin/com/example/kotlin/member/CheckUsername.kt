package com.example.kotlin.member

import com.example.kotlin.util.removeSpacesAndHyphens

@JvmInline
value class CheckUsername private constructor (val username: String) {

    companion object {
        private const val MIN_LENGTH = 4
        private const val MAX_LENGTH = 12

        // 브라우저가 # 이후는 프래그먼트로 간주하여 서버에 전달하지 않음, 따라서 #은 허용하면 안됨
        // 대문자 1개 이상, 숫자 1개 이상 포함, 영문/숫자/@*^ 특수문자 만 허용, 4~12자
        private val USERNAME_REGEX = Regex("^(?=.*[A-Z])(?=.*\\d)[A-Za-z\\d@*^]{$MIN_LENGTH,$MAX_LENGTH}$")

        fun of(username: String): CheckUsername {
            // 공백과 하이픈 제거
            val cleanedUsername = username.removeSpacesAndHyphens()

            return CheckUsername(cleanedUsername)
        }
    }

    init {
        validateUsername(username)
    }

    private fun validateUsername(username: String) {

        // 정규식 ( 형식 )에 적합하지 않는다면 IllegalArgumentException 발생
        require(USERNAME_REGEX.matchEntire(username) != null) {
            "Invalid username"
        }
    }
}
package ai.moca.app.data

/**
 * 닉네임 규칙(최종정리 3장 0번): 한글·영문·숫자 2~10자.
 * 비우면 «드라이버» + 숫자 4자리를 만들어 쓴다.
 */
object NicknameRule {

    private val ALLOWED = Regex("^[가-힣a-zA-Z0-9]{2,10}$")

    fun isValid(text: String): Boolean = ALLOWED.matches(text)

    /**
     * 화면에서 받은 글자를 저장할 값으로 바꾼다.
     * 비어 있으면 «드라이버» + 네 자리, 규칙에 맞으면 앞뒤 공백을 뗀 글자, 맞지 않으면 null.
     */
    fun resolve(typed: String, fourDigits: () -> Int = { (1000..9999).random() }): String? {
        val text = typed.trim()
        if (text.isEmpty()) return DEFAULT_PREFIX + fourDigits()
        return if (isValid(text)) text else null
    }

    const val DEFAULT_PREFIX = "드라이버"
}

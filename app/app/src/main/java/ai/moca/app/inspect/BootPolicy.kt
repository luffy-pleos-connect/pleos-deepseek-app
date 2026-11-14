package ai.moca.app.inspect

/**
 * 시동 때 점검을 돌릴지 정한다.
 * 첫 실행(동의 3개는 모두 필수라 저장된 동의 값을 따로 보지 않는다)이 끝나야 점검한다.
 */
object BootPolicy {
    fun shouldInspect(onboarded: Boolean): Boolean = onboarded

    fun skipReason(onboarded: Boolean): String? =
        if (onboarded) null else "not_onboarded"
}

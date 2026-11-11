package ai.moca.app.inspect

/**
 * 시동 때 점검을 돌릴지 정한다.
 * 첫 실행(동의)이 끝나지 않았으면 하지 않는다.
 */
object BootPolicy {
    fun shouldInspect(onboarded: Boolean, consentData: Boolean): Boolean =
        onboarded && consentData

    fun skipReason(onboarded: Boolean, consentData: Boolean): String? = when {
        !onboarded -> "not_onboarded"
        !consentData -> "no_consent"
        else -> null
    }
}

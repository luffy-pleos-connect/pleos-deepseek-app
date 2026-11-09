package ai.moca.app.data

import android.content.Context
import java.util.UUID

/**
 * DataStore 대신 SharedPreferences 를 쓰는 최소 설정 저장소.
 * 계획 11장의 항목 중 이번 단계에 필요한 것만 담는다.
 * 기기 비밀값은 서버에만 보내고 화면·로그에 찍지 않는다.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("moca_prefs", Context.MODE_PRIVATE)

    var deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        private set(value) {
            sp.edit().putString(KEY_DEVICE_ID, value).apply()
        }

    /** 서버에 보내는 기기 비밀값. 절대 로그·화면에 남기지 않는다. */
    val deviceSecret: String
        get() = sp.getString(KEY_DEVICE_SECRET, null) ?: newSecret()

    private fun newSecret(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        sp.edit().putString(KEY_DEVICE_SECRET, hex).apply()
        return hex
    }

    var nickname: String
        get() = sp.getString(KEY_NICKNAME, null) ?: "드라이버${(1000..9999).random()}"
        set(value) = sp.edit().putString(KEY_NICKNAME, value).apply()

    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDED, value).apply()

    var consentData: Boolean
        get() = sp.getBoolean(KEY_CONSENT_DATA, false)
        set(value) = sp.edit().putBoolean(KEY_CONSENT_DATA, value).apply()

    var consentCloud: Boolean
        get() = sp.getBoolean(KEY_CONSENT_CLOUD, false)
        set(value) = sp.edit().putBoolean(KEY_CONSENT_CLOUD, value).apply()

    var demoEnabled: Boolean
        get() = sp.getBoolean(KEY_DEMO, false)
        set(value) = sp.edit().putBoolean(KEY_DEMO, value).apply()

    var consentBattle: Boolean
        get() = sp.getBoolean(KEY_CONSENT_BATTLE, false)
        set(value) = sp.edit().putBoolean(KEY_CONSENT_BATTLE, value).apply()

    fun applyBattleConsent(value: Boolean) {
        consentBattle = value
    }

    /** 프로필별 전적(승·무·패). key 는 "battle_real"·"battle_demo". */
    fun battleRecord(key: String): Triple<Int, Int, Int> = Triple(
        sp.getInt("${key}_w", 0),
        sp.getInt("${key}_d", 0),
        sp.getInt("${key}_l", 0),
    )

    fun setBattleRecord(key: String, win: Int, draw: Int, lose: Int) {
        sp.edit()
            .putInt("${key}_w", win)
            .putInt("${key}_d", draw)
            .putInt("${key}_l", lose)
            .apply()
    }

    var demoScenarioId: String
        get() = sp.getString(KEY_DEMO_SCENARIO, "D3") ?: "D3"
        set(value) = sp.edit().putString(KEY_DEMO_SCENARIO, value).apply()

    /** 서버 스냅샷을 지울 때 쓴다. 기기 ID는 남기고 비밀값만 새로 만든다. */
    fun rotateSecret() {
        sp.edit().remove(KEY_DEVICE_SECRET).apply()
    }

    fun clearAll() {
        sp.edit().clear().apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_SECRET = "device_secret"
        const val KEY_NICKNAME = "nickname"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_CONSENT_DATA = "consent_data"
        const val KEY_CONSENT_CLOUD = "consent_cloud"
        const val KEY_DEMO = "demo_enabled"
        const val KEY_DEMO_SCENARIO = "demo_scenario"
        const val KEY_CONSENT_BATTLE = "consent_battle"
    }
}

package ai.moca.app

import android.os.Bundle
import android.widget.Button
import ai.pleos.ds.PleosUxRestrictions
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.fragment.app.Fragment
import ai.moca.app.data.Prefs
import ai.moca.app.ui.BattleFragment
import ai.moca.app.ui.HomeFragment
import ai.moca.app.ui.InspectFragment
import ai.moca.app.ui.OnboardingFragment
import ai.moca.app.ui.SettingsFragment
import ai.moca.app.ui.StatusFragment
import ai.moca.app.ui.VoiceFragment

/**
 * FragmentActivity 1개 + Fragment 7개. NavHost 대신 수동 트랜잭션을 쓴다(의존성 최소화).
 * 창 크기가 바뀌어도 Fragment 상태는 시스템이 복원한다(configChanges 로 막지 않는다).
 */
class MainActivity : FragmentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var ux: PleosUxRestrictions
    private var drivingLimited = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(android.R.id.content).setOnTouchListener { _, e ->
            android.util.Log.i("MOCA_TOUCH", "touch ${e.action} ${e.x},${e.y}")
            false
        }
        prefs = (application as MocaApp).prefs
        ux = PleosUxRestrictions(this) { state -> applyUxRestrictions(state) }

        findViewById<Button>(R.id.tabHome).setOnClickListener { show(HOME_TAG) { HomeFragment() } }
        findViewById<Button>(R.id.tabStatus).setOnClickListener { show(STATUS_TAG) { StatusFragment() } }
        findViewById<Button>(R.id.tabVoice).setOnClickListener { show(VOICE_TAG) { VoiceFragment() } }
        findViewById<Button>(R.id.tabBattle).setOnClickListener { show(BATTLE_TAG) { BattleFragment() } }
        findViewById<Button>(R.id.tabSettings).setOnClickListener { show(SETTINGS_TAG) { SettingsFragment() } }

        if (savedInstanceState == null) {
            if (prefs.onboarded) show(HOME_TAG) { HomeFragment() } else show(ONBOARD_TAG) { OnboardingFragment() }
        }

        // 디버그 전용 자동 시연(adb 입력이 전달되지 않는 이 에뮬레이터에서 녹화하기 위한 것).
        // release 빌드에는 포함되지 않는다.
        if (intent.getBooleanExtra("moca_autodemo", false) && BuildConfig.DEBUG) {
            viewLifecycleScopeAutoDemo()
        }
    }

    private fun applyUxRestrictions(state: PleosUxRestrictions.State) {
        drivingLimited = state.distractionOptimized
        // 주행 중: 점검·상태관리·대전 진입은 막고 홈·보이스는 남긴다(DS-01, F-20).
        findViewById<Button>(R.id.tabStatus).isEnabled = !drivingLimited
        findViewById<Button>(R.id.tabBattle).isEnabled = !drivingLimited
    }

    fun isDrivingLimited(): Boolean = drivingLimited

    override fun onStart() {
        super.onStart()
        ux.start()
    }

    override fun onStop() {
        ux.stop()
        super.onStop()
    }

    private fun viewLifecycleScopeAutoDemo() {
        lifecycleScope.launch {
            delay(1500)
            prefs.consentData = true
            prefs.consentCloud = true
            prefs.applyBattleConsent(true)
            android.util.Log.i("MOCA_AUTODEMO", "start")
            prefs.onboarded = true
            goHome()
            delay(4000)
            goInspect()
            delay(5000)
            goStatus()
            delay(3000)
            goBattle()
            delay(3500)
            (supportFragmentManager.findFragmentByTag(BATTLE_TAG) as? BattleFragment)?.autoStart()
            delay(14000)
            goVoice()
            delay(5000)
            autoAskVoice("경고등 뭐야?")
        }
    }

    /** 보이스 화면이 뜬 뒤 자동으로 질문을 넣는다(디버그 전용). */
    private fun autoAskVoice(question: String) {
        val f = supportFragmentManager.findFragmentByTag(VOICE_TAG) as? VoiceFragment ?: return
        f.autoAsk(question)
    }

    fun goHome() = show(HOME_TAG) { HomeFragment() }

    fun goStatus() = show(STATUS_TAG) { StatusFragment() }

    fun goInspect() = show(INSPECT_TAG) { InspectFragment() }

    fun goVoice() = show(VOICE_TAG) { VoiceFragment() }

    fun goBattle() = show(BATTLE_TAG) { BattleFragment() }

    fun goOnboarding() = show(ONBOARD_TAG) { OnboardingFragment() }

    private fun show(tag: String, create: () -> Fragment) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        val tx = supportFragmentManager.beginTransaction()
        // 화면 전환마다 컨테이너의 모든 Fragment 를 replace 해 겹침을 없앤다.
        // 상태는 Fragment 의 ViewModel·SavedState 가 아니라 화면 데이터 재조회로 복원한다.
        val target = existing ?: create()
        tx.replace(R.id.navHost, target, tag)
        tx.commit()
        updateDemoBadge()
    }

    private fun updateDemoBadge() {
        val badge = findViewById<android.widget.TextView>(R.id.demoBadge)
        badge.visibility = if (prefs.demoEnabled) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) updateDemoBadge()
    }

    private companion object {
        const val HOME_TAG = "home"
        const val STATUS_TAG = "status"
        const val VOICE_TAG = "voice"
        const val BATTLE_TAG = "battle"
        const val SETTINGS_TAG = "settings"
        const val ONBOARD_TAG = "onboarding"
        const val INSPECT_TAG = "inspect"
    }
}

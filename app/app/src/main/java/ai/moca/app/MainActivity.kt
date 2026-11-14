package ai.moca.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import ai.pleos.ds.PleosUxRestrictions
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.Fragment
import ai.moca.app.data.Prefs
import ai.moca.app.ui.BattleFragment
import ai.moca.app.ui.HomeFragment
import ai.moca.app.ui.OnboardingFragment
import ai.moca.app.ui.RankingFragment
import ai.moca.app.ui.SettingsFragment
import ai.moca.app.ui.StartFragment
import ai.moca.app.ui.StatusFragment
import ai.moca.app.ui.VoiceFragment
import ai.moca.app.ui.DrivingLimitPopup
import moca.core.Category

/**
 * FragmentActivity 1개 + Fragment. NavHost 대신 수동 트랜잭션을 쓴다(의존성 최소화).
 * 창 크기가 바뀌어도 Fragment 상태는 시스템이 복원한다(configChanges 로 막지 않는다).
 */
class MainActivity : FragmentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var ux: PleosUxRestrictions
    private var drivingLimited = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var suppressTabCallback = false
    private var currentTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = (application as MocaApp).prefs
        ux = PleosUxRestrictions(this) { state -> applyUxRestrictions(state) }

        findViewById<RadioGroup>(R.id.tabBar).setOnCheckedChangeListener { _, checkedId ->
            if (suppressTabCallback) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.tabHome -> show(HOME_TAG) { HomeFragment() }
                R.id.tabStatus -> openGuarded(STATUS_TAG) { StatusFragment() }
                R.id.tabVoice -> show(VOICE_TAG) { VoiceFragment() }
                R.id.tabBattle -> openGuarded(BATTLE_TAG) { BattleFragment() }
                R.id.tabSettings -> openGuarded(SETTINGS_TAG) { SettingsFragment() }
            }
        }

        currentTag = savedInstanceState?.getString(KEY_TAG)
        if (savedInstanceState == null) {
            when {
                !prefs.onboarded -> show(ONBOARD_TAG) { OnboardingFragment() }
                else -> show(START_TAG) { StartFragment() }
            }
        }

        debugAsk(intent)
    }

    /**
     * 주행 중 차단(평가기준 C-02).
     * 탭을 끄지 않는다. 누르면 [DrivingLimitPopup] 이 음성으로 묻는 길을 알려 준다.
     */
    private fun applyUxRestrictions(state: PleosUxRestrictions.State) {
        drivingLimited = state.distractionOptimized
        // 보이스챗은 주행 상태가 바뀌면 키보드 입력 칸을 바로 막거나 되돌린다.
        mainHandler.post {
            (supportFragmentManager.findFragmentByTag(VOICE_TAG) as? VoiceFragment)?.setDrivingLimited(drivingLimited)
        }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugAsk(intent)
    }

    /**
     * 디버그 전용: adb 로 한글을 칠 수 없어, 검증·녹화 때 보이스챗 질문을 인텐트로 넣는다.
     * `am start --activity-single-top -n ai.moca.app/.MainActivity --es moca_ask "질문"`. release 에서는 아무 일도 하지 않는다.
     */
    private fun debugAsk(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val question = intent?.getStringExtra("moca_ask") ?: return
        (supportFragmentManager.findFragmentByTag(VOICE_TAG) as? VoiceFragment)?.debugAsk(question)
    }

    fun goHome() = show(HOME_TAG) { HomeFragment() }

    /** 앱 시작 화면으로. 동의를 막 끝낸 직후에 부른다. */
    fun goStart() = show(START_TAG) { StartFragment() }

    fun goStatus(category: Category? = null) {
        if (category == null) {
            show(STATUS_TAG) { StatusFragment() }
        } else {
            show(STATUS_TAG, forceNew = true) {
                StatusFragment().apply {
                    arguments = Bundle().apply {
                        putString(StatusFragment.ARG_CATEGORY, category.name)
                    }
                }
            }
        }
    }

    fun goVoice() = show(VOICE_TAG) { VoiceFragment() }

    /**
     * 상태관리의 [AI에게 물어보기]. 항목 정보를 들고 보이스챗을 연다(최종정리 11.1절).
     * 새 화면을 만들지 않고, 이미 떠 있는 보이스챗이 있으면 그것에 인자를 넘긴다.
     */
    fun openVoiceWithItem(item: Bundle) {
        show(VOICE_TAG, forceNew = true) {
            VoiceFragment().apply { arguments = item }
        }
    }

    /** 월간랭킹의 [질문하기]: 이번 달 관리 요약을 들고 보이스챗으로 간다(최종정리 11.1절). */
    fun openVoiceWithCare(care: Bundle) {
        show(VOICE_TAG, forceNew = true) {
            VoiceFragment().apply { arguments = care }
        }
    }

    fun goBattle() = show(BATTLE_TAG) { BattleFragment() }

    /** 월간랭킹. 하단 탭은 대전하기가 선택된 채로 둔다. */
    fun goRanking() = show(RANKING_TAG) { RankingFragment() }

    fun goSettings() = show(SETTINGS_TAG) { SettingsFragment() }

    /**
     * 주행 중에 들어갈 수 없는 화면의 탭. 화면은 바꾸지 않고 안내만 띄운다.
     * 탭의 선택 표시는 지금 화면으로 되돌린다.
     */
    private fun openGuarded(tag: String, create: () -> Fragment) {
        if (drivingLimited) {
            DrivingLimitPopup.show(this) { goVoice() }
            currentTag?.let { selectTab(it) }
            return
        }
        show(tag, create = create)
    }

    private fun show(tag: String, forceNew: Boolean = false, create: () -> Fragment) {
        val existing = if (forceNew) null else supportFragmentManager.findFragmentByTag(tag)
        val tx = supportFragmentManager.beginTransaction()
        // 화면 전환마다 컨테이너의 모든 Fragment 를 replace 해 겹침을 없앤다.
        // 상태는 Fragment 의 ViewModel·SavedState 가 아니라 화면 데이터 재조회로 복원한다.
        val target = existing ?: create()
        currentTag = tag
        tx.replace(R.id.navHost, target, tag)
        tx.commit()
        selectTab(tag)
    }

    /** 지금 화면의 탭을 선택한다. 온보딩·시작 화면에서는 탭 바를 숨긴다. */
    private fun selectTab(tag: String) {
        val bar = findViewById<RadioGroup>(R.id.tabBar)
        val id = when (tag) {
            HOME_TAG -> R.id.tabHome
            STATUS_TAG -> R.id.tabStatus
            VOICE_TAG -> R.id.tabVoice
            BATTLE_TAG, RANKING_TAG -> R.id.tabBattle
            SETTINGS_TAG -> R.id.tabSettings
            else -> {
                bar.visibility = View.GONE
                return
            }
        }
        bar.visibility = View.VISIBLE
        if (bar.checkedRadioButtonId == id) return
        suppressTabCallback = true
        bar.check(id)
        suppressTabCallback = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAG, currentTag)
    }

    /**
     * 화면이 다시 만들어질 때(데모 스위치·시나리오 변경) 보던 화면은 FragmentManager 가 되살린다.
     * 탭의 선택 표시만 그 화면에 맞춘다. 되살리는 동안 탭이 바뀌어도 화면을 갈아 끼우지 않는다.
     */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        suppressTabCallback = true
        super.onRestoreInstanceState(savedInstanceState)
        suppressTabCallback = false
        currentTag?.let { selectTab(it) }
    }

    override fun onResume() {
        super.onResume()
        val tag = supportFragmentManager.findFragmentById(R.id.navHost)?.tag
        if (tag != null) selectTab(tag)
    }

    private companion object {
        const val KEY_TAG = "current_tag"
        const val HOME_TAG = "home"
        const val START_TAG = "start"
        const val STATUS_TAG = "status"
        const val VOICE_TAG = "voice"
        const val BATTLE_TAG = "battle"
        const val RANKING_TAG = "ranking"
        const val SETTINGS_TAG = "settings"
        const val ONBOARD_TAG = "onboarding"
    }
}

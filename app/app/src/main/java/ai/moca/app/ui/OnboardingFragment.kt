package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.data.NicknameRule
import ai.pleos.ds.PleosPopup
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 첫 실행: 동의 3개(모두 필수) + 닉네임(최종정리 3장 0번).
 * 하나라도 동의하지 않으면 안내 뒤 앱을 끝낸다. 동의 값은 저장하지 않고 onboarded 도 켜지 않는다.
 */
class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as MocaApp
        val prefs = app.prefs
        val activity = requireActivity() as ai.moca.app.MainActivity

        val consents = listOf(R.id.consent1, R.id.consent2, R.id.consent3)
            .map { view.findViewById<CheckBox>(it) }
        val nicknameInput = view.findViewById<EditText>(R.id.nicknameInput)
        val nicknameError = view.findViewById<TextView>(R.id.nicknameError)

        view.findViewById<View>(R.id.buttonStart).setOnClickListener {
            val typed = nicknameInput.text?.toString()?.trim().orEmpty()
            // 비어 있으면 «드라이버» + 네 자리를 만든다. 채웠는데 규칙에 안 맞으면 넘어가지 않는다.
            val nickname = NicknameRule.resolve(typed)
            if (nickname == null) {
                nicknameError.visibility = View.VISIBLE
                nicknameInput.requestFocus()
                return@setOnClickListener
            }
            nicknameError.visibility = View.GONE

            if (consents.any { !it.isChecked }) {
                PleosPopup.Builder(activity)
                    .description(getString(R.string.onboarding_consent_required))
                    .confirm(getString(R.string.common_confirm)) { activity.finishAffinity() }
                    .cancelable(false)
                    .show()
                return@setOnClickListener
            }

            prefs.consentData = true
            prefs.consentCloud = true
            prefs.applyBattleConsent(true)
            prefs.nickname = nickname
            prefs.onboarded = true
            activity.goHome()
        }
    }
}

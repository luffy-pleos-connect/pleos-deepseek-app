package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.R
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import androidx.fragment.app.Fragment

/**
 * 첫 실행: 동의 4개 + 닉네임.
 * 동의 2·3·4를 거부하면 오프라인 음성·연습 상대·기록 없음으로 동작한다(F-23).
 */
class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as MocaApp
        val prefs = app.prefs
        val activity = requireActivity() as ai.moca.app.MainActivity

        val consent1 = view.findViewById<CheckBox>(R.id.consent1)
        val consent2 = view.findViewById<CheckBox>(R.id.consent2)
        val consent3 = view.findViewById<CheckBox>(R.id.consent3)
        val consent4 = view.findViewById<CheckBox>(R.id.consent4)
        val nicknameInput = view.findViewById<EditText>(R.id.nicknameInput)

        view.findViewById<View>(R.id.buttonStart).setOnClickListener {
            prefs.consentData = consent1.isChecked
            prefs.consentCloud = consent2.isChecked
            prefs.applyBattleConsent(consent3.isChecked)
            prefs.consentUsage = consent4.isChecked
            val typed = nicknameInput.text?.toString()?.trim().orEmpty()
            if (typed.isNotEmpty()) prefs.nickname = typed
            prefs.onboarded = true
            activity.goHome()
        }

        view.findViewById<View>(R.id.buttonSkip).setOnClickListener {
            // 건너뛰기는 동의를 받지 않는다. 나머지 값은 지금처럼 두고 사용 기록만 끈다.
            prefs.consentUsage = false
            prefs.onboarded = true
            activity.goHome()
        }
    }
}

package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.R
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import androidx.fragment.app.Fragment

/** 첫 실행: 동의 3개 + 닉네임. 동의 2·3을 거부하면 오프라인 음성·연습 상대만 쓴다(F-23). */
class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as MocaApp
        val prefs = app.prefs
        val activity = requireActivity() as ai.moca.app.MainActivity

        val consent1 = view.findViewById<CheckBox>(R.id.consent1)
        val consent2 = view.findViewById<CheckBox>(R.id.consent2)
        val consent3 = view.findViewById<CheckBox>(R.id.consent3)
        val nicknameInput = view.findViewById<EditText>(R.id.nicknameInput)

        view.findViewById<View>(R.id.buttonStart).setOnClickListener {
            prefs.consentData = consent1.isChecked
            prefs.consentCloud = consent2.isChecked
            prefs.applyBattleConsent(consent3.isChecked)
            val typed = nicknameInput.text?.toString()?.trim().orEmpty()
            if (typed.isNotEmpty()) prefs.nickname = typed
            prefs.onboarded = true
            activity.goHome()
        }

        view.findViewById<View>(R.id.buttonSkip).setOnClickListener {
            prefs.onboarded = true
            activity.goHome()
        }
    }
}

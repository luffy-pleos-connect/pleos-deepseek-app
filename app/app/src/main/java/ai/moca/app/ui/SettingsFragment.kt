package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.vehicle.DemoScenario
import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment

/** 설정: 닉네임·데모 모드·동의·데이터 삭제·면책. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as MocaApp
        val prefs = app.prefs

        val nickname = view.findViewById<TextView>(R.id.nicknameValue)
        nickname.text = prefs.nickname

        val demo = view.findViewById<Switch>(R.id.switchDemo)
        demo.isChecked = prefs.demoEnabled
        demo.setOnCheckedChangeListener { _, checked ->
            prefs.demoEnabled = checked
            nickname.text = if (checked) {
                prefs.nickname + " · " + DemoScenario.byId(prefs.demoScenarioId).id + " " +
                    getString(R.string.c13_badge_demo)
            } else {
                prefs.nickname
            }
            requireActivity().recreate()
        }

        val consent = view.findViewById<Switch>(R.id.switchConsent)
        consent.isChecked = prefs.consentData
        consent.setOnCheckedChangeListener { _, checked -> prefs.consentData = checked }

        view.findViewById<View>(R.id.buttonDeleteData).setOnClickListener {
            prefs.clearAll()
            (requireActivity() as ai.moca.app.MainActivity).goOnboarding()
        }
    }
}

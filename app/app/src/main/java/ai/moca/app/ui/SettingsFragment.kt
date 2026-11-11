package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.data.Prefs
import ai.moca.app.vehicle.DemoScenario
import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton
import androidx.fragment.app.Fragment

/** 설정: 닉네임·데모 모드·동의·데이터 삭제·면책. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val scenarioButtons =
        listOf(R.id.scenarioD0, R.id.scenarioD1, R.id.scenarioD2, R.id.scenarioD3,
            R.id.scenarioD4, R.id.scenarioD5, R.id.scenarioD6)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as MocaApp
        val prefs = app.prefs

        val nickname = view.findViewById<TextView>(R.id.nicknameValue)
        nickname.text = nicknameText(prefs)

        val scenarioGroup = view.findViewById<View>(R.id.scenarioGroup)

        val demo = view.findViewById<Switch>(R.id.switchDemo)
        demo.isChecked = prefs.demoEnabled
        scenarioGroup.visibility = if (prefs.demoEnabled) View.VISIBLE else View.GONE
        demo.setOnCheckedChangeListener { _, checked ->
            prefs.demoEnabled = checked
            nickname.text = nicknameText(prefs)
            requireActivity().recreate()
        }

        val buttons = scenarioButtons.map { view.findViewById<ToggleButton>(it) }
        buttons.forEachIndexed { index, button ->
            button.isChecked = DemoScenario.entries[index].id == prefs.demoScenarioId
            button.setOnClickListener {
                prefs.demoScenarioId = DemoScenario.entries[index].id
                selectScenario(buttons, index)
                nickname.text = nicknameText(prefs)
                requireActivity().recreate()
            }
        }

        val consent = view.findViewById<Switch>(R.id.switchConsent)
        consent.isChecked = prefs.consentData
        consent.setOnCheckedChangeListener { _, checked -> prefs.consentData = checked }

        view.findViewById<View>(R.id.buttonDeleteData).setOnClickListener {
            prefs.clearAll()
            (requireActivity() as ai.moca.app.MainActivity).goOnboarding()
        }
    }

    private fun selectScenario(buttons: List<ToggleButton>, selected: Int) {
        buttons.forEachIndexed { index, button -> button.isChecked = index == selected }
    }

    private fun nicknameText(prefs: Prefs): String = if (prefs.demoEnabled) {
        prefs.nickname + " · " + DemoScenario.byId(prefs.demoScenarioId).id + " " +
            getString(R.string.c13_badge_demo)
    } else {
        prefs.nickname
    }
}

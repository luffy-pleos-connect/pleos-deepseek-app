package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.MainActivity
import ai.moca.app.R
import ai.moca.app.data.Prefs
import ai.moca.app.vehicle.DemoScenario
import ai.pleos.ds.PleosPopup
import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton
import androidx.fragment.app.Fragment

/** 설정: 내 정보·데모·데이터. 삭제는 확인 뒤에만 한다. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val scenarioButtons =
        listOf(R.id.scenarioD0, R.id.scenarioD1, R.id.scenarioD2, R.id.scenarioD3,
            R.id.scenarioD4, R.id.scenarioD5, R.id.scenarioD6, R.id.scenarioD7)

    private var group = GROUP_PROFILE

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as MocaApp
        val prefs = app.prefs
        group = savedInstanceState?.getString(KEY_GROUP) ?: GROUP_PROFILE

        val nickname = view.findViewById<TextView>(R.id.nicknameValue)
        nickname.text = nicknameText(prefs)

        val profileRow = view.findViewById<View>(R.id.settingsGroupProfile)
        val demoRow = view.findViewById<View>(R.id.settingsGroupDemo)
        val dataRow = view.findViewById<View>(R.id.settingsGroupData)
        val profilePanel = view.findViewById<View>(R.id.settingsPanelProfile)
        val demoPanel = view.findViewById<View>(R.id.settingsPanelDemo)
        val dataPanel = view.findViewById<View>(R.id.settingsPanelData)
        val scenarioGroup = view.findViewById<View>(R.id.scenarioGroup)

        fun showGroup(next: String) {
            group = next
            profileRow.isSelected = next == GROUP_PROFILE
            demoRow.isSelected = next == GROUP_DEMO
            dataRow.isSelected = next == GROUP_DATA
            profilePanel.visibility = if (next == GROUP_PROFILE) View.VISIBLE else View.GONE
            demoPanel.visibility = if (next == GROUP_DEMO) View.VISIBLE else View.GONE
            dataPanel.visibility = if (next == GROUP_DATA) View.VISIBLE else View.GONE
        }

        profileRow.setOnClickListener { showGroup(GROUP_PROFILE) }
        demoRow.setOnClickListener { showGroup(GROUP_DEMO) }
        dataRow.setOnClickListener { showGroup(GROUP_DATA) }
        showGroup(group)

        val demo = view.findViewById<Switch>(R.id.switchDemo)
        demo.isChecked = prefs.demoEnabled
        scenarioGroup.visibility = if (prefs.demoEnabled) View.VISIBLE else View.GONE
        demo.setOnCheckedChangeListener { _, checked ->
            prefs.demoEnabled = checked
            group = GROUP_DEMO
            nickname.text = nicknameText(prefs)
            requireActivity().recreate()
        }

        val buttons = scenarioButtons.map { view.findViewById<ToggleButton>(it) }
        buttons.forEachIndexed { index, button ->
            button.isChecked = DemoScenario.entries[index].id == prefs.demoScenarioId
            button.setOnClickListener {
                prefs.demoScenarioId = DemoScenario.entries[index].id
                selectScenario(buttons, index)
                group = GROUP_DEMO
                nickname.text = nicknameText(prefs)
                requireActivity().recreate()
            }
        }

        val consent = view.findViewById<Switch>(R.id.switchConsent)
        consent.isChecked = prefs.consentData
        consent.setOnCheckedChangeListener { _, checked -> prefs.consentData = checked }

        view.findViewById<View>(R.id.buttonDeleteData).setOnClickListener {
            val activity = requireActivity() as MainActivity
            PleosPopup.Builder(activity)
                .title(getString(R.string.settings_delete_title))
                .description(getString(R.string.settings_delete_body))
                .confirm(getString(R.string.settings_delete_confirm)) {
                    prefs.clearAll()
                    app.latestInspection = null
                    app.lastInspectKey = null
                    activity.goOnboarding()
                }
                .cancel(getString(R.string.common_cancel)) { }
                .show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_GROUP, group)
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

    private companion object {
        const val KEY_GROUP = "settings_group"
        const val GROUP_PROFILE = "profile"
        const val GROUP_DEMO = "demo"
        const val GROUP_DATA = "data"
    }
}

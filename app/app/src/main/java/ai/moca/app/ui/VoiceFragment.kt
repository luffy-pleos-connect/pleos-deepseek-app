package ai.moca.app.ui

import ai.moca.app.R
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment

/** 보이스: 이번 단계는 화면 골격만. STT/TTS·/chat 루프는 다음 단계. */
class VoiceFragment : Fragment(R.layout.fragment_voice) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val state = view.findViewById<TextView>(R.id.voiceState)
        view.findViewById<View>(R.id.micButton).setOnClickListener {
            state.text = getString(R.string.placeholder_preparing)
        }
        view.findViewById<View>(R.id.suggestion1).setOnClickListener { ask(state, getString(R.string.voice_suggestion_1)) }
        view.findViewById<View>(R.id.suggestion2).setOnClickListener { ask(state, getString(R.string.voice_suggestion_2)) }
        view.findViewById<View>(R.id.suggestion3).setOnClickListener { ask(state, getString(R.string.voice_suggestion_3)) }
    }

    private fun ask(state: TextView, question: String) {
        state.text = getString(R.string.placeholder_preparing) + " · " + question
    }
}

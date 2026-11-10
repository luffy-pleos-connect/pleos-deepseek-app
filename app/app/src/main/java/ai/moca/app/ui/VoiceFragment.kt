package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.battle.BattleRepository
import ai.moca.app.net.ApiClient
import ai.moca.app.score.ScoreBridge
import ai.moca.app.voice.Answer
import ai.moca.app.voice.ChatClient
import ai.moca.app.voice.Tools
import ai.moca.app.voice.VoiceAgent
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 보이스: Android SpeechRecognizer + TextToSpeech + /chat 도구 호출 루프.
 * 서버 실패·동의 거부면 오프라인 의도 6개로 같은 Tools 를 부른다.
 *
 * SpeechRecognizer 는 메인 스레드에서 쓴다. 에뮬레이터에 마이크가 없으면 실패하므로
 * 글자 입력 경로를 항상 함께 둔다.
 */
class VoiceFragment : Fragment(R.layout.fragment_voice) {

    private lateinit var stateText: TextView
    private lateinit var answerText: TextView
    private lateinit var chipText: TextView
    private lateinit var offlineBadge: TextView
    private lateinit var demoBadge: TextView
    private lateinit var micButton: Button
    private lateinit var input: EditText

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var agent: VoiceAgent? = null
    private var busy = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        stateText = view.findViewById(R.id.voiceState)
        answerText = view.findViewById(R.id.answerText)
        chipText = view.findViewById(R.id.toolChips)
        offlineBadge = view.findViewById(R.id.voiceOfflineBadge)
        demoBadge = view.findViewById(R.id.voiceDemoBadge)
        micButton = view.findViewById(R.id.micButton)
        input = view.findViewById<EditText>(R.id.questionInput)

        val app = requireActivity().application as MocaApp
        demoBadge.visibility = if (app.prefs.demoEnabled) View.VISIBLE else View.GONE
        stateText.text = getString(R.string.voice_state_idle)
        setupTts()

        micButton.setOnClickListener { startListening() }
        view.findViewById<View>(R.id.sendButton).setOnClickListener {
            val q = input.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) {
                input.setText("")
                ask(q)
            }
        }
        view.findViewById<View>(R.id.suggestion1).setOnClickListener { ask(getString(R.string.voice_recent_1)) }
        view.findViewById<View>(R.id.suggestion2).setOnClickListener { ask(getString(R.string.voice_recent_2)) }
        view.findViewById<View>(R.id.suggestion3).setOnClickListener { ask(getString(R.string.voice_recent_3)) }

        agent = buildAgent(app)
    }

    private fun buildAgent(app: MocaApp): VoiceAgent {
        val activity = requireActivity() as ai.moca.app.MainActivity
        val tools = Tools(
            vehicle = app.vehicle(),
            isDemo = app.prefs.demoEnabled,
            drivingLimited = { activity.isDrivingLimited() },
            openScreen = { screen ->
                when (screen) {
                    "home" -> { activity.goHome(); true }
                    "status" -> { activity.goStatus(); true }
                    "battle" -> { activity.goBattle(); true }
                    else -> false
                }
            },
            evaluate = { readings, warnings, isDemo -> ScoreBridge.evaluate(readings, warnings, isDemo) },
        )
        val client: ChatClient = ApiClient(app.prefs)
        return VoiceAgent(
            client = client,
            tools = tools,
            toolsJson = tools.definitions(),
            consentCloud = app.prefs.consentCloud,
            onFiller = { filler ->
                stateText.text = getString(R.string.voice_state_speaking)
                speak(filler)
            },
        )
    }

    private fun setupTts() {
        tts = TextToSpeech(requireContext()) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.KOREAN
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "moca-voice")
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        stateText.text = getString(R.string.voice_state_listening)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        val r = SpeechRecognizer.createSpeechRecognizer(requireContext())
        recognizer = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                r.destroy()
                recognizer = null
                if (text.isNotBlank()) ask(text)
                else stateText.text = getString(R.string.voice_state_idle)
            }

            override fun onError(error: Int) {
                r.destroy()
                recognizer = null
                stateText.text = getString(R.string.voice_permission_needed)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(intent)
    }

    /** 디버그 자동 시연에서 호출한다. 화면이 준비된 뒤 질문을 넣는다. */
    fun autoAsk(question: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(800)
            ask(question)
        }
    }

    private fun ask(question: String) {
        if (busy) return
        val runner = agent ?: return
        busy = true
        val activity = requireActivity() as ai.moca.app.MainActivity
        stateText.text = getString(R.string.voice_state_thinking)
        chipText.text = ""
        viewLifecycleOwner.lifecycleScope.launch {
            val answer: Answer = runCatching {
                // 1턴 빠른 경로(의도 분류 → 도구 선실행). 실패하면 일반 경로로 되돌아간다.
                runner.askFast(question, activity.isDrivingLimited())
            }.getOrElse {
                Answer("지금은 답할 수 없어요. 잠시 뒤 다시 물어봐 주세요.", emptyList(), true, false)
            }
            render(answer)
            busy = false
        }
    }

    private fun render(answer: Answer) {
        answerText.text = answer.text
        offlineBadge.visibility = if (answer.usedOffline) View.VISIBLE else View.GONE
        chipText.text = if (answer.toolCalls.isEmpty()) {
            ""
        } else {
            getString(R.string.voice_tool_chip_done) + " · " + answer.toolCalls.joinToString(", ")
        }
        stateText.text = getString(R.string.voice_state_speaking)
        speak(answer.text)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            stateText.text = getString(R.string.voice_permission_needed)
        }
    }

    override fun onDestroyView() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroyView()
    }

    private companion object {
        const val REQ_AUDIO = 1001
    }
}

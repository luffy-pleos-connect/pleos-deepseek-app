package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.battle.BattleRepository
import ai.moca.app.net.ApiClient
import ai.moca.app.score.ScoreBridge
import ai.moca.app.link.FixLinks
import ai.moca.app.voice.Answer
import ai.moca.app.voice.ChatClient
import ai.moca.app.voice.Tools
import ai.moca.app.voice.VoiceAgent
import ai.pleos.ds.PleosToast
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import moca.core.Priority
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

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
    private lateinit var micButton: ImageButton
    private lateinit var fixButton: Button
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
        fixButton = view.findViewById(R.id.voiceFixAction)
        input = view.findViewById<EditText>(R.id.questionInput)

        val app = requireActivity().application as MocaApp
        demoBadge.visibility = if (app.prefs.demoEnabled) View.VISIBLE else View.GONE
        stateText.text = getString(R.string.voice_state_idle)
        setupTts()

        micButton.setOnClickListener { startListening() }
        val sendButton = view.findViewById<View>(R.id.sendButton)
        sendButton.setOnClickListener {
            val q = input.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) {
                input.setText("")
                hideKeyboard()
                ask(q)
            }
        }
        // 키보드의 보내기 글쇠도 보내기 단추와 같다. 키보드가 단추를 가려도 보낼 수 있다.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) sendButton.performClick()
            actionId == EditorInfo.IME_ACTION_SEND
        }
        view.findViewById<View>(R.id.suggestion1).setOnClickListener { ask(getString(R.string.voice_recent_1)) }
        view.findViewById<View>(R.id.suggestion2).setOnClickListener { ask(getString(R.string.voice_recent_2)) }
        view.findViewById<View>(R.id.suggestion3).setOnClickListener { ask(getString(R.string.voice_recent_3)) }
        view.findViewById<View>(R.id.suggestion4).setOnClickListener { ask(getString(R.string.voice_recent_4)) }

        agent = buildAgent(app)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        // 초점이 남아 있으면 창이 다시 앞으로 올 때 키보드가 또 뜬다.
        input.clearFocus()
    }

    private fun buildAgent(app: MocaApp): VoiceAgent {
        val activity = requireActivity() as ai.moca.app.MainActivity
        val tools = Tools(
            vehicle = app.vehicle(),
            isDemo = app.prefs.demoEnabled,
            drivingLimited = { activity.isDrivingLimited() },
            openScreen = { screen ->
                when (screen) {
                    "home", "inspect" -> { activity.goHome(); true }
                    "status" -> { activity.goStatus(); true }
                    "battle" -> { activity.goBattle(); true }
                    "settings" -> { activity.goSettings(); true }
                    else -> false
                }
            },
            evaluate = { readings, warnings, isDemo -> ScoreBridge.evaluate(readings, warnings, isDemo) },
            rerunInspection = { ai.moca.app.inspect.Inspector(app).run(force = true) },
            startFix = { action ->
                suspendCancellableCoroutine { cont ->
                    FixLinks.start(activity, action, activity.isDrivingLimited()) { result ->
                        if (cont.isActive) cont.resume(result.toJson())
                    }
                }
            },
            startBattle = { activity.openBattleAndStart() },
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
        chipText.visibility = View.GONE
        fixButton.visibility = View.GONE
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
        val labels = answer.toolCalls.mapNotNull { toolLabel(it) }
        if (labels.isEmpty()) {
            chipText.text = ""
            chipText.visibility = View.GONE
        } else {
            chipText.visibility = View.VISIBLE
            chipText.text = getString(R.string.voice_tool_chip_done) + " · " + labels.joinToString(", ")
        }
        showFixAction(answer)
        stateText.text = getString(R.string.voice_state_speaking)
        speak(answer.text)
    }

    /**
     * 최근 점검에 감점이 있고, 방금 답이 차량 상태 도구를 썼을 때만 조치 단추를 보인다.
     */
    private fun showFixAction(answer: Answer) {
        val inspection = (requireActivity().application as MocaApp).latestInspection
        val problem = inspection?.let { Priority.topProblem(it.engine) }
        val usedStatus = answer.toolCalls.any { it in STATUS_TOOLS }
        if (inspection == null || problem == null || !usedStatus) {
            fixButton.visibility = View.GONE
            return
        }
        val action = Priority.actionFor(problem, inspection.engine)
        fixButton.visibility = View.VISIBLE
        fixButton.text = action.label
        fixButton.setOnClickListener {
            val host = activity as? ai.moca.app.MainActivity ?: return@setOnClickListener
            FixLinks.start(host, action, host.isDrivingLimited()) { result ->
                PleosToast.show(host, result.message)
            }
        }
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

    /** 도구 이름을 사람이 읽는 말로 바꾼다. 표에 없는 이름은 칩에 넣지 않는다. */
    private fun toolLabel(name: String): String? = when (name) {
        "get_latest_inspection" -> getString(R.string.voice_tool_get_latest_inspection)
        "get_active_warnings" -> getString(R.string.voice_tool_get_active_warnings)
        "run_inspection" -> getString(R.string.voice_tool_run_inspection)
        "get_part_status" -> getString(R.string.voice_tool_get_part_status)
        "get_energy_and_distance" -> getString(R.string.voice_tool_get_energy_and_distance)
        "start_battle" -> getString(R.string.voice_tool_start_battle)
        "open_screen" -> getString(R.string.voice_tool_open_screen)
        "start_navigation_to_service" -> getString(R.string.voice_tool_start_navigation_to_service)
        else -> null
    }

    private companion object {
        const val REQ_AUDIO = 1001
        val STATUS_TOOLS = setOf(
            "get_active_warnings",
            "get_latest_inspection",
            "get_part_status",
            "run_inspection",
        )
    }
}

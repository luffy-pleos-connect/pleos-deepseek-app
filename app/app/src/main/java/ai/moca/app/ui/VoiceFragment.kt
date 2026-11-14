package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.inspect.InspectionResult
import ai.moca.app.inspect.Inspector
import ai.moca.app.nav.NaviPlan
import ai.moca.app.nav.NaviResult
import ai.moca.app.nav.PlaceKind
import ai.moca.app.net.ApiClient
import ai.moca.app.ui.model.BadgeTone
import ai.moca.app.ui.model.SolutionButton
import ai.moca.app.ui.model.Solutions
import ai.moca.app.ui.model.WarningBadges
import ai.moca.app.voice.Answer
import ai.moca.app.voice.AskContext
import ai.moca.app.voice.ChatClient
import ai.moca.app.voice.NavAction
import ai.moca.app.voice.NavIntents
import ai.moca.app.voice.NavRequest
import ai.moca.app.voice.NavSuggestion
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * 보이스: Android SpeechRecognizer + TextToSpeech + /chat 도구 호출 루프.
 * 서버 실패·동의 거부면 오프라인 의도로 같은 Tools 를 부른다.
 * 길 안내는 서버에 보내기 전에 기기에서 먼저 알아듣고, 내비를 조작하기 전에 확인 카드로 묻는다.
 *
 * SpeechRecognizer 는 메인 스레드에서 쓴다. 에뮬레이터에 마이크가 없으면 실패하므로
 * 글자 입력 경로를 항상 함께 둔다.
 */
class VoiceFragment : Fragment(R.layout.fragment_voice) {

    /** 확인을 기다리는 길 안내 하나. 카드가 떠 있는 동안만 있다. */
    private data class PendingNav(val request: NavRequest, val plan: NaviPlan)

    private lateinit var stateText: TextView
    private lateinit var answerText: TextView
    private lateinit var chipText: TextView
    private lateinit var offlineBadge: TextView
    private lateinit var micButton: ImageButton
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var summaryGrade: TextView
    private lateinit var summaryBadge: TextView
    private lateinit var contextCard: View
    private lateinit var contextText: TextView
    private lateinit var navCard: View
    private lateinit var navText: TextView
    private lateinit var navBadge: View
    private lateinit var navYes: Button
    private lateinit var navNo: Button
    private lateinit var suggestions: List<Button>

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var agent: VoiceAgent? = null
    private var busy = false

    /** 대기 중인 길 안내. 확인 카드와 같이 나타나고 사라진다. */
    private var pendingNav: PendingNav? = null

    /** 월간랭킹에서 넘어왔을 때의 요약. 모델에 «확인된 값» 으로 같이 보낸다. */
    private var careFacts: String? = null
    private var careOfflineAnswer: String? = null
    private var careQuestions: Set<String> = emptySet()

    private val app: MocaApp get() = requireActivity().application as MocaApp

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        stateText = view.findViewById(R.id.voiceState)
        answerText = view.findViewById(R.id.answerText)
        chipText = view.findViewById(R.id.toolChips)
        offlineBadge = view.findViewById(R.id.voiceOfflineBadge)
        micButton = view.findViewById(R.id.micButton)
        input = view.findViewById(R.id.questionInput)
        sendButton = view.findViewById(R.id.sendButton)
        summaryGrade = view.findViewById(R.id.voiceSummaryGrade)
        summaryBadge = view.findViewById(R.id.voiceSummaryBadge)
        contextCard = view.findViewById(R.id.voiceContextCard)
        contextText = view.findViewById(R.id.voiceContextText)
        navCard = view.findViewById(R.id.navConfirmCard)
        navText = view.findViewById(R.id.navConfirmText)
        navBadge = view.findViewById(R.id.navConfirmBadge)
        navYes = view.findViewById(R.id.navConfirmYes)
        navNo = view.findViewById(R.id.navConfirmNo)
        suggestions = listOf(R.id.suggestion1, R.id.suggestion2, R.id.suggestion3, R.id.suggestion4)
            .map { view.findViewById<Button>(it) }

        stateText.text = getString(R.string.voice_state_idle)
        setupTts()

        micButton.setOnClickListener { startListening() }
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
        navYes.setOnClickListener { confirmNav() }
        navNo.setOnClickListener { declineNav() }

        agent = buildAgent(app)
        renderSummary()
        val fromOtherScreen = bindArguments(arguments)
        setDrivingLimited((requireActivity() as MainActivity).isDrivingLimited())
        // 먼저 제안은 화면에 들어올 때 한 번, 넘어온 내용이 없고 안내 중이 아닐 때만 한다.
        if (!fromOtherScreen && !app.navi.guiding) suggestNav()
    }

    /**
     * 주행 중에는 키보드 입력을 막는다. MainActivity 가 주행 상태가 바뀔 때마다 부른다.
     */
    fun setDrivingLimited(limited: Boolean) {
        if (view == null) return
        input.isEnabled = !limited
        sendButton.isEnabled = !limited
        input.hint = getString(if (limited) R.string.voice_hint_driving else R.string.voice_hint_type)
        if (limited) hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        // 초점이 남아 있으면 창이 다시 앞으로 올 때 키보드가 또 뜬다.
        input.clearFocus()
    }

    // ── 위쪽: 지금 내 차 요약 ─────────────────────────────────────────

    private fun latest(): InspectionResult? = app.latestInspection ?: Inspector.stored(app)

    private fun renderSummary() {
        val result = latest()
        val grade = result?.evaluation?.grade
        val overall = result?.evaluation?.displayedOverall
        summaryGrade.text = if (grade != null && overall != null) {
            getString(R.string.voice_summary_grade, grade.label, overall)
        } else {
            getString(R.string.voice_summary_grade_none)
        }
        if (result == null) {
            summaryBadge.text = getString(R.string.voice_summary_grade_none)
            summaryBadge.setTextColor(requireContext().getColor(ai.pleos.ds.R.color.pleos_text_dark_tertiary))
            return
        }
        val badge = WarningBadges.of(result.warnings)
        summaryBadge.text = badge.text
        val colorRes = when (badge.tone) {
            BadgeTone.RED -> ai.pleos.ds.R.color.pleos_regulation_red
            BadgeTone.YELLOW -> ai.pleos.ds.R.color.pleos_regulation_yellow
            BadgeTone.NONE -> ai.pleos.ds.R.color.pleos_text_dark_primary
        }
        summaryBadge.setTextColor(requireContext().getColor(colorRes))
    }

    // ── 넘어온 내용 카드와 추천 질문 ──────────────────────────────────

    /**
     * 인자를 받아 카드와 추천 질문을 정한다. 인자가 없으면 카드는 숨기고 고정 추천 4개를 둔다.
     * 넘어온 카드를 그렸으면 true.
     */
    private fun bindArguments(args: Bundle?): Boolean {
        val questions: List<Pair<String, String>> = when {
            args?.containsKey(ARG_ITEM_LABEL) == true -> itemSuggestions(args)
            args?.containsKey(ARG_CARE_DAYS) == true -> careSuggestions(args)
            else -> {
                contextCard.visibility = View.GONE
                listOf(R.string.voice_recent_1, R.string.voice_recent_2, R.string.voice_recent_3, R.string.voice_recent_4)
                    .map { getString(it) }
                    .map { it to it }
            }
        }
        suggestions.forEachIndexed { index, button ->
            val question = questions.getOrNull(index)
            button.visibility = if (question == null) View.GONE else View.VISIBLE
            if (question != null) {
                button.text = question.first
                button.setOnClickListener { ask(question.second) }
            }
        }
        return contextCard.visibility == View.VISIBLE
    }

    /** 상태관리의 [AI에게 물어보기]. 「제동·조향 · ABS 경고등 −50 · 정비소 방문」 한 줄과 질문 셋. */
    private fun itemSuggestions(args: Bundle): List<Pair<String, String>> {
        val label = args.getString(ARG_ITEM_LABEL).orEmpty()
        val group = args.getString(ARG_ITEM_GROUP).orEmpty()
        val kinds = args.getString(ARG_ITEM_KINDS).orEmpty()
        contextText.text = getString(
            R.string.voice_context_item,
            args.getString(ARG_ITEM_CATEGORY).orEmpty(),
            label,
            args.getInt(ARG_ITEM_POINTS),
            kinds.replace(",", ", "),
        )
        contextCard.visibility = View.VISIBLE
        val third = when {
            kinds.contains(getString(R.string.status_solution_kind_repair_shop)) -> R.string.voice_q_navi_repair
            group == GROUP_ENERGY -> energyQuestion(group)
            group == GROUP_WASHER -> R.string.voice_q_washer_buy
            else -> R.string.voice_q_navi_repair
        }
        return listOf(
            getString(R.string.voice_q_item_why) to getString(R.string.voice_q_item_why_ask, label),
            getString(R.string.voice_q_item_how) to getString(R.string.voice_q_item_how_ask, label),
            getString(third).let { it to it },
        )
    }

    /** 배터리만 낮으면 충전소, 아니면 주유소. 지금 점검 결과의 해결 단추를 따른다. */
    private fun energyQuestion(group: String): Int {
        val solution = latest()?.let { Solutions.of(it.engine) }.orEmpty().firstOrNull { it.group == group }
        val buttons = solution?.buttons.orEmpty()
        return if (SolutionButton.CHARGING_STATION in buttons && SolutionButton.GAS_STATION !in buttons) {
            R.string.voice_q_navi_charging
        } else {
            R.string.voice_q_navi_gas
        }
    }

    /** 월간랭킹의 [질문하기]. 요약 숫자는 모델에 확인된 값으로 같이 보낸다. */
    private fun careSuggestions(args: Bundle): List<Pair<String, String>> {
        val days = args.getInt(ARG_CARE_DAYS)
        val streak = args.getInt(ARG_CARE_STREAK)
        val resolved = args.getInt(ARG_CARE_RESOLVED)
        val gap = args.getInt(ARG_CARE_GAP)
        val first = args.getInt(ARG_CARE_RANK) == 1
        contextText.text = if (first) {
            getString(R.string.voice_context_care_first, days, streak, resolved)
        } else {
            getString(R.string.voice_context_care, days, streak, resolved, gap)
        }
        contextCard.visibility = View.VISIBLE
        careOfflineAnswer = if (first) {
            getString(R.string.voice_care_offline_first, days, streak, resolved)
        } else {
            getString(R.string.voice_care_offline, days, streak, resolved, gap)
        }
        careFacts = JSONObject()
            .put("checkDays", days)
            .put("streakDays", streak)
            .put("resolvedItems", resolved)
            .put("scoreGapToFirst", gap)
            .put("rank", args.getInt(ARG_CARE_RANK))
            .put("totalPlayers", args.getInt(ARG_CARE_TOTAL))
            .toString()
        val questions = listOf(R.string.voice_q_care_1, R.string.voice_q_care_2, R.string.voice_q_care_3)
            .map { getString(it) }
        careQuestions = questions.toSet()
        return questions.map { it to it }
    }

    // ── 길 안내: 확인 카드 ───────────────────────────────────────────

    private fun planNav(request: NavRequest): NaviPlan {
        val navi = app.navi
        val demo = app.prefs.demoEnabled
        val keyword = request.keyword
        return when {
            request.action == NavAction.ADD_STOP -> navi.planStop(request.kind ?: PlaceKind.REPAIR_SHOP, demo)
            keyword != null -> navi.planKeyword(keyword, demo)
            else -> navi.plan(request.kind ?: PlaceKind.REPAIR_SHOP, demo)
        }
    }

    private fun executeNav(request: NavRequest): NaviResult {
        val navi = app.navi
        val demo = app.prefs.demoEnabled
        val kind = request.kind ?: PlaceKind.REPAIR_SHOP
        return when (request.action) {
            NavAction.GO -> request.keyword?.let { navi.navigateToKeyword(it, demo) } ?: navi.navigateTo(kind, demo)
            NavAction.ADD_STOP -> navi.addStop(kind, demo)
            NavAction.CANCEL -> navi.cancel()
        }
    }

    /**
     * 길 안내 요청 하나를 받는다. 안내 취소는 바로 하고, 안내 시작·경유지는 내비를 만지지 않고
     * 확인 카드를 띄울 준비만 한다(카드는 답을 그릴 때 뜬다). 결과는 도구 결과 JSON 이다.
     */
    private fun requestNav(request: NavRequest): String {
        if (request.action == NavAction.CANCEL) {
            pendingNav = null
            val result = executeNav(request)
            return JSONObject().put("ok", result.ok).put("message", result.message).toString()
        }
        val plan = planNav(request)
        pendingNav = PendingNav(request, plan)
        return JSONObject().put("ok", true).put("needs_confirmation", true).put("question", plan.question).toString()
    }

    /** 길 안내 말을 기기에서 처리한다. 서버가 안 돼도 똑같이 된다. */
    private fun askNav(request: NavRequest) {
        val json = JSONObject(requestNav(request))
        val text = json.optString("question").ifEmpty { json.optString("message") }
        render(Answer(text, listOf(request.action.toolName), usedOffline = false, fillerSpoken = false))
    }

    private fun confirmNav() {
        if (busy) return
        val pending = pendingNav ?: return
        pendingNav = null
        val result = executeNav(pending.request)
        render(Answer(result.message, listOf(pending.request.action.toolName), usedOffline = false, fillerSpoken = false))
    }

    private fun declineNav() {
        if (busy) return
        pendingNav = null
        render(Answer(getString(R.string.voice_nav_declined), emptyList(), usedOffline = false, fillerSpoken = false))
    }

    /** 화면에 들어올 때 한 번: 내 차에 맞는 길 안내가 있으면 확인 카드로 먼저 제안한다. 읽어 주지는 않는다. */
    private fun suggestNav() {
        val request = latest()?.let { NavSuggestion.of(it.engine) } ?: return
        val plan = planNav(request)
        pendingNav = PendingNav(request, plan)
        answerText.text = plan.question
        renderNavCard()
    }

    private fun renderNavCard() {
        val pending = pendingNav
        navCard.visibility = if (pending == null) View.GONE else View.VISIBLE
        if (pending == null) return
        navText.text = pending.plan.question
        navBadge.visibility = if (pending.plan.demoData) View.VISIBLE else View.GONE
        navYes.setText(if (pending.request.action == NavAction.ADD_STOP) R.string.voice_nav_add_stop else R.string.voice_nav_start)
    }

    // ── 에이전트 ─────────────────────────────────────────────────────

    private fun buildAgent(app: MocaApp): VoiceAgent {
        val activity = requireActivity() as MainActivity
        val tools = Tools(
            latest = { latest() },
            isDemo = app.prefs.demoEnabled,
            drivingLimited = { activity.isDrivingLimited() },
            openScreen = { screen ->
                when (screen) {
                    "home" -> { activity.goHome(); true }
                    "status" -> { activity.goStatus(); true }
                    "battle" -> { activity.goBattle(); true }
                    "settings" -> { activity.goSettings(); true }
                    else -> false
                }
            },
            requestNav = { request -> requestNav(request) },
            rerunInspection = { Inspector(app).run(force = true) },
            startBattle = { activity.goBattle() },
        )
        val client: ChatClient = ApiClient(app.prefs)
        return VoiceAgent(
            client = client,
            tools = tools,
            toolsJson = tools.definitions(),
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

    /** 디버그 전용([MainActivity] 의 moca_ask). 화면에 떠 있을 때만 질문을 넣는다. */
    fun debugAsk(question: String) {
        if (view != null) ask(question)
    }

    private fun ask(question: String) {
        if (busy) return
        val runner = agent ?: return
        // 확인 카드가 떠 있으면 승낙·거절부터 본다(주행 중에도 말로 답할 수 있다). 다른 질문이면 카드를 접는다.
        if (pendingNav != null) {
            when {
                NavIntents.isYes(question) -> return confirmNav()
                NavIntents.isNo(question) -> return declineNav()
                else -> {
                    pendingNav = null
                    renderNavCard()
                }
            }
        }
        NavIntents.detect(question)?.let { return askNav(it) }
        busy = true
        val activity = requireActivity() as MainActivity
        stateText.text = getString(R.string.voice_state_thinking)
        chipText.text = ""
        chipText.visibility = View.GONE
        val context = careFacts?.let { AskContext(it, if (question in careQuestions) careOfflineAnswer else null) }
        viewLifecycleOwner.lifecycleScope.launch {
            val answer: Answer = runCatching {
                // 1턴 빠른 경로(의도 분류 → 도구 선실행). 실패하면 일반 경로로 되돌아간다.
                runner.askFast(question, activity.isDrivingLimited(), context)
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
        val labels = answer.toolCalls.flatMap { evidenceLabels(it, answer.partCategories) }.distinct()
        if (labels.isEmpty()) {
            chipText.text = ""
            chipText.visibility = View.GONE
        } else {
            chipText.visibility = View.VISIBLE
            chipText.text = getString(R.string.voice_tool_chip_done) + EVIDENCE_SEPARATOR + labels.joinToString(EVIDENCE_SEPARATOR)
        }
        stateText.text = getString(R.string.voice_state_speaking)
        speak(answer.text)
        renderSummary()
        renderNavCard()
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

    /**
     * 근거 줄에 쓸 말. 도구 이름이 아니라 확인한 항목 이름이다.
     * 표에 없는 도구는 줄에 넣지 않는다.
     */
    private fun evidenceLabels(tool: String, partCategories: List<String>): List<String> = when (tool) {
        "get_part_status" ->
            if (partCategories.isEmpty()) {
                listOf(getString(R.string.voice_tool_get_part_status))
            } else {
                partCategories.mapNotNull { partLabel(it) }
            }
        "get_active_warnings" -> listOf(getString(R.string.voice_tool_get_active_warnings))
        "get_latest_inspection", "run_inspection" -> listOf(getString(R.string.voice_tool_get_latest_inspection))
        "get_energy_and_distance" -> listOf(getString(R.string.voice_tool_get_energy_and_distance))
        "start_battle" -> listOf(getString(R.string.voice_tool_start_battle))
        "open_screen" -> listOf(getString(R.string.voice_tool_open_screen))
        NavAction.GO.toolName, NavAction.ADD_STOP.toolName, NavAction.CANCEL.toolName ->
            listOf(getString(R.string.voice_tool_navigation))
        else -> emptyList()
    }

    private fun partLabel(category: String): String? = when (category) {
        "power" -> getString(R.string.voice_item_power)
        "brake" -> getString(R.string.voice_item_brake)
        "tire" -> getString(R.string.voice_item_tire)
        "electric" -> getString(R.string.voice_item_electric)
        "safety" -> getString(R.string.voice_item_safety)
        else -> null
    }

    companion object {
        /** 상태관리의 [AI에게 물어보기]가 넘기는 항목 정보(최종정리 11.1절). */
        const val ARG_ITEM_CATEGORY = "item_category"
        const val ARG_ITEM_GROUP = "item_group"
        const val ARG_ITEM_LABEL = "item_label"
        const val ARG_ITEM_POINTS = "item_points"
        const val ARG_ITEM_KINDS = "item_kinds"

        /** 월간랭킹의 [질문하기]가 넘기는 이번 달 관리 요약(최종정리 11.1절). 모두 Int. */
        const val ARG_CARE_DAYS = "care_days"
        const val ARG_CARE_STREAK = "care_streak"
        const val ARG_CARE_RESOLVED = "care_resolved"
        const val ARG_CARE_GAP = "care_gap"
        const val ARG_CARE_RANK = "care_rank"
        const val ARG_CARE_TOTAL = "care_total"

        private const val REQ_AUDIO = 1001
        private const val EVIDENCE_SEPARATOR = " · "

        /** 감점 원인 묶음 이름(core Rules.deductions 의 group). */
        private const val GROUP_ENERGY = "ENERGY"
        private const val GROUP_WASHER = "WASHER"
    }
}

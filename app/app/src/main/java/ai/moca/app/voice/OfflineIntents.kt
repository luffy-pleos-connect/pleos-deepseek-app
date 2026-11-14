package ai.moca.app.voice

/**
 * 서버가 끊기거나 동의를 거부했을 때 쓰는 기기 안 의도 8개.
 * LLM 없이 키워드로 의도를 고르고, 같은 [Tools] 를 호출한다.
 */
enum class OfflineIntent(val id: String) {
    INSPECT("점검"),
    GRADE("등급"),
    WARNINGS("경고"),
    PART("부품"),
    BATTLE("대전"),
    OPEN_SCREEN("화면"),
    NAVIGATE("길안내"),
    CANCEL_NAV("안내취소"),
}

object OfflineIntents {

    private val patterns: Map<OfflineIntent, List<String>> = mapOf(
        OfflineIntent.INSPECT to listOf("점검", "검사", "체크"),
        OfflineIntent.GRADE to listOf("등급", "점수", "몇 점", "종합", "건강", "관리"),
        OfflineIntent.WARNINGS to listOf("경고", "빨간불", "노란불", "경고등", "abs", "tpms"),
        OfflineIntent.PART to listOf("오일", "타이어", "배터리", "브레이크", "워셔", "부품"),
        OfflineIntent.BATTLE to listOf("대전", "겨루", "배틀", "라운드"),
        OfflineIntent.OPEN_SCREEN to listOf("홈", "상태관리", "상세", "순위", "설정", "열어", "가 줘", "가줘", "화면"),
    )

    fun detect(question: String): OfflineIntent? {
        val q = question.lowercase()
        // 예전 탭 이름. 「관리」보다 먼저 봐야 등급이 아니라 화면 열기로 간다.
        if (q.contains("상태관리")) return OfflineIntent.OPEN_SCREEN
        // 더 구체적인 의도부터 본다: 경고 > 길 안내 > 부품 > 점검 > 등급 > 대전 > 화면
        fun matches(intent: OfflineIntent) = patterns.getValue(intent).any { q.contains(it) }
        if (matches(OfflineIntent.WARNINGS)) return OfflineIntent.WARNINGS
        NavIntents.detect(question)?.let {
            return if (it.action == NavAction.CANCEL) OfflineIntent.CANCEL_NAV else OfflineIntent.NAVIGATE
        }
        val order = listOf(
            OfflineIntent.PART,
            OfflineIntent.INSPECT,
            OfflineIntent.GRADE,
            OfflineIntent.BATTLE,
            OfflineIntent.OPEN_SCREEN,
        )
        return order.firstOrNull { matches(it) }
    }

    /** 길 안내는 말에 따라 도구가 셋으로 갈리므로 [question] 을 함께 본다. */
    fun toolName(intent: OfflineIntent, question: String = ""): String = when (intent) {
        OfflineIntent.INSPECT -> "run_inspection"
        OfflineIntent.GRADE -> "get_latest_inspection"
        OfflineIntent.WARNINGS -> "get_active_warnings"
        OfflineIntent.PART -> "get_part_status"
        OfflineIntent.BATTLE -> "start_battle"
        OfflineIntent.OPEN_SCREEN -> "open_screen"
        OfflineIntent.CANCEL_NAV -> NavAction.CANCEL.toolName
        OfflineIntent.NAVIGATE -> (NavIntents.detect(question)?.action ?: NavAction.GO).toolName
    }

    /**
     * 화면 열기일 때는 탭 이름을(모르는 말이면 빈 화면이라 열지 않는다), 부품 질문은 그 구역을,
     * 길 안내는 종류나 말한 장소를 넘긴다.
     */
    fun toolArgs(intent: OfflineIntent, question: String): org.json.JSONObject? = when (intent) {
        OfflineIntent.OPEN_SCREEN -> org.json.JSONObject().put("screen", screenId(question))
        OfflineIntent.PART -> partCategory(question)?.let { org.json.JSONObject().put("category", it) }
        OfflineIntent.NAVIGATE -> NavIntents.detect(question)?.let { request ->
            org.json.JSONObject().apply {
                request.kind?.let { put("kind", it.name.lowercase()) }
                request.keyword?.let { put("place", it) }
            }
        }
        else -> null
    }

    /**
     * open_screen 값. MainActivity 가 여는 탭과 같다.
     * 홈 → home, 상태관리·상세 → status, 대전하기·순위 → battle, 설정 → settings.
     */
    fun screenId(question: String): String {
        val q = question.lowercase()
        return when {
            q.contains("설정") -> "settings"
            q.contains("대전하기") || q.contains("순위") -> "battle"
            q.contains("상태관리") || q.contains("상세") -> "status"
            q.contains("홈") -> "home"
            else -> ""
        }
    }

    /** 질문에 있는 부품이 속한 구역. 부품을 못 가렸으면 전체. */
    private fun partCategory(question: String): String? = when (partKind(question)) {
        "oil" -> "power"
        "brake" -> "brake"
        "tire" -> "tire"
        "battery" -> "electric"
        "washer" -> "safety"
        else -> null
    }

    /**
     * 도구 결과 JSON 을 템플릿 문장으로 바꾼다.
     * 숫자는 도구 결과의 값을 그대로 쓰고, 없는 값을 지어내지 않는다.
     * [question] 은 부품 질문에서 어떤 부품을 물었는지 가릴 때 쓴다.
     */
    fun template(
        intent: OfflineIntent,
        resultJson: String,
        drivingLimited: Boolean,
        question: String = "",
    ): String {
        val obj = runCatching { org.json.JSONObject(resultJson) }.getOrNull()
            ?: return "지금은 확인할 수 없어요. 잠시 뒤 다시 물어봐 주세요."
        if (!obj.optBoolean("ok", false)) {
            val reason = obj.optString("reason")
            if (intent == OfflineIntent.CANCEL_NAV && obj.has("message")) return obj.optString("message")
            return when {
                reason == "driving_limited" -> "주행 중에는 화면을 열 수 없어요. 안전한 곳에 정차한 뒤 다시 말해 주세요."
                reason == "no_measurement" -> "아직 점검 기록이 없어요. 점검해 주시면 등급을 알려 드릴게요."
                else -> "지금은 확인할 수 없어요. 잠시 뒤 다시 물어봐 주세요."
            }
        }
        val sentence = when (intent) {
            OfflineIntent.GRADE, OfflineIntent.INSPECT -> gradeSentence(obj)
            OfflineIntent.WARNINGS -> warningSentence(obj)
            OfflineIntent.PART -> partSentence(question, obj)
            OfflineIntent.BATTLE -> "대전하기 화면을 열었어요. 시작 단추를 눌러 주세요."
            OfflineIntent.OPEN_SCREEN -> screenSentence(obj)
            OfflineIntent.NAVIGATE -> obj.optString("question")
            OfflineIntent.CANCEL_NAV -> obj.optString("message")
        }
        val withSafety = stopSafelyFirst(intent, obj, sentence)
        // 주행 중에는 2문장 이내로 자른다. 정차 안내를 넣은 뒤에 자른다(안내가 잘리면 안 된다).
        return if (drivingLimited) limitSentences(withSafety, 2) else limitSentences(withSafety, 4)
    }

    /**
     * 서버를 거친 답과 같은 규칙: 차의 값을 읽은 답인데 빨간 경고등이 켜져 있으면 첫 문장은 정차 안내다.
     * 이미 그 문장으로 시작하면 두 번 붙이지 않는다.
     */
    private fun stopSafelyFirst(intent: OfflineIntent, obj: org.json.JSONObject, sentence: String): String {
        val readsVehicle = when (intent) {
            OfflineIntent.GRADE, OfflineIntent.INSPECT, OfflineIntent.PART, OfflineIntent.WARNINGS -> true
            OfflineIntent.BATTLE, OfflineIntent.OPEN_SCREEN, OfflineIntent.NAVIGATE, OfflineIntent.CANCEL_NAV -> false
        }
        if (!readsVehicle) return sentence
        if (obj.optInt("red") <= 0) return sentence
        if (sentence.startsWith(Tools.STOP_SAFELY)) return sentence
        return "${Tools.STOP_SAFELY}. $sentence"
    }

    private fun gradeSentence(obj: org.json.JSONObject): String {
        val grade = if (obj.isNull("grade")) "" else obj.optString("grade")
        val overall = if (obj.isNull("overall")) null else obj.optInt("overall")
        val next = if (obj.isNull("nextGrade")) "" else obj.optString("nextGrade")
        val points = if (obj.isNull("pointsToNext")) null else obj.optInt("pointsToNext")
        return buildString {
            if (grade.isNotEmpty()) append("지금 등급은 $grade").append(
                if (overall != null) "이고 종합 ${overall}점이에요. " else "이에요. ",
            )
            if (next.isNotEmpty() && points != null) append("${next}까지 ${points}점 남았어요.")
        }.ifEmpty { "아직 점검을 더 해야 등급을 낼 수 있어요." }
    }

    private fun warningSentence(obj: org.json.JSONObject): String {
        val active = obj.optJSONArray("active")
        if (active == null || active.length() == 0) {
            if (obj.optInt("total") == 0) return "지금 켜진 경고등은 없어요."
        }
        val advice = if (obj.isNull("safetyAdvice")) {
            if (obj.optInt("red") > 0) Tools.STOP_SAFELY else null
        } else {
            obj.optString("safetyAdvice").takeIf { it.isNotBlank() }
        }
        val names = if (active == null) {
            emptyList()
        } else {
            (0 until active.length()).map { i ->
                val w = active.getJSONObject(i)
                val name = w.optString("name")
                val color = if (w.isNull("color")) "" else w.optString("color")
                if (color.isEmpty()) "$name 경고등" else "$name 경고등($color)"
            }
        }
        if (names.isEmpty()) {
            return buildString {
                if (advice != null) append(advice).append(". ")
                append("지금 경고등 ${obj.optInt("total")}개가 켜져 있어요.")
            }
        }
        return buildString {
            if (advice != null) append(advice).append(". ")
            append("지금 ${names.joinToString(", ")}이 켜져 있어요.")
        }
    }

    private fun partSentence(question: String, obj: org.json.JSONObject): String {
        val items = obj.optJSONObject("items")
        return when (partKind(question)) {
            "oil" -> fluidSentence("엔진오일은", "엔진오일이", items?.optJSONObject("oil_engine"))
            "tire" -> tireSentence(items?.optJSONObject("tire_low"))
            "battery" -> batterySentence(items?.optJSONObject("soc"))
            "brake" -> fluidSentence("브레이크액은", "브레이크액이", items?.optJSONObject("brake_fluid"))
            "washer" -> fluidSentence("워셔액은", "워셔액이", items?.optJSONObject("washer"))
            else -> allPartsSentence(obj)
        }
    }

    /** 질문에 있는 부품 낱말. 없으면 감점 전체를 말한다. */
    private fun partKind(question: String): String {
        val q = question.lowercase()
        return when {
            q.contains("오일") -> "oil"
            q.contains("타이어") -> "tire"
            q.contains("배터리") -> "battery"
            q.contains("브레이크") -> "brake"
            q.contains("워셔") -> "washer"
            else -> "all"
        }
    }

    private fun fluidSentence(topic: String, subject: String, item: org.json.JSONObject?): String {
        if (item == null || !item.optBoolean("supported") || item.isNull("value") || item.isNull("state")) {
            return "$topic 읽을 수 없어요."
        }
        val head = when (item.getString("state")) {
            "정상" -> "$topic 정상이에요."
            "부족" -> "$subject 부족해요."
            "심각 부족" -> "$subject 심각 부족이에요."
            "과다" -> "$subject 과다예요."
            else -> "$subject ${item.getString("state")}이에요."
        }
        return withAdvice(head, item)
    }

    private fun tireSentence(item: org.json.JSONObject?): String {
        if (item == null || !item.optBoolean("supported") || item.isNull("value") || item.isNull("state")) {
            return "타이어는 읽을 수 없어요."
        }
        if (item.getString("state") == "정상") return "타이어는 정상이에요."
        val places = item.optJSONArray("places")
        val where = if (places == null || places.length() == 0) {
            ""
        } else {
            (0 until places.length()).joinToString(", ") { places.getString(it) }
        }
        val head = if (where.isEmpty()) "타이어 공기압이 낮아요." else "타이어 공기압이 ${where}에서 낮아요."
        return withAdvice(head, item)
    }

    private fun batterySentence(item: org.json.JSONObject?): String {
        if (item == null || !item.optBoolean("supported") || item.isNull("value") || item.isNull("state")) {
            return "배터리는 읽을 수 없어요."
        }
        val head = "배터리는 ${formatPercent(item.getDouble("value"))} 남았어요."
        return if (item.getString("state") == "정상") head else withAdvice(head, item)
    }

    private fun formatPercent(value: Double): String {
        val asLong = value.toLong()
        val num = if (value == asLong.toDouble()) asLong.toString() else value.toString()
        return "$num%"
    }

    private fun withAdvice(head: String, item: org.json.JSONObject): String {
        if (item.isNull("advice")) return head
        val advice = item.optString("advice").trim().trimEnd('.')
        if (advice.isEmpty()) return head
        val gap = if (head.endsWith(".")) " " else ". "
        return "$head$gap$advice."
    }

    private fun allPartsSentence(obj: org.json.JSONObject): String {
        val problems = obj.optJSONArray("problems")
        if (problems == null || problems.length() == 0) return "부품은 이상 없어요."
        val names = (0 until problems.length()).joinToString(", ") { problems.getString(it) }
        return "감점이 있는 것은 $names 이에요."
    }

    private fun screenSentence(obj: org.json.JSONObject): String {
        val name = when (obj.optString("screen")) {
            "home" -> "홈"
            "status" -> "상태관리"
            "battle" -> "대전하기"
            "settings" -> "설정"
            else -> ""
        }
        return if (name.isEmpty()) "화면을 열었어요." else "$name 화면을 열었어요."
    }

    fun limitSentences(text: String, max: Int): String {
        val parts = text.split(". ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= max) return text
        return parts.take(max).joinToString(". ") + "."
    }

    const val NO_MATCH = "차량 관련 질문을 이해하지 못했어요. 등급·경고등·부품·점검·대전·길 안내 중 하나로 물어봐 주세요."
}

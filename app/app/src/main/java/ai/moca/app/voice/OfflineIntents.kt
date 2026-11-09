package ai.moca.app.voice

/**
 * 서버가 끊기거나 동의를 거부했을 때 쓰는 기기 안 의도 6개.
 * LLM 없이 키워드로 의도를 고르고, 같은 [Tools] 를 호출한다.
 */
enum class OfflineIntent(val id: String) {
    INSPECT("점검"),
    GRADE("등급"),
    WARNINGS("경고"),
    PART("부품"),
    BATTLE("대전"),
    OPEN_SCREEN("화면"),
}

object OfflineIntents {

    private val patterns: Map<OfflineIntent, List<String>> = mapOf(
        OfflineIntent.INSPECT to listOf("점검", "검사", "체크"),
        OfflineIntent.GRADE to listOf("등급", "점수", "몇 점", "종합", "건강", "관리"),
        OfflineIntent.WARNINGS to listOf("경고", "빨간불", "노란불", "경고등", "abs", "tpms"),
        OfflineIntent.PART to listOf("오일", "타이어", "배터리", "브레이크", "워셔", "부품"),
        OfflineIntent.BATTLE to listOf("대전", "겨루", "배틀", "라운드"),
        OfflineIntent.OPEN_SCREEN to listOf("홈", "상태관리", "열어", "가 줘", "가줘", "화면"),
    )

    fun detect(question: String): OfflineIntent? {
        val q = question.lowercase()
        // 더 구체적인 의도부터 본다: 경고 > 부품 > 점검 > 등급 > 대전 > 화면
        val order = listOf(
            OfflineIntent.WARNINGS,
            OfflineIntent.PART,
            OfflineIntent.INSPECT,
            OfflineIntent.GRADE,
            OfflineIntent.BATTLE,
            OfflineIntent.OPEN_SCREEN,
        )
        for (intent in order) {
            if (patterns.getValue(intent).any { q.contains(it) }) return intent
        }
        return null
    }

    fun toolName(intent: OfflineIntent): String = when (intent) {
        OfflineIntent.INSPECT -> "run_inspection"
        OfflineIntent.GRADE -> "get_latest_inspection"
        OfflineIntent.WARNINGS -> "get_active_warnings"
        OfflineIntent.PART -> "get_part_status"
        OfflineIntent.BATTLE -> "start_battle"
        OfflineIntent.OPEN_SCREEN -> "open_screen"
    }

    /**
     * 도구 결과 JSON 을 템플릿 문장으로 바꾼다.
     * 숫자는 도구 결과의 값을 그대로 쓰고, 없는 값을 지어내지 않는다.
     */
    fun template(intent: OfflineIntent, resultJson: String, drivingLimited: Boolean): String {
        val obj = runCatching { org.json.JSONObject(resultJson) }.getOrNull()
            ?: return "지금은 확인할 수 없어요. 잠시 뒤 다시 물어봐 주세요."
        if (!obj.optBoolean("ok", false)) {
            val reason = obj.optString("reason")
            return when {
                reason == "driving_limited" -> "주행 중에는 화면을 열 수 없어요. 안전한 곳에 정차한 뒤 다시 말해 주세요."
                reason == "no_measurement" -> "아직 점검 기록이 없어요. 점검해 주시면 등급을 알려 드릴게요."
                else -> "지금은 확인할 수 없어요. 잠시 뒤 다시 물어봐 주세요."
            }
        }
        val sentence = when (intent) {
            OfflineIntent.GRADE, OfflineIntent.INSPECT -> {
                val grade = obj.optString("grade")
                val overall = if (obj.isNull("overall")) null else obj.optInt("overall")
                val next = obj.optString("nextGrade")
                val points = if (obj.isNull("pointsToNext")) null else obj.optInt("pointsToNext")
                buildString {
                    if (grade.isNotEmpty()) append("지금 등급은 $grade").append(
                        if (overall != null) "이고 종합 $overall 점이에요. " else "이에요. ",
                    )
                    if (next.isNotEmpty() && points != null) append("$next 까지 $points 점 남았어요.")
                }.ifEmpty { "점검 결과를 확인했어요." }
            }
            OfflineIntent.WARNINGS -> {
                val total = obj.optInt("total")
                val red = obj.optInt("red")
                if (total == 0) {
                    "지금 켜진 경고등은 없어요."
                } else {
                    buildString {
                        if (red > 0) append("안전한 곳에 정차한 뒤 점검을 받으세요. ")
                        append("지금 경고등 $total 개가 켜져 있어요.")
                    }
                }
            }
            OfflineIntent.PART -> "부품 값을 확인했어요. 자세한 값은 상태관리에서 볼 수 있어요."
            OfflineIntent.BATTLE -> "대전은 대전 화면에서 시작할 수 있어요."
            OfflineIntent.OPEN_SCREEN -> "화면을 열었어요."
        }
        // 주행 중에는 2문장 이내로 자른다.
        return if (drivingLimited) limitSentences(sentence, 2) else limitSentences(sentence, 4)
    }

    fun limitSentences(text: String, max: Int): String {
        val parts = text.split(". ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= max) return text
        return parts.take(max).joinToString(". ") + "."
    }

    const val NO_MATCH = "차량 관련 질문을 이해하지 못했어요. 등급·경고등·부품·점검·대전 중 하나로 물어봐 주세요."
}

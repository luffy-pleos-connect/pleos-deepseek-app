package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.battle.CareSummary
import ai.moca.app.battle.CareSummaryBuilder
import ai.moca.app.battle.MonthlyRank
import ai.moca.app.battle.MonthlyRanking
import ai.moca.app.battle.MonthlyRow
import ai.moca.app.inspect.Inspector
import ai.moca.app.net.ApiClient
import ai.moca.app.score.ScoreBridge
import ai.moca.app.voice.ChatResult
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moca.core.Profile
import org.json.JSONArray
import org.json.JSONObject

/**
 * 월간랭킹(최종정리 7장·12장). 상대는 연습 상대뿐이다.
 * 왼쪽은 순위표, 오른쪽은 이번 달 내 관리 요약(기록에서 센 숫자) + AI 한 줄 + [질문하기].
 * AI 한 줄은 서버가 되면 확인된 숫자만 넘겨 받은 문장을, 안 되면 문장 틀을 쓴다. 숫자를 지어내지 않는다.
 */
class RankingFragment : Fragment(R.layout.fragment_ranking) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val today = ScoreBridge.today()
        view.findViewById<TextView>(R.id.rankingTitle).text = getString(R.string.ranking_title, today.monthValue)
        view.findViewById<View>(R.id.rankingBack).setOnClickListener { (activity as? MainActivity)?.goBattle() }
        viewLifecycleOwner.lifecycleScope.launch { load(view) }
    }

    private suspend fun load(view: View) {
        val app = requireActivity().application as MocaApp
        val result = app.latestInspection ?: try {
            Inspector(app).run(force = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val today = ScoreBridge.today()
        val ranking = result?.evaluation?.let { MonthlyRank.of(app.prefs.nickname, it) }
        val profile = if (app.prefs.demoEnabled) Profile.DEMO else Profile.REAL
        val summary = CareSummaryBuilder.of(app.store.records(profile), today, ranking)

        renderTable(view, ranking)
        renderSummary(view, summary)

        view.findViewById<View>(R.id.rankingAsk).setOnClickListener {
            val care = Bundle().apply {
                putInt(VoiceFragment.ARG_CARE_DAYS, summary.daysInspected)
                putInt(VoiceFragment.ARG_CARE_STREAK, summary.streak)
                putInt(VoiceFragment.ARG_CARE_RESOLVED, summary.resolved)
                // 순위가 없으면(등급이 없으면) 순위·차이는 넘기지 않는다.
                summary.gapToLeader?.let { putInt(VoiceFragment.ARG_CARE_GAP, it) }
                ranking?.let {
                    putInt(VoiceFragment.ARG_CARE_RANK, it.myRank)
                    putInt(VoiceFragment.ARG_CARE_TOTAL, it.total)
                }
            }
            (activity as? MainActivity)?.openVoiceWithCare(care)
        }

        val aiText = view.findViewById<TextView>(R.id.rankingAiText)
        val template = templateOf(summary)
        aiText.text = template
        askServer(app, summary, ranking)?.let { if (isAdded) aiText.text = it }
    }

    // ── 왼쪽: 순위표 ────────────────────────────────────────────────

    private fun renderTable(view: View, ranking: MonthlyRanking?) {
        val mine = view.findViewById<TextView>(R.id.rankingMine)
        val scroll = view.findViewById<ScrollView>(R.id.rankingScroll)
        val list = view.findViewById<LinearLayout>(R.id.rankingList)
        val empty = view.findViewById<View>(R.id.rankingEmpty)
        if (ranking == null) {
            mine.visibility = View.GONE
            scroll.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
        mine.text = if (ranking.myRank == 1) {
            getString(R.string.ranking_mine_first, ranking.total)
        } else {
            getString(
                R.string.ranking_mine,
                ranking.total, ranking.myRank, ranking.leader.nickname, ranking.leader.overall, ranking.gapToLeader,
            )
        }
        var myRow: View? = null
        ranking.rows.forEachIndexed { index, row ->
            val line = rowView(index + 1, row)
            list.addView(line)
            if (row.me) myRow = line
        }
        // 내 줄이 한 화면 밖이면 보이는 자리로 옮겨 둔다.
        scroll.post {
            val target = myRow ?: return@post
            scroll.scrollTo(0, (target.top - (scroll.height - target.height) / 2).coerceAtLeast(0))
        }
    }

    private fun rowView(place: Int, row: MonthlyRow): View {
        val ctx = requireContext()
        val pad = resources.getDimensionPixelSize(R.dimen.moca_gap_small)
        val gap = resources.getDimensionPixelSize(R.dimen.moca_gap)
        val style = if (row.me) ai.pleos.ds.R.style.Pleos_Text_Title_S_ExtraBold else ai.pleos.ds.R.style.Pleos_Text_Title_S
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.moca_row_bg)
            isSelected = row.me
            setPadding(pad, 0, pad, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.moca_ranking_row_height),
            )
        }
        fun cell(value: String, width: Int, weight: Float = 0f) = TextView(ctx).apply {
            text = value
            setTextAppearance(style)
            setTextColor(ContextCompat.getColor(ctx, ai.pleos.ds.R.color.pleos_text_dark_primary))
            layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }
        line.addView(cell(place.toString(), resources.getDimensionPixelSize(R.dimen.moca_score_column)))
        line.addView(
            cell(
                if (row.me) getString(R.string.ranking_me_name, row.nickname) else row.nickname,
                0, 1f,
            ),
        )
        line.addView(cell(row.gradeLabel, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setPadding(gap, 0, gap, 0) })
        line.addView(cell(row.overall.toString(), ViewGroup.LayoutParams.WRAP_CONTENT))
        return line
    }

    // ── 오른쪽: 이번 달 내 관리 요약 ─────────────────────────────────

    private fun renderSummary(view: View, summary: CareSummary) {
        val lines = view.findViewById<LinearLayout>(R.id.rankingCareLines)
        val rows = buildList {
            add(getString(R.string.ranking_care_days, summary.daysInspected))
            add(getString(R.string.ranking_care_streak, summary.streak))
            add(getString(R.string.ranking_care_resolved, summary.resolved))
            summary.gapToLeader?.let { add(getString(R.string.ranking_care_gap, it)) }
            add(
                if (summary.tips.isEmpty()) {
                    getString(R.string.ranking_care_no_tips)
                } else {
                    getString(R.string.ranking_care_tips, summary.tips.joinToString(", "))
                },
            )
        }
        rows.forEach { text ->
            lines.addView(TextView(requireContext()).apply {
                this.text = text
                setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_L)
                setTextColor(ContextCompat.getColor(requireContext(), ai.pleos.ds.R.color.pleos_text_dark_primary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.moca_gap_small) }
            })
        }
    }

    /** 서버가 안 될 때(또는 답이 맞지 않을 때) 쓰는 문장 틀. 기록에서 센 숫자만 넣는다. */
    private fun templateOf(summary: CareSummary): String =
        summary.gapToLeader?.let {
            getString(R.string.ranking_ai_template, summary.daysInspected, summary.streak, summary.resolved, it)
        } ?: getString(R.string.ranking_ai_template_no_rank, summary.daysInspected, summary.streak, summary.resolved)

    /**
     * 확인된 숫자를 «도구 결과(확인된 값)» 시스템 메시지로 넣어 /chat 에 한 번 묻는다(도구는 넘기지 않는다).
     * 클라우드 동의가 없거나, 서버 주소가 없거나, 오류·시간 초과이거나, 답에 확인된 값에 없는 숫자가 있으면 null.
     */
    private suspend fun askServer(app: MocaApp, summary: CareSummary, ranking: MonthlyRanking?): String? {
        if (!app.prefs.consentCloud) return null
        val facts = JSONObject().apply {
            put("점검한 날", summary.daysInspected)
            put("연속 점검", summary.streak)
            put("해결한 항목", summary.resolved)
            summary.gapToLeader?.let { put("1위와 점수 차이", it) }
            ranking?.let {
                put("순위", it.myRank)
                put("전체 인원", it.total)
            }
        }
        // apply 안에서 getString 을 부르면 JSONArray.getString(index) 가 불려 죽는다. 문장은 밖에서 꺼낸다.
        val system = getString(R.string.ranking_ai_system)
        val factsLine = getString(R.string.ranking_ai_facts, facts.toString())
        val question = getString(R.string.ranking_ai_question)
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "system").put("content", factsLine))
            .put(JSONObject().put("role", "user").put("content", question))
        val answer = try {
            ApiClient(app.prefs).chat(JSONObject().put("messages", messages))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val text = (answer as? ChatResult.Ok)?.message?.optString("content")?.trim().orEmpty()
        if (text.isEmpty()) return null
        val known = listOfNotNull(
            summary.daysInspected, summary.streak, summary.resolved, summary.gapToLeader, ranking?.myRank, ranking?.total,
        )
        return text.takeIf { CareSummaryBuilder.usesOnly(it, known) }
    }
}

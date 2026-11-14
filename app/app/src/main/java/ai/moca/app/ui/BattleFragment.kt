package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.battle.BattleRepository
import ai.moca.app.battle.Opponent
import ai.moca.app.battle.OpponentResult
import ai.moca.app.battle.opponentEvaluation
import ai.moca.app.inspect.Inspector
import ai.moca.app.net.ApiClient
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moca.core.Battle
import moca.core.BattleResult
import moca.core.BattleRound
import moca.core.BattleSide
import moca.core.DailyRank
import moca.core.Evaluation
import moca.core.RankRow

/**
 * 순위·대전. 왼쪽은 오늘의 연습 순위, 오른쪽은 부품 대전.
 * 판정은 :core `Battle` 이 한다. 이 화면은 표시와 진행만 맡는다.
 * 화면에 채워진 단추는 하나만 둔다. 진 뒤 해결 단추가 보이면 「다시」는 옅은 단추로 바뀐다.
 */
class BattleFragment : Fragment(R.layout.fragment_battle) {

    private lateinit var myGrade: TextView
    private lateinit var opponentName: TextView
    private lateinit var opponentKind: TextView
    private lateinit var opponentScore: TextView
    private lateinit var recordText: TextView
    private lateinit var resultText: TextView
    private lateinit var reasonText: TextView
    private lateinit var hintText: TextView
    private lateinit var fixButton: Button
    private lateinit var roundContainer: LinearLayout
    private lateinit var startButton: Button
    private lateinit var againButton: Button
    private lateinit var skipButton: Button
    private lateinit var demoBadge: TextView
    private lateinit var rankSummary: TextView
    private lateinit var rankPracticeBadge: TextView
    private lateinit var rankExplain: TextView
    private lateinit var rankListNote: TextView
    private lateinit var rankList: LinearLayout
    private lateinit var rankEmpty: TextView

    private var myEvaluation: Evaluation? = null
    private var opponent: Opponent? = null
    private var running = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        myGrade = view.findViewById(R.id.myGrade)
        opponentName = view.findViewById(R.id.opponentName)
        opponentKind = view.findViewById(R.id.opponentKind)
        opponentScore = view.findViewById(R.id.opponentScore)
        recordText = view.findViewById(R.id.battleRecord)
        resultText = view.findViewById(R.id.battleResult)
        reasonText = view.findViewById(R.id.battleReason)
        hintText = view.findViewById(R.id.battleHint)
        fixButton = view.findViewById(R.id.battleFixAction)
        roundContainer = view.findViewById(R.id.roundContainer)
        startButton = view.findViewById(R.id.buttonStartBattle)
        againButton = view.findViewById(R.id.buttonAgain)
        skipButton = view.findViewById(R.id.buttonSkip)
        demoBadge = view.findViewById(R.id.battleDemoBadge)
        rankSummary = view.findViewById(R.id.rankSummary)
        rankPracticeBadge = view.findViewById(R.id.rankPracticeBadge)
        rankExplain = view.findViewById(R.id.rankExplain)
        rankListNote = view.findViewById(R.id.rankListNote)
        rankList = view.findViewById(R.id.rankList)
        rankEmpty = view.findViewById(R.id.rankEmpty)

        val app = requireActivity().application as MocaApp
        demoBadge.visibility = if (app.prefs.demoEnabled) View.VISIBLE else View.GONE
        startButton.setOnClickListener { startBattle() }
        againButton.setOnClickListener { startBattle() }
        skipButton.setOnClickListener { skipRequested = true }

        viewLifecycleOwner.lifecycleScope.launch { loadLobby() }
    }

    private var skipRequested = false

    /** 디버그 자동 시연용: 상대를 찾은 뒤 대전을 시작한다. */
    fun autoStart() {
        viewLifecycleOwner.lifecycleScope.launch {
            delay(1200)
            if (opponent != null && myEvaluation?.grade != null) startBattle()
        }
    }

    private suspend fun loadLobby() {
        val pendingStart = arguments?.getBoolean(ARG_START) == true
        if (pendingStart) arguments?.putBoolean(ARG_START, false)

        val app = requireActivity().application as MocaApp
        val result = app.latestInspection ?: try {
            Inspector(app).run(force = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val eval = result?.evaluation
        myEvaluation = eval
        renderRank(app, eval)

        val grade = eval?.grade
        if (eval == null || grade == null) {
            opponent = null
            myGrade.text = getString(R.string.battle_no_record)
            opponentName.text = getString(R.string.battle_no_grade_opponent)
            opponentKind.visibility = View.GONE
            opponentScore.text = ""
            reasonText.visibility = View.GONE
            hintText.visibility = View.GONE
            roundContainer.visibility = View.VISIBLE
            fixButton.visibility = View.GONE
            againButton.visibility = View.GONE
            startButton.visibility = View.VISIBLE
            startButton.isEnabled = false
            return
        }
        myGrade.text = "${grade.label} · ${eval.displayedOverall ?: "—"}"
        showHint()

        val repo = BattleRepository(app.prefs)
        val (w, d, l) = repo.record()
        recordText.text = getString(R.string.battle_record, w, d, l)

        when (val found = repo.findOpponent(eval.categories)) {
            is OpponentResult.Found -> {
                opponent = found.opponent
                opponentName.text = found.opponent.nickname
                opponentScore.text = "${found.opponent.grade} · ${found.opponent.total.toInt()}"
                opponentKind.text = getString(R.string.battle_practice)
                opponentKind.visibility = View.VISIBLE
                startButton.isEnabled = true
            }
            is OpponentResult.Unavailable -> {
                opponentName.text = getString(R.string.battle_searching)
                opponentKind.visibility = View.GONE
                startButton.isEnabled = false
            }
        }
        if (pendingStart && opponent != null && !running) startBattle()
    }

    private fun renderRank(app: MocaApp, evaluation: Evaluation?) {
        rankList.removeAllViews()
        // 서버가 답하기 전에는 연습 순위 그대로다. 다시 그릴 때도 연습 값으로 되돌린다.
        rankPracticeBadge.text = getString(R.string.rank_practice_badge)
        rankExplain.text = getString(R.string.rank_explain)
        val board = if (evaluation?.grade == null) {
            null
        } else {
            DailyRank.board(app.prefs.nickname, evaluation)
        }
        if (board == null) {
            rankSummary.visibility = View.GONE
            rankList.visibility = View.GONE
            rankListNote.visibility = View.GONE
            rankEmpty.visibility = View.VISIBLE
            return
        }
        rankEmpty.visibility = View.GONE
        rankSummary.visibility = View.VISIBLE
        rankList.visibility = View.VISIBLE
        rankSummary.text = getString(R.string.rank_summary, board.total, board.rank)
        rankListNote.visibility = View.GONE
        val from = maxOf(0, board.rank - 1 - 2)
        DailyRank.window(board).forEachIndexed { index, row ->
            rankList.addView(rankRow(from + index + 1, row))
        }
        renderServerRank(app, evaluation)
    }

    /**
     * 연습 순위를 먼저 보이고, 서버가 실제 사용자를 셌으면 카드의 숫자·배지·설명을 바꾼다.
     * 아래 목록은 연습 상대만 보이므로 목록 위에 그렇다고 적는다.
     */
    private fun renderServerRank(app: MocaApp, evaluation: Evaluation?) {
        val total = evaluation?.displayedOverall ?: return
        val practice = DailyRank.board(app.prefs.nickname, evaluation)?.let { it.total - 1 } ?: 12
        viewLifecycleOwner.lifecycleScope.launch {
            val answer = ApiClient(app.prefs).rank(total) ?: return@launch
            if (!isAdded || answer.real <= 0) return@launch
            rankSummary.text = getString(R.string.rank_summary, answer.of, answer.rank)
            rankPracticeBadge.text = getString(R.string.rank_real_badge, answer.real)
            rankExplain.text = getString(R.string.rank_real_explain, practice, answer.real)
            rankListNote.visibility = View.VISIBLE
        }
    }

    private fun rankRow(place: Int, row: RankRow): View {
        val ctx = requireContext()
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.moca_row_bg)
            isSelected = row.isMe
            val pad = resources.getDimensionPixelSize(R.dimen.moca_gap_small)
            setPadding(pad, 0, pad, 0)
            val rowHeight = resources.getDimensionPixelSize(R.dimen.moca_status_row_min)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                rowHeight,
                1f,
            )
        }
        val gap = resources.getDimensionPixelSize(R.dimen.moca_gap)
        line.addView(rankText(place.toString(), ai.pleos.ds.R.style.Pleos_Text_Title_S, false))
        val name = if (row.isMe) getString(R.string.rank_me_name, row.nickname) else row.nickname
        line.addView(rankText(name, ai.pleos.ds.R.style.Pleos_Text_Title_S, true).also {
            it.setPadding(gap, 0, 0, 0)
        })
        line.addView(rankText(row.grade.label, ai.pleos.ds.R.style.Pleos_Text_Title_S, false))
        line.addView(rankText(row.overall.toString(), ai.pleos.ds.R.style.Pleos_Text_Title_S, false).also {
            it.setPadding(gap, 0, 0, 0)
        })
        return line
    }

    private fun rankText(value: String, style: Int, stretch: Boolean): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = value
            setTextAppearance(style)
            setTextColor(ContextCompat.getColor(ctx, ai.pleos.ds.R.color.pleos_text_dark_primary))
            layoutParams = if (stretch) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        }
    }

    private fun startBattle() {
        val mine = myEvaluation ?: return
        val theirs = opponent ?: return
        if (running) return
        running = true
        skipRequested = false
        resultText.text = ""
        reasonText.visibility = View.GONE
        hintText.visibility = View.GONE
        roundContainer.visibility = View.VISIBLE
        fixButton.visibility = View.GONE
        againButton.visibility = View.GONE
        startButton.visibility = View.VISIBLE
        roundContainer.removeAllViews()
        startButton.isEnabled = false

        val result = Battle.judge(mine, opponentEvaluation(theirs))
        val app = requireActivity().application as MocaApp

        viewLifecycleOwner.lifecycleScope.launch {
            skipButton.visibility = View.VISIBLE
            for (round in result.rounds) {
                if (!skipRequested) delay(ROUND_DELAY_MS)
                roundContainer.addView(roundCell(round))
            }
            skipButton.visibility = View.GONE
            showResult(result)
            running = false
            startButton.isEnabled = true
            startButton.text = getString(R.string.battle_again)

            BattleRepository(app.prefs).record(
                win = result.winner == BattleSide.MINE,
                draw = result.winner == BattleSide.DRAW,
            )
            loadLobby()
        }
    }

    private fun roundCell(round: BattleRound): View {
        val ctx = requireContext()
        val colorRes = when (round.winner) {
            BattleSide.MINE -> ai.pleos.ds.R.color.pleos_informative_positive
            BattleSide.THEIRS -> ai.pleos.ds.R.color.pleos_informative_negative
            BattleSide.DRAW -> ai.pleos.ds.R.color.pleos_text_dark_tertiary
        }
        val color = ContextCompat.getColor(ctx, colorRes)
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        cell.addView(TextView(ctx).apply {
            text = round.category.label
            gravity = Gravity.CENTER
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_L)
            setTextColor(ContextCompat.getColor(ctx, ai.pleos.ds.R.color.pleos_text_dark_secondary))
        })
        val mine = round.mine?.toString() ?: getString(R.string.home_grade_placeholder)
        val theirs = round.theirs?.toString() ?: getString(R.string.home_grade_placeholder)
        cell.addView(TextView(ctx).apply {
            text = getString(R.string.battle_round_score, mine, theirs)
            gravity = Gravity.CENTER
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_L)
            setTextColor(ContextCompat.getColor(ctx, ai.pleos.ds.R.color.pleos_text_dark_primary))
        })
        val verdictRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val dotSize = resources.getDimensionPixelSize(R.dimen.moca_status_dot)
        verdictRow.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            setBackgroundResource(R.drawable.moca_status_dot)
            backgroundTintList = ColorStateList.valueOf(color)
        })
        verdictRow.addView(TextView(ctx).apply {
            text = when (round.winner) {
                BattleSide.MINE -> getString(R.string.battle_round_win)
                BattleSide.THEIRS -> getString(R.string.battle_round_lose)
                BattleSide.DRAW -> getString(R.string.battle_round_draw)
            }
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_L)
            setTextColor(color)
            setPadding(resources.getDimensionPixelSize(R.dimen.moca_gap_small), 0, 0, 0)
        })
        cell.addView(verdictRow)
        return cell
    }

    /** 등급은 있는데 아직 겨뤄 본 적이 없을 때 라운드 자리 대신 안내를 둔다. */
    private fun showHint() {
        val hasResult = resultText.text.isNotEmpty() || roundContainer.childCount > 0
        val show = myEvaluation?.grade != null && !hasResult
        hintText.visibility = if (show) View.VISIBLE else View.GONE
        roundContainer.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showResult(result: BattleResult) {
        val text = when (result.winner) {
            BattleSide.MINE -> getString(R.string.battle_result_win)
            BattleSide.THEIRS -> getString(R.string.battle_result_lose)
            BattleSide.DRAW -> getString(R.string.battle_result_draw)
        }
        resultText.text = moca.core.BattleText.line(text, result.myWins, result.theirWins)
        resultText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when (result.winner) {
                    BattleSide.MINE -> ai.pleos.ds.R.color.pleos_informative_positive
                    BattleSide.THEIRS -> ai.pleos.ds.R.color.pleos_regulation_red
                    BattleSide.DRAW -> ai.pleos.ds.R.color.pleos_text_dark_primary
                },
            ),
        )
        showReason(result)
        showHint()
    }

    /** 왜 그렇게 됐는지 한 줄. 라운드가 같으면 종합 점수로 가른 이유까지 적는다. */
    private fun showReason(result: BattleResult) {
        val mine = myEvaluation?.displayedOverall
        val theirs = opponent?.let { it.total.toInt() }
        if (mine == null || theirs == null) {
            reasonText.visibility = View.GONE
            return
        }
        reasonText.text = moca.core.BattleText.reason(result.myWins, result.theirWins, mine, theirs)
        reasonText.visibility = View.VISIBLE
        showFixAction(result)
    }

    /**
     * 졌고 홈에 보여 줄 해결 단추가 있으면 같은 단추를 여기에도 둔다.
     * 채워진 단추는 하나만 두므로, 해결 단추가 보일 때는 「다시」를 옅은 단추로 바꾼다.
     */
    private fun showFixAction(result: BattleResult) {
        val host = activity as? MainActivity
        val target = TopProblemAction.of(requireActivity().application as MocaApp)
        if (result.winner != BattleSide.THEIRS || host == null || target == null) {
            fixButton.visibility = View.GONE
            againButton.visibility = View.GONE
            startButton.visibility = View.VISIBLE
            return
        }
        TopProblemAction.bind(host, fixButton, target)
        startButton.visibility = View.GONE
        againButton.visibility = View.VISIBLE
    }

    companion object {
        const val ARG_START = "start"
        private const val ROUND_DELAY_MS = 600L
    }
}

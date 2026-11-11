package ai.moca.app.ui

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
 */
class BattleFragment : Fragment(R.layout.fragment_battle) {

    private lateinit var myGrade: TextView
    private lateinit var opponentName: TextView
    private lateinit var opponentScore: TextView
    private lateinit var recordText: TextView
    private lateinit var resultText: TextView
    private lateinit var roundContainer: LinearLayout
    private lateinit var startButton: Button
    private lateinit var skipButton: Button
    private lateinit var demoBadge: TextView
    private lateinit var practiceBadge: TextView
    private lateinit var rankSummary: TextView
    private lateinit var rankList: LinearLayout
    private lateinit var rankEmpty: TextView

    private var myEvaluation: Evaluation? = null
    private var opponent: Opponent? = null
    private var running = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        myGrade = view.findViewById(R.id.myGrade)
        opponentName = view.findViewById(R.id.opponentName)
        opponentScore = view.findViewById(R.id.opponentScore)
        recordText = view.findViewById(R.id.battleRecord)
        resultText = view.findViewById(R.id.battleResult)
        roundContainer = view.findViewById(R.id.roundContainer)
        startButton = view.findViewById(R.id.buttonStartBattle)
        skipButton = view.findViewById(R.id.buttonSkip)
        demoBadge = view.findViewById(R.id.battleDemoBadge)
        practiceBadge = view.findViewById(R.id.battlePracticeBadge)
        rankSummary = view.findViewById(R.id.rankSummary)
        rankList = view.findViewById(R.id.rankList)
        rankEmpty = view.findViewById(R.id.rankEmpty)

        val app = requireActivity().application as MocaApp
        demoBadge.visibility = if (app.prefs.demoEnabled) View.VISIBLE else View.GONE
        startButton.setOnClickListener { startBattle() }
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
            myGrade.text = getString(R.string.battle_no_record)
            startButton.isEnabled = false
            return
        }
        myGrade.text = "${grade.label} · ${eval.displayedOverall ?: "—"}"

        val repo = BattleRepository(app.prefs, ApiClient(app.prefs))
        val (w, d, l) = repo.record()
        recordText.text = getString(R.string.battle_record, w, d, l)

        when (val found = repo.findOpponent(eval.categories)) {
            is OpponentResult.Found -> {
                opponent = found.opponent
                opponentName.text = found.opponent.nickname
                opponentScore.text = "${found.opponent.grade} · ${found.opponent.total.toInt()}"
                practiceBadge.visibility = if (found.isPractice) View.VISIBLE else View.GONE
                startButton.isEnabled = true
            }
            is OpponentResult.Unavailable -> {
                opponentName.text = getString(R.string.battle_searching)
                startButton.isEnabled = false
            }
        }
        if (pendingStart && opponent != null && !running) startBattle()
    }

    private fun renderRank(app: MocaApp, evaluation: Evaluation?) {
        rankList.removeAllViews()
        val board = if (evaluation?.grade == null) {
            null
        } else {
            DailyRank.board(app.prefs.nickname, evaluation)
        }
        if (board == null) {
            rankSummary.visibility = View.GONE
            rankList.visibility = View.GONE
            rankEmpty.visibility = View.VISIBLE
            return
        }
        rankEmpty.visibility = View.GONE
        rankSummary.visibility = View.VISIBLE
        rankList.visibility = View.VISIBLE
        rankSummary.text = getString(R.string.rank_summary, board.total, board.rank)
        val from = maxOf(0, board.rank - 1 - 2)
        DailyRank.window(board).forEachIndexed { index, row ->
            rankList.addView(rankRow(from + index + 1, row))
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val gap = resources.getDimensionPixelSize(R.dimen.moca_gap)
        line.addView(rankText(place.toString(), ai.pleos.ds.R.style.Pleos_Text_Title_S, false))
        val name = if (row.isMe) getString(R.string.rank_me_name, row.nickname) else row.nickname
        line.addView(rankText(name, ai.pleos.ds.R.style.Pleos_Text_Body_L, true).also {
            it.setPadding(gap, 0, 0, 0)
        })
        line.addView(rankText(row.grade.label, ai.pleos.ds.R.style.Pleos_Text_Body_L, false))
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
        roundContainer.removeAllViews()
        startButton.isEnabled = false

        val raw = Battle.judge(mine, opponentEvaluation(theirs))
        val app = requireActivity().application as MocaApp
        val result = if (app.prefs.demoEnabled) {
            moca.core.Demo.applyFixedRounds(raw, app.prefs.demoScenarioId)
        } else {
            raw
        }

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

            BattleRepository(app.prefs, ApiClient(app.prefs)).record(
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
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Label_M)
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

    private fun showResult(result: BattleResult) {
        val text = when (result.winner) {
            BattleSide.MINE -> getString(R.string.battle_result_win)
            BattleSide.THEIRS -> getString(R.string.battle_result_lose)
            BattleSide.DRAW -> getString(R.string.battle_result_draw)
        }
        val app = requireActivity().application as MocaApp
        val demoFixed = app.prefs.demoEnabled && moca.core.Demo.fixedBattleRounds(app.prefs.demoScenarioId) != null
        resultText.text = moca.core.BattleText.line(text, result.myWins, result.theirWins, demoFixed)
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
    }

    companion object {
        const val ARG_START = "start"
        private const val ROUND_DELAY_MS = 600L
    }
}

package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.battle.BattleRepository
import ai.moca.app.battle.Opponent
import ai.moca.app.battle.OpponentResult
import ai.moca.app.battle.opponentEvaluation
import ai.moca.app.net.ApiClient
import ai.moca.app.score.ScoreBridge
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moca.core.Battle
import moca.core.BattleResult
import moca.core.BattleSide
import moca.core.Evaluation

/**
 * 부품 대전. 로비 → 5라운드(0.6초 간격) → 결과.
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

        val app = requireActivity().application as MocaApp
        demoBadge.visibility = if (app.prefs.demoEnabled) View.VISIBLE else View.GONE
        startButton.setOnClickListener { startBattle() }
        skipButton.setOnClickListener { skipRequested = true }

        viewLifecycleOwner.lifecycleScope.launch { loadLobby() }
    }

    private var skipRequested = false

    private suspend fun loadLobby() {
        val app = requireActivity().application as MocaApp
        val source = app.vehicle()
        val snapshot = source.read()
        val eval = runCatching {
            ScoreBridge.evaluate(snapshot.items, source.cachedWarnings(), snapshot.isDemo)
        }.getOrNull()
        myEvaluation = eval

        if (eval?.grade == null) {
            myGrade.text = getString(R.string.battle_no_record)
            startButton.isEnabled = false
            return
        }
        myGrade.text = "${eval.grade.label} · ${eval.displayedOverall ?: "—"}"

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

        val result = Battle.judge(mine, opponentEvaluation(theirs))

        viewLifecycleOwner.lifecycleScope.launch {
            skipButton.visibility = View.VISIBLE
            for ((index, round) in result.rounds.withIndex()) {
                if (!skipRequested) delay(ROUND_DELAY_MS)
                roundContainer.addView(roundRow(index + 1, round))
            }
            skipButton.visibility = View.GONE
            showResult(result)
            running = false
            startButton.isEnabled = true
            startButton.text = getString(R.string.battle_again)

            val app = requireActivity().application as MocaApp
            BattleRepository(app.prefs, ApiClient(app.prefs)).record(
                win = result.winner == BattleSide.MINE,
                draw = result.winner == BattleSide.DRAW,
            )
            loadLobby()
        }
    }

    private fun roundRow(number: Int, round: moca.core.BattleRound): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val label = TextView(ctx).apply {
            text = "${number}. ${round.category.label}"
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_M)
            setTextColor(ContextCompat.getColor(ctx, ai.pleos.ds.R.color.pleos_text_dark_secondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val score = TextView(ctx).apply {
            text = getString(
                R.string.battle_score_line,
                round.mine ?: -1,
                round.theirs ?: -1,
            ).replace("-1", "—")
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_M_Strong)
            setTextColor(ContextCompat.getColor(ctx, ai.pleos.ds.R.color.pleos_text_dark_primary))
        }
        val verdict = TextView(ctx).apply {
            text = when (round.winner) {
                BattleSide.MINE -> getString(R.string.battle_round_win)
                BattleSide.THEIRS -> getString(R.string.battle_round_lose)
                BattleSide.DRAW -> getString(R.string.battle_round_draw)
            }
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_M_Strong)
            setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when (round.winner) {
                        BattleSide.MINE -> ai.pleos.ds.R.color.pleos_informative_positive
                        BattleSide.THEIRS -> ai.pleos.ds.R.color.pleos_regulation_red
                        BattleSide.DRAW -> ai.pleos.ds.R.color.pleos_text_dark_tertiary
                    },
                ),
            )
            setPadding(24, 0, 0, 0)
        }
        row.addView(label)
        row.addView(score)
        row.addView(verdict)
        return row
    }

    private fun showResult(result: BattleResult) {
        val text = when (result.winner) {
            BattleSide.MINE -> getString(R.string.battle_result_win)
            BattleSide.THEIRS -> getString(R.string.battle_result_lose)
            BattleSide.DRAW -> getString(R.string.battle_result_draw)
        }
        resultText.text = "$text  (${result.myWins} : ${result.theirWins})"
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

    private companion object {
        const val ROUND_DELAY_MS = 600L
    }
}

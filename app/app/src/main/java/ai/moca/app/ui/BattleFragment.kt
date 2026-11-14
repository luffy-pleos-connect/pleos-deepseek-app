package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.battle.BattleRepository
import ai.moca.app.battle.CatchUp
import ai.moca.app.battle.CatchUpItem
import ai.moca.app.battle.CatchUpPlanner
import ai.moca.app.battle.objectParticle
import ai.moca.app.inspect.InspectionResult
import ai.moca.app.inspect.Inspector
import ai.moca.app.ui.widget.CarDiagramLayout
import ai.moca.app.ui.widget.CarDiagramView
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import moca.core.Battle
import moca.core.BattleResult
import moca.core.BattleRound
import moca.core.BattleSide
import moca.core.Category
import moca.core.CategoryStatus
import moca.core.Evaluation
import moca.core.Seed
import kotlin.coroutines.resume

/**
 * 대전하기(최종정리 7장). 내 차와 상대 차를 나란히 놓고, 라운드 5개를 차례로 겨룬다.
 * 판정은 :core `Battle.judge` 하나만 쓴다(승패를 덮어쓰지 않는다). 이 화면은 표시와 연출만 맡는다.
 * 상대는 연습 상대(Seeds)뿐이고, 대전 결과는 등급·점수에 반영하지 않는다.
 * 연출은 한 번씩만 움직이고(반복 없음) 차가 부서지거나 찌그러지는 표현은 없다.
 */
class BattleFragment : Fragment(R.layout.fragment_battle) {

    private lateinit var myCar: CarDiagramView
    private lateinit var theirCar: CarDiagramView
    private lateinit var myName: TextView
    private lateinit var myScore: TextView
    private lateinit var theirName: TextView
    private lateinit var theirBadge: TextView
    private lateinit var theirScore: TextView
    private lateinit var roundLabel: TextView
    private lateinit var roundScore: TextView
    private lateinit var winnerMine: TextView
    private lateinit var winnerTheirs: TextView
    private lateinit var rounds: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var recordText: TextView
    private lateinit var catchUp: View
    private lateinit var catchUpLines: LinearLayout
    private lateinit var startButton: Button
    private lateinit var skipButton: Button
    private lateinit var againButton: Button
    private lateinit var rankingButton: Button

    private var myResult: InspectionResult? = null
    private var opponent: Seed? = null
    private var running = false
    private var skipSignal = CompletableDeferred<Unit>()
    private var currentAnimator: Animator? = null

    private enum class Phase { READY, RUNNING, DONE }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        myCar = view.findViewById(R.id.battleMyCar)
        theirCar = view.findViewById(R.id.battleTheirCar)
        myName = view.findViewById(R.id.battleMyName)
        myScore = view.findViewById(R.id.battleMyScore)
        theirName = view.findViewById(R.id.battleTheirName)
        theirBadge = view.findViewById(R.id.battleTheirBadge)
        theirScore = view.findViewById(R.id.battleTheirScore)
        roundLabel = view.findViewById(R.id.battleRoundLabel)
        roundScore = view.findViewById(R.id.battleRoundScore)
        winnerMine = view.findViewById(R.id.battleWinnerMine)
        winnerTheirs = view.findViewById(R.id.battleWinnerTheirs)
        rounds = view.findViewById(R.id.battleRounds)
        resultText = view.findViewById(R.id.battleResult)
        recordText = view.findViewById(R.id.battleRecord)
        catchUp = view.findViewById(R.id.battleCatchUp)
        catchUpLines = view.findViewById(R.id.battleCatchUpLines)
        startButton = view.findViewById(R.id.battleStart)
        skipButton = view.findViewById(R.id.battleSkip)
        againButton = view.findViewById(R.id.battleAgain)
        rankingButton = view.findViewById(R.id.battleRanking)

        // 이 Fragment 는 화면을 떠났다 돌아와도 같은 인스턴스다. 이전 화면의 진행 상태를 비운다.
        running = false
        skipSignal = CompletableDeferred()
        currentAnimator = null

        startButton.setOnClickListener { startBattle() }
        againButton.setOnClickListener { fightAgain() }
        skipButton.setOnClickListener {
            skipSignal.complete(Unit)
            currentAnimator?.cancel()
        }
        rankingButton.setOnClickListener { (activity as? MainActivity)?.goRanking() }

        viewLifecycleOwner.lifecycleScope.launch { loadLobby() }
    }

    override fun onDestroyView() {
        // 코루틴은 화면과 함께 취소된다. 움직이던 그림은 제자리로 돌려 둔다.
        currentAnimator?.cancel()
        currentAnimator = null
        super.onDestroyView()
    }

    // ── 로비 ────────────────────────────────────────────────────────

    private suspend fun loadLobby() {
        val app = requireActivity().application as MocaApp
        val result = app.latestInspection ?: try {
            Inspector(app).run(force = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        myResult = result
        val evaluation = result?.evaluation
        resetBoard()

        val grade = evaluation?.grade
        if (result == null || evaluation == null || grade == null) {
            // 등급이 없으면 겨룰 수 없다.
            opponent = null
            myCar.render(emptyMap(), emptySet())
            theirCar.render(emptyMap(), emptySet())
            myName.text = ""
            myScore.text = ""
            theirName.text = ""
            theirScore.text = ""
            theirBadge.text = ""
            resultText.text = getString(R.string.main_battle_no_grade)
            startButton.isEnabled = false
            showButtons(Phase.READY)
            return
        }

        myName.text = app.prefs.nickname
        myScore.text = getString(R.string.versus_grade_score, grade.label, evaluation.displayedOverall ?: 0)
        myCar.render(statusesOf(evaluation), result.engine.lowTireWheels)
        opponent = BattleRepository(app.prefs).findOpponent()
        renderOpponent(app)
        renderRecord(app)
        startButton.isEnabled = opponent != null
        showButtons(Phase.READY)
    }

    private fun renderOpponent(app: MocaApp) {
        val seed = opponent ?: return
        val evaluation = seed.evaluation()
        theirName.text = seed.nickname
        theirScore.text = getString(
            R.string.versus_grade_score,
            evaluation.grade?.label ?: seed.grade.label,
            evaluation.displayedOverall ?: seed.overall.toInt(),
        )
        theirBadge.text = getString(if (app.prefs.demoEnabled) R.string.c13_badge_demo else R.string.versus_practice_badge)
        // 상대 차는 저압 바퀴 정보가 없다.
        theirCar.render(statusesOf(evaluation), emptySet())
    }

    private fun renderRecord(app: MocaApp) {
        val (win, draw, lose) = BattleRepository(app.prefs).record()
        recordText.text = getString(R.string.versus_record, win, draw, lose)
    }

    private fun statusesOf(evaluation: Evaluation): Map<Category, CategoryStatus> =
        Category.entries.associateWith { CategoryStatus.of(evaluation.categories[it]) }

    /** 라운드 줄·결과·승리 표시·이 차만큼 카드를 처음 모양으로 되돌린다. */
    private fun resetBoard() {
        myCar.translationX = 0f
        theirCar.translationX = 0f
        roundLabel.text = ""
        roundScore.text = ""
        winnerMine.visibility = View.INVISIBLE
        winnerTheirs.visibility = View.INVISIBLE
        resultText.text = ""
        resultText.setTextColor(color(ai.pleos.ds.R.color.pleos_text_dark_primary))
        catchUp.visibility = View.GONE
        rounds.removeAllViews()
        Battle.ROUND_ORDER.forEach { rounds.addView(roundCell(it, null)) }
    }

    private fun showButtons(phase: Phase) {
        startButton.visibility = if (phase == Phase.DONE || phase == Phase.RUNNING) View.GONE else View.VISIBLE
        skipButton.visibility = if (phase == Phase.RUNNING) View.VISIBLE else View.GONE
        againButton.visibility = if (phase == Phase.DONE) View.VISIBLE else View.GONE
        rankingButton.visibility = if (phase == Phase.RUNNING) View.GONE else View.VISIBLE
    }

    // ── 대전 ────────────────────────────────────────────────────────

    /** [다시]: 실제 모드는 다른 연습 상대를 뽑고(데모는 같은 상대), 바로 겨룬다. */
    private fun fightAgain() {
        if (running) return
        val app = requireActivity().application as MocaApp
        opponent = BattleRepository(app.prefs).findOpponent(previous = opponent?.nickname)
        resetBoard()
        renderOpponent(app)
        startBattle()
    }

    private fun startBattle() {
        val result = myResult ?: return
        val mine = result.evaluation ?: return
        val seed = opponent ?: return
        if (running) return
        running = true
        skipSignal = CompletableDeferred()
        resetBoard()
        showButtons(Phase.RUNNING)

        val theirs = seed.evaluation()
        val judged = Battle.judge(mine, theirs)
        val app = requireActivity().application as MocaApp

        viewLifecycleOwner.lifecycleScope.launch {
            for ((index, round) in judged.rounds.withIndex()) {
                if (!skipSignal.isCompleted) {
                    roundLabel.text = getString(R.string.versus_round_label, index + 1, round.category.label)
                    roundScore.text = ""
                    playRound(round)
                    // 건너뛰기를 누르면 기다리지 않고 넘어간다.
                    if (index < judged.rounds.lastIndex) withTimeoutOrNull(ROUND_DELAY_MS) { skipSignal.await() }
                }
                rounds.removeViewAt(index)
                rounds.addView(roundCell(round.category, round), index)
            }
            myCar.translationX = 0f
            theirCar.translationX = 0f
            roundLabel.text = ""
            roundScore.text = ""

            BattleRepository(app.prefs).record(
                win = judged.winner == BattleSide.MINE,
                draw = judged.winner == BattleSide.DRAW,
            )
            renderRecord(app)
            showResult(judged, mine, theirs)
            renderCatchUp(CatchUpPlanner.of(result.engine, mine, theirs))
            showButtons(Phase.DONE)
            running = false
        }
    }

    /**
     * 한 라운드 연출. 두 그림이 가운데로 다가가 부딪치고(약 250ms), 점수가 낮은 쪽이 뒤로 더 밀려났다가(약 250ms)
     * 둘 다 제자리로 돌아온다(약 250ms). 무승부는 다가갔다가 제자리로 돌아오기만 한다.
     */
    private suspend fun playRound(round: BattleRound) {
        val travel = travelPx()
        val recoil = resources.getDimension(R.dimen.moca_battle_recoil)
        animate(myCar to travel, theirCar to -travel)
        if (skipSignal.isCompleted) return
        roundScore.text = getString(R.string.versus_pair, scoreText(round.mine), scoreText(round.theirs))
        // 진 쪽만 뒤(제자리 바깥쪽)로 더 밀려난다. 이긴 쪽은 부딪친 자리에 있다.
        when (round.winner) {
            BattleSide.MINE -> animate(theirCar to recoil)
            BattleSide.THEIRS -> animate(myCar to -recoil)
            BattleSide.DRAW -> Unit
        }
        if (skipSignal.isCompleted) return
        animate(myCar to 0f, theirCar to 0f)
    }

    private fun scoreText(score: Int?): String = score?.toString() ?: getString(R.string.home_grade_placeholder)

    /** 두 그림이 서로 닿을 때까지 한쪽이 움직일 거리(px). 그려진 차의 폭을 빼서 계산한다. */
    private fun travelPx(): Float {
        val mine = IntArray(2)
        val theirs = IntArray(2)
        myCar.getLocationInWindow(mine)
        theirCar.getLocationInWindow(theirs)
        val centers = (theirs[0] + theirCar.width / 2f) - (mine[0] + myCar.width / 2f)
        val drawn = minOf(myCar.width.toFloat(), myCar.height * CarDiagramLayout.CAR_ASPECT)
        return ((centers - drawn) / 2f).coerceAtLeast(0f)
    }

    private suspend fun animate(vararg moves: Pair<View, Float>) = suspendCancellableCoroutine { cont ->
        val set = AnimatorSet().apply {
            playTogether(moves.map { (view, x) -> ObjectAnimator.ofFloat(view, View.TRANSLATION_X, x) })
            duration = STEP_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }
        currentAnimator = set
        cont.invokeOnCancellation { set.cancel() }
        set.start()
    }

    // ── 결과 ────────────────────────────────────────────────────────

    private fun showResult(judged: BattleResult, mine: Evaluation, theirs: Evaluation) {
        val myOverall = mine.displayedOverall ?: 0
        val theirOverall = theirs.displayedOverall ?: 0
        val text = when (judged.winner) {
            BattleSide.MINE -> if (judged.tieBreakUsed) {
                getString(R.string.versus_result_win_overall, judged.myWins, judged.theirWins, myOverall, theirOverall)
            } else {
                getString(R.string.versus_result_win, judged.myWins, judged.theirWins)
            }
            BattleSide.THEIRS -> if (judged.tieBreakUsed) {
                getString(R.string.versus_result_lose_overall, judged.myWins, judged.theirWins, myOverall, theirOverall)
            } else {
                getString(R.string.versus_result_lose, judged.myWins, judged.theirWins)
            }
            BattleSide.DRAW -> getString(R.string.versus_result_draw)
        }
        resultText.text = text
        resultText.setTextColor(
            color(
                when (judged.winner) {
                    BattleSide.MINE -> ai.pleos.ds.R.color.pleos_informative_positive
                    BattleSide.THEIRS -> ai.pleos.ds.R.color.pleos_regulation_red
                    BattleSide.DRAW -> ai.pleos.ds.R.color.pleos_text_dark_primary
                },
            ),
        )
        val mark = getString(if (judged.winner == BattleSide.DRAW) R.string.versus_draw_mark else R.string.versus_winner_mark)
        winnerMine.text = mark
        winnerTheirs.text = mark
        winnerMine.visibility = if (judged.winner == BattleSide.THEIRS) View.INVISIBLE else View.VISIBLE
        winnerTheirs.visibility = if (judged.winner == BattleSide.MINE) View.INVISIBLE else View.VISIBLE
    }

    // ── 이 차만큼 관리하려면 ─────────────────────────────────────────

    private fun renderCatchUp(plan: CatchUp) {
        catchUpLines.removeAllViews()
        plan.lost.forEach { catchUpLines.addView(lostRow(it)) }
        plan.unmeasured.forEach {
            catchUpLines.addView(lineText(getString(R.string.versus_catchup_unmeasured, it.label)))
        }
        if (plan.ahead.isNotEmpty()) {
            catchUpLines.addView(
                lineText(getString(R.string.versus_catchup_ahead, plan.ahead.joinToString(", ") { it.label })),
            )
        }
        catchUp.visibility = if (catchUpLines.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun lostRow(item: CatchUpItem): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val names = item.fixes.joinToString(", ") { it.label }
        val sentence = when {
            item.fixes.isEmpty() -> getString(R.string.versus_catchup_nothing, item.category.label, item.mine, item.theirs)
            item.reaches -> getString(
                R.string.versus_catchup_reaches,
                item.category.label, item.mine, item.theirs, names + objectParticle(names), item.after,
            )
            else -> getString(
                R.string.versus_catchup_short,
                item.category.label, item.mine, item.theirs, names + objectParticle(names), item.after,
            )
        }
        row.addView(lineText(sentence).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 고칠 항목이 있을 때만 그 구역의 상태관리 화면으로 보낸다.
        if (item.fixes.isNotEmpty()) {
            row.addView(Button(requireContext(), null, 0, ai.pleos.ds.R.style.Widget_Pleos_Button_Primary_S).apply {
                text = getString(R.string.versus_catchup_go)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = resources.getDimensionPixelSize(R.dimen.moca_gap) }
                setOnClickListener { (activity as? MainActivity)?.goStatus(item.category) }
            })
        }
        return row
    }

    private fun lineText(value: String): TextView = TextView(requireContext()).apply {
        text = value
        setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_L)
        setTextColor(color(ai.pleos.ds.R.color.pleos_text_dark_primary))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.moca_gap_small) }
    }

    // ── 라운드 줄 ───────────────────────────────────────────────────

    /** 라운드 칸 하나. [round] 가 null 이면 아직 안 겨룬 칸(대기)이다. */
    private fun roundCell(category: Category, round: BattleRound?): View {
        val ctx = requireContext()
        val colorRes = when (round?.winner) {
            BattleSide.MINE -> ai.pleos.ds.R.color.pleos_informative_positive
            BattleSide.THEIRS -> ai.pleos.ds.R.color.pleos_informative_negative
            BattleSide.DRAW, null -> ai.pleos.ds.R.color.pleos_text_dark_tertiary
        }
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        cell.addView(cellText(category.label, ai.pleos.ds.R.color.pleos_text_dark_secondary))
        cell.addView(
            cellText(
                if (round == null) getString(R.string.home_grade_placeholder)
                else getString(R.string.versus_pair, scoreText(round.mine), scoreText(round.theirs)),
                ai.pleos.ds.R.color.pleos_text_dark_primary,
            ),
        )
        val verdictRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val dotSize = resources.getDimensionPixelSize(R.dimen.moca_status_dot)
        verdictRow.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply { gravity = Gravity.CENTER_VERTICAL }
            setBackgroundResource(R.drawable.moca_status_dot)
            backgroundTintList = ColorStateList.valueOf(color(colorRes))
        })
        verdictRow.addView(
            cellText(
                getString(
                    when (round?.winner) {
                        BattleSide.MINE -> R.string.versus_round_win
                        BattleSide.THEIRS -> R.string.versus_round_lose
                        BattleSide.DRAW -> R.string.versus_round_draw
                        null -> R.string.versus_round_pending
                    },
                ),
                colorRes,
            ).apply { setPadding(resources.getDimensionPixelSize(R.dimen.moca_gap_small), 0, 0, 0) },
        )
        cell.addView(verdictRow)
        return cell
    }

    private fun cellText(value: String, colorRes: Int): TextView = TextView(requireContext()).apply {
        text = value
        gravity = Gravity.CENTER
        setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_L)
        setTextColor(color(colorRes))
    }

    private fun color(res: Int): Int = ContextCompat.getColor(requireContext(), res)

    companion object {
        private const val ROUND_DELAY_MS = 600L

        /** 다가가기·밀려나기·제자리 돌아오기 한 단계의 길이. */
        private const val STEP_MS = 250L
    }
}

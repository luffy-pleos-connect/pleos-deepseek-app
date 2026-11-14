package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.battle.BattleRepository
import ai.moca.app.battle.CatchUp
import ai.moca.app.battle.CatchUpItem
import ai.moca.app.battle.CatchUpPlanner
import ai.moca.app.battle.CrashVariant
import ai.moca.app.battle.objectParticle
import ai.moca.app.inspect.InspectionResult
import ai.moca.app.inspect.Inspector
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import moca.core.Battle
import moca.core.BattleResult
import moca.core.BattleRound
import moca.core.BattleSide
import moca.core.Category
import moca.core.Evaluation
import moca.core.Seed
import kotlin.coroutines.resume

/**
 * 대전하기(최종정리 7장). 내 차와 상대 차를 나란히 놓고, 라운드 5개를 차례로 겨룬다.
 * 판정은 :core `Battle.judge` 하나만 쓴다(승패를 덮어쓰지 않는다). 이 화면은 표시와 연출만 맡는다.
 * 상대는 연습 상대(Seeds)뿐이고, 대전 결과는 등급·점수에 반영하지 않는다.
 * [대전 시작]을 누르면 결과에 맞는 3D 충돌 영상(res/raw)을 한 번 재생하고, 끝나면(또는 건너뛰면) 결과를 보인다.
 * 진 쪽 차는 부서진 그림, 이긴 쪽은 멀쩡한 그림이다(무승부는 둘 다 멀쩡). 영상 재생이 실패하면 영상 없이 바로 결과를 보인다.
 */
class BattleFragment : Fragment(R.layout.fragment_battle) {

    private lateinit var myCar: ImageView
    private lateinit var theirCar: ImageView
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
    private lateinit var crashOverlay: View
    private lateinit var crashVideo: VideoView

    private var myResult: InspectionResult? = null
    private var opponent: Seed? = null
    private var running = false

    /** 영상이 재생 중일 때만 있다. 부르면 재생을 끝내고 결과로 넘어간다([건너뛰기]·화면 이탈). */
    private var finishCrash: (() -> Unit)? = null

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
        crashOverlay = view.findViewById(R.id.battleCrashOverlay)
        crashVideo = view.findViewById(R.id.battleCrashVideo)

        // 이 Fragment 는 화면을 떠났다 돌아와도 같은 인스턴스다. 이전 화면의 진행 상태를 비운다.
        running = false
        finishCrash = null
        crashOverlay.visibility = View.GONE

        startButton.setOnClickListener { startBattle() }
        againButton.setOnClickListener { fightAgain() }
        skipButton.setOnClickListener { finishCrash?.invoke() }
        rankingButton.setOnClickListener { (activity as? MainActivity)?.goRanking() }

        viewLifecycleOwner.lifecycleScope.launch { loadLobby() }
    }

    override fun onPause() {
        // 화면을 떠나면 재생을 멈춘다(결과 계산은 이어서 끝난다).
        finishCrash?.invoke()
        super.onPause()
    }

    override fun onDestroyView() {
        // 코루틴은 화면과 함께 취소된다. 재생 중이던 영상은 해제한다.
        stopCrash()
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
        theirBadge.text = getString(R.string.versus_practice_badge)
    }

    private fun renderRecord(app: MocaApp) {
        val (win, draw, lose) = BattleRepository(app.prefs).record()
        recordText.text = getString(R.string.versus_record, win, draw, lose)
    }

    /** 라운드 줄·결과·승리 표시·이 차만큼 카드를 처음 모양으로 되돌리고, 두 차를 멀쩡한 그림으로 둔다. */
    private fun resetBoard() {
        myCar.setImageResource(R.drawable.battle_car_mine)
        theirCar.setImageResource(R.drawable.battle_car_theirs)
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
        resetBoard()
        showButtons(Phase.RUNNING)

        val theirs = seed.evaluation()
        val judged = Battle.judge(mine, theirs)
        val app = requireActivity().application as MocaApp

        viewLifecycleOwner.lifecycleScope.launch {
            val variant = CrashVariant.of(judged.winner)
            try {
                // 영상이 멈춰 끝나지 않는 경우를 대비해 한도를 둔다. 한도가 지나면 영상 없이 결과로 넘어간다.
                withTimeoutOrNull(CRASH_MAX_MS) { playCrash(variant) }
            } finally {
                stopCrash()
            }
            roundLabel.text = ""
            roundScore.text = ""
            judged.rounds.forEachIndexed { index, round ->
                rounds.removeViewAt(index)
                rounds.addView(roundCell(round.category, round), index)
            }

            BattleRepository(app.prefs).record(
                win = judged.winner == BattleSide.MINE,
                draw = judged.winner == BattleSide.DRAW,
            )
            renderRecord(app)
            showResult(judged, mine, theirs)
            myCar.setImageResource(if (variant.mineWrecked) R.drawable.battle_car_mine_wrecked else R.drawable.battle_car_mine)
            theirCar.setImageResource(
                if (variant.theirsWrecked) R.drawable.battle_car_theirs_wrecked else R.drawable.battle_car_theirs,
            )
            renderCatchUp(CatchUpPlanner.of(result.engine, mine, theirs))
            showButtons(Phase.DONE)
            running = false
        }
    }

    /**
     * 충돌 영상을 한 번 재생한다. 끝나거나, 건너뛰거나, 재생 오류가 나거나, 시작에 실패하면 돌아온다.
     * 영상 파일(res/raw)은 소리가 없고 마지막 장면으로 멈춘다.
     */
    private suspend fun playCrash(variant: CrashVariant) = suspendCancellableCoroutine<Unit> { cont ->
        val done = { if (cont.isActive) cont.resume(Unit) }
        finishCrash = done
        try {
            val raw = when (variant) {
                CrashVariant.MINE_WINS -> R.raw.battle_crash_mine_wins
                CrashVariant.THEIRS_WINS -> R.raw.battle_crash_theirs_wins
                CrashVariant.DRAW -> R.raw.battle_crash_draw
            }
            crashVideo.setOnCompletionListener { done() }
            // true 를 돌려주면 시스템의 «동영상을 재생할 수 없습니다» 대화상자를 띄우지 않는다.
            crashVideo.setOnErrorListener { _, _, _ -> done(); true }
            crashVideo.setVideoURI(Uri.parse("android.resource://${requireContext().packageName}/$raw"))
            crashOverlay.visibility = View.VISIBLE
            crashVideo.start()
        } catch (e: Exception) {
            done()
        }
    }

    /** 오버레이를 걷고 MediaPlayer 를 해제한다. 여러 번 불러도 된다. */
    private fun stopCrash() {
        finishCrash = null
        crashVideo.setOnCompletionListener(null)
        crashVideo.setOnErrorListener(null)
        crashVideo.stopPlayback()
        crashOverlay.visibility = View.GONE
    }

    private fun scoreText(score: Int?): String = score?.toString() ?: getString(R.string.home_grade_placeholder)

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
        /** 영상(5~6초)이 이보다 오래 끝나지 않으면 멈춘 것으로 보고 결과로 넘어간다. */
        private const val CRASH_MAX_MS = 15_000L
    }
}

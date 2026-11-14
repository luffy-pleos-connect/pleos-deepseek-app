package ai.moca.app.battle

import moca.core.BattleSide

/** 대전 충돌 영상의 종류. 결과(:core 판정)에서 하나로 정해지고, 어느 차가 부서진 그림을 보일지도 여기서 정한다. */
enum class CrashVariant {
    MINE_WINS,
    THEIRS_WINS,
    DRAW;

    /** 내 차가 부서진 그림을 보이는가(졌을 때만). */
    val mineWrecked: Boolean get() = this == THEIRS_WINS

    /** 상대 차가 부서진 그림을 보이는가(내가 이겼을 때만). */
    val theirsWrecked: Boolean get() = this == MINE_WINS

    companion object {
        fun of(winner: BattleSide): CrashVariant = when (winner) {
            BattleSide.MINE -> MINE_WINS
            BattleSide.THEIRS -> THEIRS_WINS
            BattleSide.DRAW -> DRAW
        }
    }
}

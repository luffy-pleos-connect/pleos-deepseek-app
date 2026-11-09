package ai.moca.app.ui

import ai.moca.app.R
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment

/** 대전: 이번 단계는 로비 골격. 시드·서버 상대·연습 상대는 다음 단계. */
class BattleFragment : Fragment(R.layout.fragment_battle) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val result = view.findViewById<TextView>(R.id.battleResult)
        view.findViewById<View>(R.id.buttonStartBattle).setOnClickListener {
            result.text = getString(R.string.c12_inspect_first)
        }
    }
}

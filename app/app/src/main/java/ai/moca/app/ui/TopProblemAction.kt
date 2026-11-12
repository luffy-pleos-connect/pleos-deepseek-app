package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.MocaApp
import ai.moca.app.inspect.InspectionResult
import ai.moca.app.link.FixLinks
import ai.pleos.ds.PleosToast
import android.widget.Button
import moca.core.FixAction
import moca.core.Priority

/**
 * 「가장 먼저 할 일」의 해결 단추. 홈·대전·차에게 묻기가 같은 함수를 쓴다.
 * 등급이 없거나 급한 문제가 없으면 null 이다.
 */
object TopProblemAction {

    data class Target(val label: String, val action: FixAction)

    fun of(result: InspectionResult?): Target? {
        if (result == null) return null
        val evaluation = result.evaluation ?: return null
        if (evaluation.grade == null) return null
        val problem = Priority.topProblem(result.engine) ?: return null
        val action = Priority.actionFor(problem, result.engine)
        return Target(action.label, action)
    }

    fun of(app: MocaApp): Target? = of(app.latestInspection)

    fun bind(host: MainActivity, button: Button, target: Target) {
        button.text = target.label
        button.visibility = android.view.View.VISIBLE
        button.setOnClickListener {
            FixLinks.start(host, target.action, host.isDrivingLimited()) { linkResult ->
                PleosToast.show(host, linkResult.message)
            }
        }
    }
}

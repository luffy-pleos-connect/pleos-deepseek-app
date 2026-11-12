package ai.moca.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView
import ai.moca.app.R

/**
 * 내용이 짧으면 내용만큼만 커지고, 길면 [R.dimen.moca_answer_max_height] 에서 멈추고 안에서 스크롤한다.
 * 답이 길어도 그 아래 질문 단추와 입력 칸이 화면 밖으로 밀리지 않게 하려는 것이다.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    private val maxHeight: Int = context.resources.getDimensionPixelSize(R.dimen.moca_answer_max_height)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentMode = MeasureSpec.getMode(heightMeasureSpec)
        val parentSize = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (parentMode == MeasureSpec.UNSPECIFIED) maxHeight else minOf(maxHeight, parentSize)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

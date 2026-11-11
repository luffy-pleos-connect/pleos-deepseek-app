package ai.moca.app.link

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** QR 모듈을 정사각 비트맵으로 그린다. 점의 검정·흰색은 비트맵 픽셀이다. */
object QrBitmap {
    private const val QUIET_MODULES = 4

    fun render(modules: Array<BooleanArray>, sizePx: Int): Bitmap {
        val side = sizePx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val n = modules.size
        if (n == 0) return bitmap
        val total = n + QUIET_MODULES * 2
        val cell = side.toFloat() / total
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        for (y in modules.indices) {
            val row = modules[y]
            for (x in row.indices) {
                if (!row[x]) continue
                val left = (x + QUIET_MODULES) * cell
                val top = (y + QUIET_MODULES) * cell
                canvas.drawRect(left, top, left + cell, top + cell, paint)
            }
        }
        return bitmap
    }
}

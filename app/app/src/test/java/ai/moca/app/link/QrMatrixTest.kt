package ai.moca.app.link

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrMatrixTest {
    private val hyundai = LinkTargets.HYUNDAI_REPAIR
    private val parts = LinkTargets.PARTS

    @Test
    fun `같은 주소는 같은 정사각 행렬이고 다른 주소는 다르다`() {
        val a = QrMatrix.encode(hyundai)
        val b = QrMatrix.encode(hyundai)
        val c = QrMatrix.encode(parts)
        assertTrue(a.isNotEmpty())
        assertEquals(a.size, a[0].size)
        assertTrue(a.contentDeepEquals(b))
        assertFalse(a.contentDeepEquals(c))
        for (row in a) assertEquals(a.size, row.size)
    }

    @Test
    fun `디코더로 다시 읽으면 원래 주소다`() {
        assertEquals(hyundai, decode(QrMatrix.encode(hyundai)))
        assertEquals(parts, decode(QrMatrix.encode(parts)))
    }

    private fun decode(modules: Array<BooleanArray>): String {
        val quiet = 4
        val scale = 4
        val modulesSide = modules.size + quiet * 2
        val side = modulesSide * scale
        val pixels = IntArray(side * side) { -1 } // ARGB 흰색
        for (y in modules.indices) {
            for (x in modules[y].indices) {
                if (!modules[y][x]) continue
                val originX = (x + quiet) * scale
                val originY = (y + quiet) * scale
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        val px = originX + dx
                        val py = originY + dy
                        pixels[py * side + px] = 255 shl 24 // ARGB 검정
                    }
                }
            }
        }
        val source = RGBLuminanceSource(side, side, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}

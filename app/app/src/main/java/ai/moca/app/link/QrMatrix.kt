package ai.moca.app.link

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** 글자를 QR 점 행렬로 바꾼다. 안드로이드 클래스를 쓰지 않는다. */
object QrMatrix {
    fun encode(text: String): Array<BooleanArray> {
        val matrix = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
        return Array(matrix.height) { y ->
            BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() != 0 }
        }
    }
}

package tv.live

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrGenerator {

    /** 生成二维码 Bitmap，失败返回 null */
    fun generate(text: String, sizePx: Int): Bitmap? {
        if (text.isEmpty() || sizePx <= 0) return null
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    pixels[y * w + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            bmp
        } catch (e: Exception) {
            null
        }
    }
}

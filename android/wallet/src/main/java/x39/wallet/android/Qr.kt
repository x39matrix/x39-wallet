package x39.wallet.android

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** QR de una dirección (dato público). */
object Qr {
    fun deDireccion(direccion: String, lado: Int): Bitmap {
        val m = QRCodeWriter().encode("bitcoin:$direccion", BarcodeFormat.QR_CODE, lado, lado, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.RGB_565)
        for (x in 0 until lado) for (y in 0 until lado) bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        return bmp
    }
}

package com.strmr.tv.ui.watchtogether

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code's modules, row by row (true is dark); null if [text] can't be encoded. */
class QrMatrix(val size: Int, private val bits: BooleanArray) {
    operator fun get(x: Int, y: Int) = bits[y * size + x]

    companion object {
        fun encode(text: String): QrMatrix? = runCatching {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            QrMatrix(m.width, BooleanArray(m.width * m.height) { m.get(it % m.width, it / m.width) })
        }.getOrNull()
    }
}

/** [text] as a black-on-white QR code with a quiet zone, drawn to fit [modifier]'s square. */
@Composable
fun QrCodeImage(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { QrMatrix.encode(text) } ?: return
    Canvas(modifier.background(Color.White).padding(12.dp)) {
        val cell = size.minDimension / matrix.size
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

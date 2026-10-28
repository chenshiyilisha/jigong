package com.jigong.update

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/**
 * 二维码的两件事：**生成**用 zxing core，**识别**用 ML Kit。
 *
 * 为什么这么分：
 *  - ML Kit 只能读不能写，生成这边没有替代品，zxing core 是最小的一把（纯 JVM，几百 KB）
 *  - 识别那边 zxing 要求自己做相机、解码、旋转，而 ML Kit 的模型是训练过的，
 *    在反光、暗光、斜着拿的情况下明显更稳
 *
 * 以前用的是 journeyapps 的 BarcodeEncoder + zxing-android-embedded 一体包。
 * 现在这个写法只多十几行，但把 151KB 的相机壳子换成了官方模型，也顺手摆脱了
 * 「自带 CaptureActivity 锁横屏」那个坑（见 QrScanActivity）。
 */
object QrCode {

    /**
     * 画一张二维码。
     *
     * 尺寸给 560px 而不是 300：屏幕上显示成 150dp，在 560dpi 的手机上就是 525px，
     * 按 1:1 出图才不会糊。被系统放大过的二维码边缘发虚，相机对焦会慢半拍。
     */
    fun make(content: String, sizePx: Int = 560): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            // 白边留 2 格。扫码器靠这圈"静区"定位，留 0 的时候在深色背景上经常扫不出来，
            // 而对方可能是拿另一台手机对着屏幕拍，背景是什么颜色不由我们决定。
            put(EncodeHintType.MARGIN, 2)
            // M 级容错（约 15%）：再高会把码点挤小，屏幕反光时更难认
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        }

        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /**
     * 把扫到的内容收拾成 `ip:端口`，收拾不出来就返回 null。
     *
     * 扫到的不一定是纯地址：对方可能是把地址写进一段文字，也可能贴了个 http:// 前缀，
     * 甚至有整条 URL 带路径的。这里只认主机名/IP + 可选端口，别的一律拒掉 ——
     * 宁可提示"扫到的不是地址"，也不要拿一段乱七八糟的字符串去发 HTTP 请求。
     */
    fun parse(raw: String?): String? {
        var t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        t = t.removePrefix("http://").removePrefix("https://").removePrefix("HTTP://")
        t = t.substringBefore('/').substringBefore('?').trim()
        val ok = Regex("""^[A-Za-z0-9.\-]{4,}(:\d{2,5})?$""").matches(t)
        return if (ok) t else null
    }
}

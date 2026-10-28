package com.jigong.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth

/**
 * 生成一张对账单图片，走系统分享面板（面板里选微信）。
 *
 * 刻意不接微信开放平台 SDK：那要 appid、要联网校验，会毁掉"零联网"这个前提。
 * 这里用纯 Canvas 画，不依赖界面渲染，所以任何页面都能随时调。
 */
object ShareImage {

    /**
     * 生成 1080×1080 的 ARGB 位图（4.4MB）再压成 PNG，实测 100~300ms。
     * 这段必须离开主线程 —— 否则点「分享」会先定一下，老人会以为没点着又去点第二次。
     * 只有最后 startActivity 回主线程。
     */
    suspend fun share(ctx: Context, ym: YearMonth, days: Double) {
        val uri = withContext(Dispatchers.IO) {
            val bmp = render(ym, days)
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            // 先清掉上个月的图：每张 ~100KB 不大，但会越攒越多
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "jigong-${ym.year}-${ym.monthValue}.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            // Android 7 起 file:// 会抛 FileUriExposedException，必须走 FileProvider
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // 加上 clipData，系统分享面板才会显示图片缩略图（否则只有一个文件图标）
            clipData = android.content.ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "分享对账单"))
    }

    private fun render(ym: YearMonth, days: Double): Bitmap {
        val w = 1080
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1A")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#555555")
            typeface = Typeface.DEFAULT
        }

        // 顶部蓝条
        c.drawRect(0f, 0f, w.toFloat(), 18f, Paint().apply { color = Color.parseColor("#1565C0") })

        soft.textSize = 62f
        c.drawText("${ym.year} 年 ${ym.monthValue} 月", 90f, 270f, soft)

        soft.textSize = 76f
        c.drawText("出勤", 90f, 400f, soft)

        val num = fmtDays(days)
        bold.textSize = 230f
        c.drawText(num, 90f, 680f, bold)

        val numWidth = bold.measureText(num)
        bold.textSize = 120f
        c.drawText("天", 90f + numWidth + 24f, 666f, bold)

        c.drawRect(
            90f, 760f, (w - 90).toFloat(), 765f,
            Paint().apply { color = Color.parseColor("#E6E6E6") }
        )

        soft.textSize = 52f
        c.drawText("本机记录 · 无云端", 90f, 1010f, soft)

        return bmp
    }

    private fun fmtDays(d: Double): String =
        if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
}

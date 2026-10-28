package com.jigong.update

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest

/**
 * 局域网更新：从电脑上那台小服务器（lan-update-server.py）拿新版本。
 *
 * 只做三件事，全部走明文 HTTP，只在局域网内：
 *   1. 广播一个 UDP 包喊一声「谁在发记工更新」→ 电脑回自己的地址（省得手打 IP）
 *   2. GET /version.json → 看清对面是什么版本
 *   3. GET /xxx.apk → 下载到 cache/update/，边下边算 SHA-256，对不上就扔掉
 *
 * 刻意不引 OkHttp / Retrofit：整个功能就两个 GET，加一个依赖不值当，
 * 而且这个 app 的体积本身也是设计目标之一。
 */
object Updater {

    const val DEFAULT_PORT = 8765
    private const val DISCOVER_PORT = 8766
    private const val PROBE = "JIGONG1"
    private const val REPLY_PREFIX = "JIGONG:"

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apk: String,
        val sha256: String,
        val size: Long
    )

    /**
     * 只认「主机名/IP + 可选端口」。
     *
     * 有这条白名单才能真正把错误挡在本地：不校验的话，一段乱七八糟的输入会一路
     * 走到 URL() 里，抛出来的是 MalformedURLException("Invalid URL") ——
     * 界面上直接出现这行英文，用户既看不懂，也不知道要改哪儿。
     */
    private val ADDR = Regex("""^[A-Za-z0-9][A-Za-z0-9.\-]*(:\d{1,5})?$""")

    /**
     * 中文输入法打出来的全角字符要折成半角，否则永远连不上。
     *
     * 最常撞的一种：中文状态下按冒号键，出来的是「：」（U+FF1A）而不是「:」。
     * 原样拼起来就变成 `http://192.168.1.10：8767`，URL() 直接抛
     * Invalid URL —— 用户不会想到是冒号的问题，只会觉得"这软件坏了"。
     * 全角数字、全角句点（．）同理，都是从别的窗口复制粘贴时最常见的污染。
     * 顺便把空格（含全角空格）全部去掉：`192.168.1.10 : 8767` 也该能认。
     */
    private fun normalize(raw: String): String = buildString {
        raw.forEach { c ->
            when {
                // U+FF01..FF5E 是全角 ASCII，和半角正好差 0xFEE0
                c.code in 0xFF01..0xFF5E -> append((c.code - 0xFEE0).toChar())
                c == '\u3000' || c.isWhitespace() -> Unit
                else -> append(c)
            }
        }
    }

    /** 用户可能只打 "192.168.1.20"，也可能连 http:// 一起粘进来，这里统一成基地址 */
    fun base(addr: String): String {
        var a = normalize(addr)
        if (a.isEmpty()) throw IOException("没有填对方地址")
        // 协议头不区分大小写：从浏览器地址栏复制来的常是 "HTTP://192.168.1.10"
        a = a.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "").trimEnd('/')
        if (!a.contains(':')) a = "$a:$DEFAULT_PORT"
        if (!ADDR.matches(a)) {
            throw IOException("地址不对。要像 192.168.1.10 或 192.168.1.10:8767 这样")
        }
        return "http://$a"
    }

    // ------------------------------------------------------------ 自动查找

    /**
     * 向本网段广播，把**所有**回话的更新源都收下来。
     *
     * 一次广播可能同时召回好几个：电脑上的 lan-update-server.py（8765）、
     * 隔壁工友手机上开着的「从我这更新」（8767）。都收回来交给界面挑版本最高的那个，
     * 这样「谁有新版本就用谁的」是自动的，不用用户自己判断。
     *
     * 为了不等满整个超时：收到第一个之后再等 settleMs 收剩下的就走。
     * 失败（不在同一 WiFi、防火墙全拦）返回空表，界面退回到手动填 IP。
     */
    fun discover(timeoutMs: Int = 2500, settleMs: Long = 700): List<String> {
        val targets = broadcastAddresses()
        if (targets.isEmpty()) return emptyList()

        return try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 300
                val payload = PROBE.toByteArray()
                targets.forEach { ia ->
                    runCatching { sock.send(DatagramPacket(payload, payload.size, ia, DISCOVER_PORT)) }
                }

                val found = LinkedHashSet<String>()
                val deadline = System.currentTimeMillis() + timeoutMs
                var firstAt = 0L
                val buf = ByteArray(128)

                while (true) {
                    val now = System.currentTimeMillis()
                    if (now >= deadline) break
                    // 已经收到过，再多等一小会儿收齐，不必耗满 2.5 秒
                    if (firstAt > 0 && now - firstAt > settleMs) break

                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                        val text = String(p.data, 0, p.length).trim()
                        if (text.startsWith(REPLY_PREFIX)) {
                            val addr = text.removePrefix(REPLY_PREFIX).trim()
                            if (addr.isNotEmpty() && found.add(addr) && firstAt == 0L) {
                                firstAt = System.currentTimeMillis()
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // 没回音，继续等
                    }
                }
                found.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 只取 IPv4 的广播地址。往 255.255.255.255 发不可靠，可能从移动数据跑掉 */
    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------ 查版本

    fun check(addr: String, timeoutMs: Int = 3000): Info {
        val conn = open("${base(addr)}/version.json", timeoutMs)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("电脑那边返回 ${conn.responseCode}")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            return Info(
                versionCode = o.getInt("versionCode"),
                versionName = o.optString("versionName", "?"),
                apk = o.getString("apk"),
                sha256 = o.optString("sha256", ""),
                size = o.optLong("size", 0L)
            )
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ 下载

    /**
     * 下到 cache/update/ 下。全部读完、SHA-256 对得上才算成功；
     * 任何一步不对就删掉文件抛异常 —— 宁可报错，不能把一个半截 APK 递给安装器
     * （半截包有时能装上，然后启动就崩，比装不上难查一百倍）。
     */
    fun download(
        ctx: Context,
        addr: String,
        info: Info,
        onProgress: (Int) -> Unit
    ): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        // 先清掉旧的，避免上一次失败留下的残骸被当成本次的
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update.apk")

        val conn = open("${base(addr)}/${info.apk}", 10_000)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("下载失败，电脑那边返回 ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPct = -1

            conn.inputStream.use { input ->
                FileOutputStream(out).use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    os.flush()
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256.isNotEmpty() && !actual.equals(info.sha256, ignoreCase = true)) {
                out.delete()
                throw IOException("文件校验没通过，可能是传输中断了，再试一次")
            }
            if (info.size > 0 && out.length() != info.size) {
                out.delete()
                throw IOException("文件大小不对，再试一次")
            }
            onProgress(100)
            return out
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val u = URL(url)
        return (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", "*/*")
        }
    }

    // ------------------------------------------------------------ 清理安装包

    /** 装完之后马上删。安装包是临时文件，18MB 不该长期占着手机 */
    fun clearUpdateCache(ctx: Context) {
        val n = File(ctx.cacheDir, "update").listFiles()?.count { it.delete() } ?: 0
        if (n > 0) android.util.Log.i("jigong", "更新完成，已删除 $n 个安装包")
    }

    /**
     * 兜底清理：万一没走到"装完删"那一步（比如用户装了但 app 没被重启），
     * 下次打开时判断一下缓存里的包是不是已经用过了。
     *
     * 判据：文件的修改时间早于"最后一次安装时间" → 那次安装用的就是它 → 删。
     * 反之说明是下载了但还没装成（比如用户取消了），留着，下次不用重传 18MB。
     */
    fun purgeStale(ctx: Context) {
        val dir = File(ctx.cacheDir, "update")
        if (!dir.isDirectory) return
        val installedAt = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < installedAt || f.lastModified() < weekAgo) {
                if (f.delete()) n++
            }
        }
        if (n > 0) android.util.Log.i("jigong", "启动清理：删除 $n 个过期安装包")
    }
}

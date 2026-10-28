package com.jigong.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * 把「本机装着的这个版本」导出成一个 APK 文件，发给别人。
 *
 * 为什么不能直接把 /data/app/.../base.apk 交给分享：
 * FileProvider 只允许暴露 <paths> 里声明过的根目录（cache / files / external-*）。
 * 把 /data/app/... 传给 getUriForFile() 会抛
 *   IllegalArgumentException: Failed to find configured root that contains /data/app/...
 * 这不是权限问题，改 Manifest 也修不好，社区里最常见的一个坑。
 * 所以必须先复制到 cache/share-apk/ 再分享 —— 见 export()。
 *
 * 一个必须知道的前提：**只有同一把 keystore 签出来的包，才能互相覆盖安装**。
 * 别人装了你发的包，以后再收到新版点安装才会成功；签名不同会直接
 * INSTALL_FAILED_UPDATE_INCOMPATIBLE，只能先卸载 —— 数据跟着没了。
 * 所以千万不要用 debug 签名打包发给别人。
 */
object ShareApk {

    private const val DIR = "share-apk"

    /** 当更新源用的目录，和分享用的分开，避免互相清掉 */
    const val SERVE_DIR = "serve-apk"

    /** 本机 APK 的真实路径。是自己装的文件，自己能读，不需要任何权限 */
    fun source(ctx: Context): File = File(ctx.applicationInfo.sourceDir)

    /**
     * 复制到 cache/<dir>/记工-1.8.apk 并返回。
     * 每次都先清空目录：每留一份就是 18MB，攒几次手机就满了。
     *
     * dir 必须区分用途：分享给微信用的放 share-apk，当更新源发给别人下载的放 serve-apk。
     * 两者共用一个目录会打架 —— 用户开着分享面板时去开「让别人从我这更新」，
     * 后者的 export 会把前者刚交出去的那份删掉，微信那边就读不到了。
     */
    fun export(ctx: Context, dir: String = DIR): File {
        val src = source(ctx)
        if (!src.isFile) throw IllegalStateException("找不到本机安装包：${src.absolutePath}")

        val target = File(ctx.cacheDir, dir).apply { mkdirs() }
        target.listFiles()?.forEach { it.delete() }

        val dst = File(target, "记工-${versionNameOf(ctx)}.apk")
        src.inputStream().use { input ->
            dst.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        // 校验一下复制是完整的：半截包发给别人，对方装到一半报错，比不给更糟
        if (dst.length() != src.length()) {
            dst.delete()
            throw IllegalStateException("导出失败，复制出来的文件不完整")
        }
        return dst
    }

    /**
     * 拉起系统分享面板。
     *
     * MIME 用 application/vnd.android.package-archive：微信/QQ/邮件都会正确识别成安装包。
     * 副作用是蓝牙收不到这个类型（蓝牙不吃 package-archive），所以想走蓝牙时
     * 系统面板里不会出现它 —— 这是取舍，不是 bug。
     */
    fun share(ctx: Context, apk: File, title: String = "把安装包发给谁"): Boolean = try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                title
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        android.util.Log.w("jigong", "分享安装包失败：${e.message}")
        false
    }

    /** 关掉页面时清掉，别让两份 18MB 长期躺在缓存里 */
    fun clean(ctx: Context) {
        File(ctx.cacheDir, DIR).listFiles()?.forEach { it.delete() }
        File(ctx.cacheDir, SERVE_DIR).listFiles()?.forEach { it.delete() }
    }

    /** 给对端做完整性校验用。18MB 大概 100ms，只在开服务时算一次 */
    fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    fun versionNameOf(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "?"

    fun versionCodeOf(ctx: Context): Int = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)
}

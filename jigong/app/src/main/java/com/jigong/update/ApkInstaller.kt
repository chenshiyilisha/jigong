package com.jigong.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把下载好的 APK 交给系统安装。走的是最通用的一条路，和你在浏览器里点 APK 下载是一样的：
 *
 *   1. 先确认用户允许过「记工」安装应用（Android 8 起按来源单独授权，且随时能被收回，
 *      所以每次安装前都要重新查，不能缓存结果）
 *   2. 用 FileProvider 把文件包成 content:// （直接给 file:// 会抛 FileUriExposedException）
 *   3. ACTION_VIEW + application/vnd.android.package-archive → 系统安装界面 → 用户点「安装」
 *
 * 为什么不用 PackageInstaller + setRequireUserAction(USER_ACTION_NOT_REQUIRED) 去赌静默安装：
 * 普通 App 没有 UPDATE_PACKAGES_WITHOUT_USER_ACTION（签名级权限），也没有设备管理器身份，
 * 系统会忽略这个请求照样弹框，但代码会多出一整套 session/回调/状态机，还可能被 ROM 拦。
 * 收益不稳定、代价确定 —— 不值。
 */
object ApkInstaller {

    fun canInstall(ctx: Context): Boolean =
        ctx.packageManager.canRequestPackageInstalls()

    /** 跳到"允许来自此来源的应用"开关。从设置回来后要重新查，不能假设用户已经打开 */
    fun openPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(ctx, "请到系统设置里允许「记工」安装应用", Toast.LENGTH_LONG).show()
        }
    }

    /** 返回 true 表示已经把安装界面拉起来了 */
    fun install(ctx: Context, apk: File): Boolean {
        if (!canInstall(ctx)) {
            openPermission(ctx)
            Toast.makeText(ctx, "请先允许「记工」安装应用，再点一次", Toast.LENGTH_LONG).show()
            return false
        }

        return try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (e: Exception) {
            Toast.makeText(ctx, "打不开安装界面：${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }
}

/**
 * 装完新版本后本进程会被系统杀掉，屏幕会停在桌面上。
 * 收这个系统广播把 app 重新拉到前台，用户看到的是"更新完就回来了"。
 */
class RelaunchReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // 更新已经落地，安装包没用了，立刻删掉（这里是唯一能确认"装成功了"的时刻）
        Updater.clearUpdateCache(ctx)

        runCatching {
            ctx.startActivity(
                Intent(ctx, com.jigong.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }
    }
}

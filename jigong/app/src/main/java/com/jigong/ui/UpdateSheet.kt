package com.jigong.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.jigong.update.ApkInstaller
import com.jigong.update.LanServer
import com.jigong.update.QrCode
import com.jigong.update.QrScanActivity
import com.jigong.update.ShareApk
import com.jigong.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/** 更新流程的几个状态。屏幕上永远只会停在其中一个，不会出现"点完没反应" */
private sealed interface Upd {
    data object Idle : Upd
    data object Checking : Upd
    data object Searching : Upd
    data object Latest : Upd
    data class New(val info: Updater.Info) : Upd
    data class Downloading(val pct: Int) : Upd
    data object Launched : Upd
    data class Failed(val msg: String) : Upd
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    addr: String,
    onAddrChange: (String) -> Unit,
    onDismiss: () -> Unit,
    // 分享这个月的工天。原来放在顶栏右侧（一个 18sp 的文字按钮），
    // 但它和「更新」抢同一条基线 —— 这台机只有 360dp 宽，塞不下。
    // 参考 M3 顶栏规范：顶栏只放「标题 + 一个入口」，低频操作收进面板；
    // 顺手也符合适老化要求（面板里能做成整行大按钮，44dp 的顶栏小字对老人太难点）。
    monthTitle: String,
    monthDays: Double,
    onShareMonth: () -> Unit,
    // 启动时静默查到的新版本号。顶栏只用它决定要不要挂红点，
    // 具体是什么版本在这里说清楚
    newVersion: String? = null
) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    val myName = remember { appVersionName(ctx) }
    val myCode = remember { appVersionCode(ctx) }

    var st by remember { mutableStateOf<Upd>(Upd.Idle) }
    var ready by remember { mutableStateOf<File?>(null) }
    // 缓存里那个 APK 到底是哪个版本。必须记住，不能只判断"文件存在"——
    // 否则服务器发了新版、缓存里还是旧版，就会把旧版装上去（真机上已经踩到过）
    var readyCode by remember { mutableIntStateOf(-1) }

    // 分享安装包
    var exporting by remember { mutableStateOf(false) }

    // 把本机当更新源
    var serving by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var serveIps by remember { mutableStateOf<List<LanServer.LanAddr>>(emptyList()) }
    var serveMsg by remember { mutableStateOf<String?>(null) }
    var servedCount by remember { mutableIntStateOf(0) }

    // 面板一关就把服务停掉、把导出的安装包删掉。
    // 服务不留在后台：国产 ROM 会掐后台网络，跑在眼前这一段最稳。
    DisposableEffect(Unit) {
        onDispose {
            LanServer.stop()
            ShareApk.clean(ctx)
        }
    }

    // 服务开着的时候刷新"已发出几份"，让对方那边下载有反馈
    LaunchedEffect(serving) {
        while (serving) {
            servedCount = LanServer.served
            delay(700)
        }
    }

    fun fail(e: Throwable) {
        st = Upd.Failed(
            when (e) {
                is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                    "连不上。检查三件事：两台设备在同一个 WiFi、对方那个窗口还开着、地址没打错"
                else -> e.message ?: "出错了"
            }
        )
    }

    fun check(a: String) {
        val target = a.trim()
        if (target.isEmpty()) {
            st = Upd.Failed("先填对方地址，或者点「自动查找」")
            return
        }
        st = Upd.Checking
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.check(target) }
                st = if (info.versionCode > myCode) Upd.New(info) else Upd.Latest
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /**
     * 广播找同网段里所有的更新源：电脑上的 lan-update-server.py（8765），
     * 和隔壁工友手机上开着的「从我这更新」（8767）。
     * 全部问一遍版本，用版本号最大的那个 —— 「谁有新版本就用谁的」是自动的。
     */
    fun search() {
        st = Upd.Searching
        scope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) { Updater.discover() }
                if (candidates.isEmpty()) {
                    st = Upd.Failed("没找到。确认两台手机（或电脑）连的是同一个 WiFi")
                    return@launch
                }
                val best = withContext(Dispatchers.IO) {
                    coroutineScope {
                        candidates.map { c ->
                            async { runCatching { c to Updater.check(c, 2500) }.getOrNull() }
                        }.awaitAll().filterNotNull().maxByOrNull { it.second.versionCode }
                    }
                }
                if (best == null) {
                    st = Upd.Failed("找到了设备但连不上：${candidates.joinToString("、")}")
                    return@launch
                }
                onAddrChange(best.first)
                st = if (best.second.versionCode > myCode) Upd.New(best.second) else Upd.Latest
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun downloadAndInstall(info: Updater.Info) {
        // 只有"这个文件正是当前这个版本"时才复用，否则一定重新下
        val cached = ready
        if (cached != null && cached.exists() && readyCode == info.versionCode) {
            if (ApkInstaller.install(ctx, cached)) st = Upd.Launched
            return
        }
        st = Upd.Downloading(0)
        scope.launch {
            try {
                val target = addr.trim()
                val f = withContext(Dispatchers.IO) {
                    Updater.download(ctx, target, info) { pct -> st = Upd.Downloading(pct) }
                }
                ready = f
                readyCode = info.versionCode
                // 装不上通常是因为用户还没允许「安装应用」（刚才已经跳到设置页了）。
                // 此时要留在"下载并安装"这个状态，用户回来直接再点一次就行 ——
                // ready 里已经有下好的文件，不会再传一遍 18MB
                if (ApkInstaller.install(ctx, f)) st = Upd.Launched else st = Upd.New(info)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun shareApk() {
        if (exporting) return
        exporting = true
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) { ShareApk.export(ctx) }
                if (!ShareApk.share(ctx, f)) {
                    Toast.makeText(ctx, "这台手机上没有能接收安装包的应用", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "导出安装包失败", Toast.LENGTH_LONG).show()
            } finally {
                exporting = false
            }
        }
    }

    fun toggleServe(on: Boolean) {
        if (!on) {
            LanServer.stop()
            serving = false
            serveIps = emptyList()
            serveMsg = null
            return
        }
        starting = true
        serveMsg = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { LanServer.start(ctx) }
            starting = false
            r.onSuccess {
                serveIps = LanServer.localIps(ctx)
                serving = true
                if (serveIps.isEmpty()) {
                    serveMsg = "没连上 WiFi。先连和对方同一个 WiFi，或者开热点让对方连你"
                }
            }.onFailure {
                serveMsg = "开不起来：${it.message}"
                serving = false
            }
        }
    }

    // ---------------------------------------------------------------- 扫码填入地址

    var qrBmp by remember { mutableStateOf<Bitmap?>(null) }

    // 服务开着的时候盯着地址变化。实测开热点的瞬间 wlan1(双 WiFi) 会消失、wlan2(热点)
    // 才出现，如果只在开开关那一刻算一次，界面就会一直挂着已经不通的旧地址，
    // 对方照着填必然连不上。每 2 秒重算一次（只是枚举网卡，开销可以忽略），
    // 列表没变就不写状态，避免二维码跟着白重画。
    LaunchedEffect(serving) {
        while (serving) {
            val now = withContext(Dispatchers.IO) { LanServer.localIps(ctx) }
            if (now != serveIps) serveIps = now
            delay(2000)
        }
    }

    // 地址变了就重画二维码。列表已按角色排好序（WiFi → 热点 → 其它），取第一个就是最优
    LaunchedEffect(serving, serveIps) {
        val ip = serveIps.firstOrNull()?.ip
        qrBmp = if (ip.isNullOrEmpty()) null else withContext(Dispatchers.Default) {
            runCatching { QrCode.make("$ip:${LanServer.PORT}") }.getOrNull()
        }
    }

    // 扫码页是 app 自己的 Activity（CameraX + ML Kit），不再依赖第三方扫码库。
    // 它只在"确实是地址码"时才回 RESULT_OK 并把解析好的 ip:port 放进 EXTRA_TEXT，
    // 所以这边不用再判格式；用户按返回键就是 RESULT_CANCELED，什么都不做。
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val scanned = result.data?.getStringExtra(QrScanActivity.EXTRA_TEXT)
        if (!scanned.isNullOrEmpty()) {
            onAddrChange(scanned)
            check(scanned)   // 扫完顺手查一次版本，省一次点击
        }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanLauncher.launch(QrScanActivity.intent(ctx))
        } else {
            Toast.makeText(ctx, "没给相机权限。也可以直接手填地址", Toast.LENGTH_LONG).show()
        }
    }

    fun startScan() {
        // 先自己问权限再开页面：否则会先看到扫码页闪一下、再弹系统权限框，
        // 老人容易以为点坏了。扫码页自己也查一次兜底。
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) scanLauncher.launch(QrScanActivity.intent(ctx))
        else cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    // 用全屏对话框，而不是 ModalBottomSheet。
    // 原因：sheet 自己的拖拽手势会把内部滚动抢走 —— 实测在 1.3 倍系统字号下内容溢出，
    // 往上滑完全没有反应（内容被裁在屏幕外，谁也看不到）。Dialog 里普通的
    // verticalScroll 是正常工作的。
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = Palette.sheetBg) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("更新与分享", fontSize = Dimen.title, fontWeight = FontWeight.Bold, color = Palette.ink)
                        Spacer(Modifier.height(2.dp))
                        Text("当前版本 $myName（$myCode）", fontSize = 15.sp, color = Palette.inkSoft)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Palette.accent)
                    }
                }

            // 顶栏那个「三横」上只有一个红点，不写清楚是什么事，老人会以为点坏了。
            // 进面板第一眼就得看到原因。
            if (newVersion != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.accentSoft, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        "有新版本 $newVersion（现在是 $myName）。往下点「自动查找」或「检查更新」就能装上。",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Palette.accent,
                        lineHeight = 24.sp
                    )
                }
            }

            // ---------------------------------------------------------- 〇、分享这个月
            Spacer(Modifier.height(18.dp))

            OutlinedButton(
                onClick = onShareMonth,
                enabled = monthDays > 0.0,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimen.sheetButtonHeight),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, if (monthDays > 0.0) Palette.accent else Palette.line)
            ) {
                Text(
                    "分享这个月的工天",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (monthDays > 0.0) Palette.accent else Palette.hint
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (monthDays > 0.0) {
                    "$monthTitle 已记 ${fmtDays(monthDays)} 天，生成一张图，发微信里给老板看"
                } else {
                    "$monthTitle 还没有记录，所以没得分享"
                },
                fontSize = 14.sp,
                color = Palette.inkSoft,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Palette.line, thickness = 1.dp)

            // ---------------------------------------------------------- 一、把安装包发给别人
            Spacer(Modifier.height(18.dp))

            OutlinedButton(
                onClick = { shareApk() },
                enabled = !exporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimen.sheetButtonHeight),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, Palette.accent)
            ) {
                Text(
                    if (exporting) "正在准备…" else "把安装包发给别人",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.accent
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "对方点开就能装。优先用手机自带的「互传」（vivo、小米、OPPO 都在这里）；" +
                    "微信会把 .apk 改名成 .apk.1，要用「其他应用打开」才能装。",
                fontSize = 14.sp,
                color = Palette.inkSoft,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Palette.line, thickness = 1.dp)
            Spacer(Modifier.height(14.dp))

            // ---------------------------------------------------------- 二、从别处更新（原有逻辑）
            Text("从电脑或别的手机更新", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = addr,
                onValueChange = onAddrChange,
                // 标签直接带格式说明：Compose 在输入框为空时会用标签占住 placeholder 的位置，
                // 所以格式提示写在标签里才看得见
                label = { Text("对方地址 IP:端口", fontSize = 15.sp) },
                placeholder = { Text("192.168.1.10:8767", fontSize = 16.sp, color = Palette.hint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = TextStyle(
                    // 18sp 是量出来的：22sp 时 "192.168.1.20:8765" 会顶到框外面被裁掉
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.ink
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 两个入口并排放在输入框下面：老人手指粗，按钮横着排容易点错，
            // 所以给足高度和间距，右对齐贴近"填完就按"的位置
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { startScan() }) {
                    Text(
                        "扫码填入",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.accent
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { search() },
                    enabled = st !is Upd.Searching && st !is Upd.Downloading
                ) {
                    Text(
                        if (st is Upd.Searching) "查找中…" else "自动查找",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.accent
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "自动查找会把电脑和别人的手机都找出来，用最新的那个",
                fontSize = 14.sp,
                color = Palette.inkSoft
            )

            Spacer(Modifier.height(14.dp))

            when (val s = st) {
                is Upd.New -> {
                    Button(
                        onClick = { downloadAndInstall(s.info) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimen.sheetButtonHeight),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent)
                    ) {
                        Text(
                            "下载并安装 ${s.info.versionName}",
                            fontSize = Dimen.sheetButton,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is Upd.Downloading -> {
                    LinearProgressIndicator(
                        progress = { s.pct / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("正在传新版本… ${s.pct}%", fontSize = 16.sp, color = Palette.inkSoft)
                }

                else -> {
                    Button(
                        onClick = { check(addr) },
                        enabled = st !is Upd.Checking && st !is Upd.Searching,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimen.sheetButtonHeight),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent)
                    ) {
                        Text(
                            if (st is Upd.Checking) "正在检查…" else "检查更新",
                            fontSize = Dimen.sheetButton,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            val (msg, color) = when (val s = st) {
                is Upd.Idle -> "" to Palette.inkSoft
                is Upd.Checking -> "正在问有没有新版本…" to Palette.inkSoft
                is Upd.Searching -> "正在局域网里找…" to Palette.inkSoft
                is Upd.Latest -> "已经是最新版本" to Palette.inkSoft
                is Upd.New -> "发现新版本 ${s.info.versionName}（${sizeText(s.info.size)}）" to Palette.accent
                is Upd.Downloading -> "正在传新版本… ${s.pct}%" to Palette.accent
                is Upd.Launched -> "已打开安装界面，点「安装」就好" to Palette.accent
                is Upd.Failed -> s.msg to Palette.danger
            }

            if (msg.isNotEmpty()) {
                Text(text = msg, fontSize = 16.sp, color = color, lineHeight = 24.sp)
            }

            if (st is Upd.Launched) {
                Spacer(Modifier.height(6.dp))
                Text("装完 app 会自己回来。工天数据不会动。", fontSize = 14.sp, color = Palette.inkSoft)
            }

            // ---------------------------------------------------------- 三、让别人从我这更新
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Palette.line, thickness = 1.dp)
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "让别的手机从我这更新",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Palette.ink
                    )
                    Text(
                        "对方连同一 WiFi，或者连你的热点",
                        fontSize = 14.sp,
                        color = Palette.inkSoft
                    )
                }
                Switch(
                    checked = serving,
                    onCheckedChange = { toggleServe(it) },
                    enabled = !starting
                )
            }

            if (serving && serveIps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.accentSoft, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text("在对方手机上填这个地址", fontSize = 14.sp, color = Palette.inkSoft)

                    // 必须分用法写清楚。实测开热点后这台手机有三个地址：
                    //   wlan0 192.168.1.10（连的上游路由器，PC 在同一网段时用这个）
                    //   wlan1 192.168.1.11（双 WiFi 加速，和 wlan0 是同一台路由器，不算热点）
                    //   wlan2 192.168.43.x（热点，对方连你热点时只能用这个）
                    // 网段不同的两个地址互不相通，给错了对方就一直转圈。
                    val primary = serveIps.firstOrNull()
                    val hotspotAddr = serveIps.firstOrNull { it.role == LanServer.Role.HOTSPOT }
                    val otherAddrs = serveIps.drop(1).filter { it.role == LanServer.Role.OTHER }
                    val multi = serveIps.size > 1

                    if (primary != null) {
                        if (multi) {
                            Text(
                                when (primary.role) {
                                    LanServer.Role.WIFI -> "同一个 WiFi 用这个"
                                    LanServer.Role.HOTSPOT -> "对方连你热点时用这个"
                                    LanServer.Role.OTHER -> "本机地址"
                                },
                                fontSize = 13.sp,
                                color = Palette.inkSoft
                            )
                        }
                        Text(
                            "${primary.ip}:${LanServer.PORT}",
                            fontSize = if (multi) 23.sp else 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Palette.accent
                        )
                    }
                    // 真正的热点地址（和上面不是同一个网段时才单列，否则会让人以为要二选一）
                    if (hotspotAddr != null && hotspotAddr.ip != primary?.ip) {
                        Text("对方连你热点时用这个", fontSize = 13.sp, color = Palette.inkSoft)
                        Text(
                            "${hotspotAddr.ip}:${LanServer.PORT}",
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            color = Palette.accent
                        )
                    }
                    // 双 WiFi 加速的第二条链路之类：能用但没必要强调
                    if (otherAddrs.isNotEmpty()) {
                        Text(
                            "连不上再试：" + otherAddrs.joinToString("  ") { "${it.ip}:${LanServer.PORT}" },
                            fontSize = 13.sp,
                            color = Palette.inkSoft
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    val bmp = qrBmp
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "地址二维码",
                            modifier = Modifier
                                .size(150.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "对方在「记工」里点「扫码填入」扫上面这个码，地址就自动填好了；也可以点「自动查找」。\n" +
                                "传的时候这一页要开着。",
                            fontSize = 14.sp,
                            color = Palette.inkSoft,
                            lineHeight = 20.sp
                        )
                    } else {
                        Text(
                            "或者对方点「自动查找」也能找到我。\n传的时候这一页要开着。",
                            fontSize = 14.sp,
                            color = Palette.inkSoft,
                            lineHeight = 20.sp
                        )
                    }
                    if (servedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "已经发出去 $servedCount 份",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Palette.accent
                        )
                    }
                }
            }

            if (starting) {
                Spacer(Modifier.height(10.dp))
                Text("正在准备安装包…", fontSize = 15.sp, color = Palette.inkSoft)
            }

            serveMsg?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 15.sp, color = Palette.danger, lineHeight = 22.sp)
            }

                Spacer(Modifier.height(26.dp))
            }
        }
    }
}

private fun sizeText(bytes: Long): String {
    if (bytes <= 0) return "大小未知"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) String.format(Locale.CHINA, "%.1f MB", mb) else "${bytes / 1024} KB"
}

@Suppress("DEPRECATION")
fun appVersionName(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
        .getOrNull() ?: "?"

fun appVersionCode(ctx: Context): Int = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
}.getOrDefault(0)

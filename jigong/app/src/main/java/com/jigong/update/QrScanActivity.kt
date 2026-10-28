package com.jigong.update

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫二维码填地址。CameraX 取景 + ML Kit 识别。
 *
 * 为什么不直接用某个库自带的扫码界面（之前用的 zxing-android-embedded）：
 * 它那个 CaptureActivity 在**自己 aar 的 Manifest 里锁死了 landscape**，
 * 我们只能靠一个空子类 + Manifest 覆盖来改竖屏 —— 属于跟库打架。
 * 而且它走的是 Camera1 老 API，对焦和旋转都要自己兜。
 *
 * 现在这条链路的每件事都有明确出处：
 *   · 取景/对焦/旋转  → CameraX（lifecycle 感知，切后台自动释放相机）
 *   · 从一帧里解条码  → ML Kit 捆绑版模型（不需要 Google Play 服务）
 *   · 扫到之后干什么  → 交给 QrCode.parse 判是不是地址，是就带结果回去
 *
 * 注意 [androidx.camera.core.ExperimentalGetImage]：ImageProxy.getImage() 被标成实验 API。
 * 但"把这一帧的 MediaImage 交给 ML Kit"是官方文档里唯一推荐的做法（另一条路是自己把
 * 像素拷进 ByteBuffer，多一次全帧复制），所以这里明确 opt-in，而不是绕开它。
 *
 * 只认二维码：把 Barcode.FORMAT_QR_CODE 写死。放开全部格式会让 ML Kit 多跑几个
 * 解码器，取景框里随便有条码（比如纸箱上的）就抢答。
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
class QrScanActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TEXT = "text"

        /** 调用方只需要这一行，别自己拼 Intent 忘了加 EXTRA */
        fun intent(ctx: Context): Intent = Intent(ctx, QrScanActivity::class.java)
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /** ML Kit 的识别器。第一次 process() 时才真正加载模型，所以用 lazy */
    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    /**
     * 一帧正在识别时不要再送下一帧。
     *
     * ImageAnalysis 用 STRATEGY_KEEP_ONLY_LATEST，如果这一帧还没识别完就来了新的，
     * 分析器还是会被再调一次；两帧同时交给 ML Kit 会互相干扰，而且 ImageProxy
     * 会漏关（相机就僵住，画面定格）。这个闸门是必须的。
     */
    private val busy = AtomicBoolean(false)

    /** 已经出结果并准备关页面了。后续帧一律丢掉，避免 finish() 之后再 setResult */
    @Volatile
    private var done = false

    private var hint by mutableStateOf("把对方屏幕上的二维码放进取景框")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 调用方（更新面板）已经请求过权限了。这里再查一次是兜底：
        // 万一被用户在系统设置里收回，直接关掉，别让用户对着黑屏等。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "没有相机权限，没法扫码。也可以直接手填地址", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                /*
                 * 取景框的位置不是随手放的。
                 *
                 * 主流扫码页（微信 / 支付宝 / 各种 ZXing 脚手架）都把框放在**纵向约 1/3 处**：
                 * 人端着手机时视线焦点偏上，框放在正中或偏下都得低头去对。
                 * 具体数值可参考 XCodeScanner 文档里的 constraintVerticalBias = 0.33，
                 * 以及 ZXing 实践里 framingRect 的 top = screenHeight * 0.3。
                 *
                 * 之前这个页面是用 Spacer(weight(1f)) 把整块内容往下推的，结果框掉到
                 * 屏幕下三分之一，正对着手指和肚子 —— 所以看着"太靠下"。
                 * 现在按屏幕高度的 33% 定位，换任何尺寸的手机都落在同一个相对位置。
                 *
                 * 尺寸取屏宽的 72%（上限 300dp）：太小对不准，太大反而识别变慢
                 * （画面里无关内容越多，检测越费），方框也正好是二维码的 1:1 形状。
                 */
                val frameSize = minOf(maxWidth * 0.72f, 300.dp)
                val frameTop = (maxHeight * 0.33f - frameSize / 2).coerceAtLeast(0.dp)

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            // COMPATIBLE 用的是 TextureView：在 Compose 里叠内容比 SurfaceView 稳，
                            // SurfaceView 有自己的窗口层，容易盖住上面画的取景框
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            // 等这一帧真正上屏之后再绑相机：提前 bind 拿不到 surfaceProvider
                            post { bindCamera(this) }
                        }
                    }
                )

                TextButton(
                    onClick = { finish() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        /*
                         * 必须避状态栏。
                         *
                         * targetSdk 35 起（Android 15+）系统**强制** edge-to-edge：
                         * 内容默认就画到状态栏底下，`android:statusBarColor` 已经废弃失效
                         * （官方《行为变更：以 Android 15 为目标平台》明写 "no effect"）。
                         * 原来只给了 20dp 的 top padding，在状态栏更高的机型上（挖孔屏、
                         * 三键导航、横屏时状态栏变高）「关闭」会压到时钟/电量图标上。
                         * official 提示里点的正是这类"访问频率低的界面"最容易被漏掉。
                         */
                        .statusBarsPadding()
                        .padding(top = 8.dp, end = 6.dp)
                ) {
                    Text("关闭", fontSize = 19.sp, color = Color.White)
                }

                // 取景框：纯提示用。ML Kit 认的是**整帧**，不是这个框里的内容，
                // 所以框画大画小不影响能不能识别，只影响用户把码放在哪。
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = frameTop)
                        .size(frameSize)
                        .border(3.dp, Color.White, RoundedCornerShape(18.dp))
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = frameTop + frameSize + 18.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        // 半透明黑底：相机里可能是白墙，也可能是大太阳下的水泥地，
                        // 白字直接压在画面上会看不清。加一层底比加粗字体靠谱。
                        .background(Color(0x99000000), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = hint,
                        textAlign = TextAlign.Center,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        lineHeight = 26.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // 说清楚"在对方手机上点哪儿能找到这个码"，否则用户会去扫别的东西
                        text = "对方在「记工」里打开「更新与分享」，打开「让别的手机从我这更新」，就有这个码",
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                        color = Color(0xFFDDDDDD),
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }

    private fun bindCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    // 只留最新一帧：识别跟不上取景帧率是正常的，积压只会让结果越来越旧
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    /*
                     * 分析分辨率必须显式指定。
                     *
                     * CameraX 官方文档《Configuration options》写明 ImageAnalysis 的
                     * **默认目标是 640x480** —— 对扫码来说偏小：屏幕上的二维码本身不大，
                     * 640 宽的一帧里码点只有几个像素，稍微远一点或手一抖就解不出来。
                     *
                     * 1280x720（720p）是社区反复验证过的甜点：ML Kit 在 720p 上的
                     * 识别准确率和大图差别很小，单帧耗时却从百毫秒级降到 20~50ms，
                     * 而 1080p 只会让延迟变大、更容易发热。
                     *
                     * 尺寸的写法按官方说明：要表达成「按目标旋转量旋转之后的坐标系」。
                     * 这个页面锁竖屏、手机自然方向也是竖屏，所以写成 720 宽 x 1280 高；
                     * 写成 1280x720 会被解析成一张横向的图。
                     *
                     * FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER：优先找不小于目标的最近一档；
                     * 找不到就退到下面的最近一档，不会因为机型没有 720p 就放弃。
                     */
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(720, 1280),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .build()
                    .apply { setAnalyzer(analysisExecutor, ::analyze) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }.onFailure { e ->
                hint = "相机打不开：${e.message ?: "未知原因"}"
                android.util.Log.w("jigong", "相机绑定失败：${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || done || !busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }

        // rotationDegrees 一定要传：竖着拿手机时这一帧在图里是横的，
        // 不告诉 ML Kit 它会把码当成歪的，十有八九认不出来
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)

        scanner.process(image)
            .addOnSuccessListener { codes -> onCodes(codes) }
            // 单帧失败是常态（糊了、太暗），界面上一声不响是对的；但要留一行日志 ——
            // 万一模型加载失败，就变成"永远扫不出来且没有任何线索"，那种问题最难查
            .addOnFailureListener { e ->
                android.util.Log.w("jigong", "单帧识别失败：${e.message}", e)
            }
            .addOnCompleteListener {
                // 必须关，而且要在识别**完成之后**关：提前关等于把图像从 ML Kit 手里抽走
                busy.set(false)
                proxy.close()
            }
    }

    private fun onCodes(codes: List<Barcode>) {
        if (done) return
        for (c in codes) {
            val raw = c.rawValue ?: continue
            val addr = QrCode.parse(raw)
            if (addr != null) {
                done = true
                setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, addr))
                finish()
                return
            }
            // 扫到了码但不是我们要的那种（比如付款码、公众号码）
            hint = "扫到了，但不是地址码。换对方屏幕上的那个"
        }
    }

    override fun onDestroy() {
        done = true
        runCatching { scanner.close() }
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}

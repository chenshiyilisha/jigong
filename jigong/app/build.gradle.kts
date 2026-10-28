import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * 签名信息不写进源码。
 *
 * 原来这里是 storePassword = "..." 直接硬编码 —— 源码一推到 GitHub，签名口令就等于公开了。
 * 后果不是"别人能改你的 app"，而是更具体的：拿到 keystore + 口令的人可以签一个**同一个签名**
 * 的 APK，然后利用这个 app 自带的「局域网更新」把包推到手机上，系统是认的（同签名允许覆盖安装）。
 *
 * 现在从 local.properties 读 —— 那个文件是 Gradle/Android Studio 的本地配置，
 * 按规定不进版本库（.gitignore 里已排除）。需要这几行：
 *
 *   jigong.storeFile=keystore/jigong.jks
 *   jigong.storePassword=……
 *   jigong.keyAlias=jigong
 *   jigong.keyPassword=……
 *
 * 没有这些配置时照样能编译（debug 包不受影响），只是出不了正式签名包 ——
 * 别人克隆这个仓库时不会被卡住。
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key.replace('.', '_').uppercase())?.takeIf { it.isNotBlank() }

android {
    namespace = "com.jigong"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jigong"
        // 个人使用，不需要适配低版本：Android 12 起（SQLite 3.32，java.time 原生可用）
        minSdk = 31
        targetSdk = 35
        // 每次发新版就 +1。局域网更新靠这个数字判断"谁更新"，
        // 它必须严格递增，否则手机不会认。
        versionCode = 14
        versionName = "1.13"
    }

    // 签名信息从 local.properties 读（见文件开头那段说明）。
    // ⚠️ app/keystore/jigong.jks 一定要留好：换了签名就无法覆盖安装，只能卸载重装（数据全丢）。
    //    它同样不进版本库，所以这个仓库里没有它 —— 换电脑要自己拷过去，或者重新生成。
    val ksFile = file(prop("jigong.storeFile") ?: "keystore/jigong.jks")
    val storePass = prop("jigong.storePassword")
    val keyPass = prop("jigong.keyPassword")
    // 三个条件都满足才挂签名配置。缺一个就出未签名 release 包，而不是让 gradle 直接报错 ——
    // 后者会让"只是想编译看看"的人卡在第一步。
    val canSign = ksFile.exists() && storePass != null && keyPass != null

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = ksFile
                storePassword = storePass
                keyAlias = prop("jigong.keyAlias") ?: "jigong"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            // 之前是 false，dex 里塞了 18MB —— 一个只有三个界面的 app 不可能真有这么多代码，
            // 全是没被摇掉的 Compose / Material3 死代码。官方《使用 R8 启用应用优化》明确
            // 「建议始终同时打开 isMinifyEnabled 和 isShrinkResources」。
            // 这个 app 靠 WiFi 传安装包、还要塞进微信，18MB → 个位数是实打实的收益。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (canSign) signingConfigs.getByName("release") else null
        }
    }

    /*
     * 只保留两种真机 ABI。
     *
     * ML Kit 捆绑版把模型（libbarhopper_v3.so）按 ABI 各塞一份：arm64 4.9MB、
     * v7a 3.2MB、x86/x86_64 各 6MB 左右。全带上光 .so 就 20MB，而这个 app
     * 只在真机上跑（没有模拟器需求），所以砍掉 x86 两条。
     * 留 v7a 是因为便宜的 32 位安卓机还有人用，而 app 是要发给别人的。
     */
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        // 只留中文和英文。依赖库里带了几十种语言的字符串（CameraX、Compose 都有），
        // 这个 app 的界面文字全是代码里写死的中文，那些资源一个都用不上。
        resourceConfigurations += listOf("zh", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // 扫码：ML Kit 条码识别（捆绑版）
    //
    // 换掉 zxing-android-embedded 的原因：那个库自带 CaptureActivity 用的是 Camera1 老 API，
    // 在国产 ROM 上对焦/旋转都得靠猜；ML Kit 走 CameraX + 官方模型，识别率明显更高。
    //
    // 为什么是「捆绑版」而不是带 play-services 的非捆绑版：
    //   非捆绑版只加 ~200KB，但模型要运行时从 Google Play 服务下载 —— 国产机没有 GMS，
    //   等于永远下载不下来，扫不出来。捆绑版把模型静态打进 APK（官方口径 +约 2.4MB/单 ABI），
    //   不需要 GMS，这才是能发给别人用的版本。
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // 相机。ML Kit 只负责"从一帧图像里解出条码"，取景/对焦/旋转要自己搭，
    // CameraX 是这个组合里唯一靠谱的选择（Camera2 自己写这几十行容易在国产 ROM 上翻车）。
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // 二维码「生成」还要用 zxing core（ML Kit 只能读、不能写）。
    // 只要 core 这一个纯 JVM 库，几百 KB，R8 之后更小。
    implementation("com.google.zxing:core:3.5.3")
}

// ---------------------------------------------------------------- 发布到 dist/
//
// 打包 + 把 APK 和 version.json 一起丢进 jigong/dist/，那个目录就是局域网更新服务器要发的目录。
// 用法：  gradle pubApk      （不要用 assembleRelease，那样不会写 version.json）
val pubApk by tasks.registering {
    dependsOn("assembleRelease")
    group = "发布"
    description = "打正式包并写入 dist/，供局域网更新服务器分发"

    val destDir = rootProject.layout.projectDirectory.dir("dist").asFile
    val apkSrc = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val vName = android.defaultConfig.versionName ?: "1"
    val vCode = android.defaultConfig.versionCode ?: 1

    doLast {
        destDir.mkdirs()
        destDir.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }

        val src = apkSrc.get().asFile
        require(src.exists()) { "找不到打包产物：$src" }

        val dst = File(destDir, "jigong-$vName.apk")
        src.copyTo(dst, overwrite = true)

        val digest = MessageDigest.getInstance("SHA-256")
        val sha = digest.digest(dst.readBytes()).joinToString("") { "%02x".format(it) }

        File(destDir, "version.json").writeText(
            """{"versionCode":$vCode,"versionName":"$vName","apk":"${dst.name}","sha256":"$sha","size":${dst.length()}}"""
        )

        println("发布完成  v$vName ($vCode)  ${dst.absolutePath}")
        println("下一步：python lan-update-server.py")
    }
}

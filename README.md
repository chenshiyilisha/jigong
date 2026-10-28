# 记工

记录工地出勤天数的安卓 App。日历上点日期记半天 / 一天，翻月查看，按日薪算账。

**[下载安装包](https://github.com/chenshiyilisha/jigong/releases/latest)** —— 12 MB，Android 12 及以上，装到手机上直接能用，不需要编译。

给自己和工友用，所以只有一个要求：**大字、点两下就能记完**。没有登录、没有云同步、没有加班统计。

## 现在能做什么

- 日历上点一天 → 记 1 天；再点可以改成半天、或删掉
- 底部一颗大按钮：今天还没记就「今天记一天」，记过了就显示「今天已记 1 天」
- 翻月看历史，本月 / 全年合计跟着变
- 算账：填日薪，算出所选的起止区间该拿多少
- 记错了能撤销（底部提示条上的「撤销」）
- 生成一张工天图，直接分享到微信

数据只写在本机的一个 SQLite 文件里。唯一需要联网的功能是「局域网更新」—— 从同一 WiFi 下的电脑拉安装包，不经过任何服务器。

## 文档

设计方案、每一处技术选择的理由、以及踩过的坑都在这里：

**[记工App-方案评估与落地修正.md](./记工App-方案评估与落地修正.md)**

这份文档不是事后补的说明，是边做边记的决策记录。举几个例子：

- 为什么 `minSdk` 定 31（不是"支持新版本"，是因为 SQLite 3.32 才有 `UPSERT`、`java.time` 才原生可用）
- 为什么扫码从 zxing-android-embedded 换成 CameraX + ML Kit **捆绑版**（国产机没有 GMS，非捆绑版下不来模型）
- 为什么 R8 开着，包从 18MB 降到 12MB
- 为什么首页锁竖屏、为什么整页可滚动
- 「翻到 9 月再点今天，弹层显示还没记」—— 这类只在特定路径下才出现的 bug 是怎么发现的

文中出现的局域网地址（`192.168.1.x`、`192.168.43.x`）都是示意，不是真实环境。

## 构建

需要 JDK 17 和 Android SDK（compileSdk 35）。

```bash
cd jigong
./gradlew assembleRelease
```

正式签名从 `local.properties` 读，这个文件**不在仓库里**（里面有口令）。没有它照样能编译，只是出的是未签名包：

```properties
jigong.storeFile=keystore/jigong.jks
jigong.storePassword=……
jigong.keyAlias=jigong
jigong.keyPassword=……
```

想自己出一版能装的包，生成一对签名即可：

```bash
keytool -genkeypair -v -keystore app/keystore/jigong.jks -alias jigong \
        -keyalg RSA -keysize 2048 -validity 10000
```

⚠️ 换签名就不能覆盖安装，只能卸载重装，而卸载会清掉数据库 —— 工天记录一起没。所以正式用的那对签名要留好。

## 目录

```
jigong/
  app/src/main/java/com/jigong/
    MainActivity.kt          入口
    data/WorkRepo.kt         单表 SQLite，一天最多一条记录
    ui/HomeScreen.kt         日历、记工弹层、算账弹层
    ui/UpdateSheet.kt        更新与分享面板
    ui/ShareImage.kt         把工天画成一张图
    update/                  局域网更新：UDP 发现、HTTP 服务、扫码、装包
  lan-update-server.py       电脑端更新服务器（只用 Python 标准库）
  启动更新服务器.bat          上面那个脚本的快捷启动
记工App-方案评估与落地修正.md  设计与决策记录
```

## 几个技术选择

| | |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose + Material3 |
| minSdk / targetSdk | 31（Android 12）/ 35 |
| 数据库 | SQLite，一张表，`work_date` UNIQUE |
| 扫码 | CameraX 1.4.1 + ML Kit `barcode-scanning`（bundled） |
| 体积 | release 12MB（R8 开），只留 arm64 + armeabi-v7a |

适老化按工信部《APP 适老化通用设计规范》做：主文字 ≥ 18sp、点击区 ≥ 44dp、对比度 ≥ 4.5:1，并在 200% 系统字号下逐个界面实测过不裁字。

## 许可

[Apache License 2.0](./LICENSE)，Copyright 2026 chenshiyilisha。

白话版：可以随便用、改、拿去卖，不用付费也不用告诉我；保留原版权声明和 [NOTICE](./NOTICE)、
改过的文件说明一句「已修改」就行。另外它不保证任何结果——这是个记工工具，不是精算软件，
算错一天工钱别找我。专利那块按 Apache-2.0 第 3 条办。

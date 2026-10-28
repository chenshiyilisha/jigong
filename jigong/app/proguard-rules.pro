# R8 规则 —— 目标是"能摇多少摇多少"，所以这里刻意写得很窄。
#
# 加规则之前先想清楚：每一条 -keep 都是让 R8 少删一块代码，
# 图省事写 -keep class com.xxx.** { *; } 等于把瘦身效果全废掉。
#
# 当前用到的三个库的情况：
#   · ML Kit  —— aar 自带 consumer 规则（proguard.txt），不用我们管，
#                里面已经处理了 proto 反射和 native 方法名
#   · CameraX —— 纯静态调用，R8 能看懂；如果真出问题，会在首次扫码时崩，很好定位
#   · zxing core —— 只用 QRCodeWriter/BarcodeFormat 两个类，全静态引用，
#                不需要 keep，让它被摇掉正好（生成的二维码只跟算法有关，跟类名无关）
#
# 注意：Manifest 里声明的 Activity/Receiver 由 AAPT 自动生成 keep 规则，不用手写。

# 混淆后崩溃栈仍然是「类名:行号」，能直接对着源码看。
# 代价只有几十 KB，自用 app 调试价值远大于这点体积。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin 的元数据（协程、内联函数、扩展属性在某些路径上要靠它）
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

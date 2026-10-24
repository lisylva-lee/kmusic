# ============================================================
# KMusic R8 / ProGuard 规则
#
# 之前 release 也是 isMinifyEnabled = false —— 66MB 未压缩 dex 全打进包里。
# 开启 R8 后体积和冷启动都会明显改善，但下面这些「靠反射/匿名类工作」的
# 库必须显式保留，否则会在 release 包上出现「debug 正常、release 崩溃」。
# ============================================================

# ---- kotlinx.serialization ----
# 序列化器是编译期生成的伴生对象，R8 看不到调用点就会连类一起删掉，
# 表现是 JSON 解析时抛 SerializationException("Serializer for class X is not found")
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.kmusic.apkhd.**$$serializer { *; }
-keepclassmembers class com.kmusic.apkhd.** {
    *** Companion;
}
-keepclasseswithmembers class com.kmusic.apkhd.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- OkHttp / Okio ----
# 这俩自带较多可选依赖（conscrypt、bouncycastle 等），缺类会被 R8 报错
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Media3 / ExoPlayer ----
-dontwarn androidx.media3.**

# ---- Coil ----
-dontwarn coil.**

# ---- 本项目数据模型 ----
# Track / SearchResult 等会被 JSON 反序列化，字段名不能混淆
-keepclassmembers class com.kmusic.apkhd.model.** {
    <fields>;
}

# ---- 下载/媒体服务由系统按类名拉起 ----
-keep class com.kmusic.apkhd.playback.MoyinPlaybackService { *; }
-keep class com.kmusic.apkhd.data.MoyinDownloadService { *; }

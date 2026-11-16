# 口袋物理仿真 · 混淆规则（release 开启 R8 时生效，v1 骨架默认未启用）

# ---------- Chaquopy 运行时（Java↔Python 桥靠反射与 JNI） ----------
-keep class com.chaquo.** { *; }
-dontwarn com.chaquo.**

# ---------- kotlinx.serialization ----------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# 保持序列化器伴生对象与 serializer() 访问
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class com.pocketsim.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pocketsim.app.**$$serializer { *; }

# ---------- OkHttp ----------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

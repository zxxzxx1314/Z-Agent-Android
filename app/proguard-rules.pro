# Z-Agent R8 混淆规则

# 目标：开启 minifyEnabled 后仍不崩溃（Chaquopy / Kotlin / 反射 / 枚举 / 序列化）
# ---------- 保留行号（崩溃日志可读），但隐藏源文件名 ----------

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- 基础元数据 ----------

-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# ---------- Chaquopy（Python 桥，必须全保留）----------

-keep class com.chaquo.python.** { *; }
-keep class cn.rain.zagent.PythonBridge { *; }
-keep class cn.rain.zagent.AndroidAPI { *; }
-dontwarn com.chaquo.python.**
-keepclassmembers class * {
@com.chaquo.python.PyObject *;
}

# ---------- Kotlin ----------

-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
<fields>;
}
-keepclassmembers class kotlin.coroutines.jvm.internal.** { *; }
-dontwarn kotlinx.coroutines.**

# ---------- Kotlin 协程 ----------

-keepnames class kotlinx.coroutines.internal.**MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
volatile <fields>;
}

# ---------- Retrofit / OkHttp（若引入则生效）----------

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# ---------- Gson ----------

-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
@com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers enum * {
public static **[] values();
public static ** valueOf(java.lang.String);
}

# ---------- Parcelable ----------

-keep class * implements android.os.Parcelable {
public static final android.os.Parcelable$Creator *;
}

# ---------- 枚举 / 序列化 ----------

-keepclassmembers class * extends java.lang.Enum {
public static **[] values();
public static ** valueOf(java.lang.String);
}

# ---------- WebView JS 桥 ----------

-keepclassmembers class * {
@android.webkit.JavascriptInterface <methods>;
}

# ---------- 保留应用入口与 Activity ----------

-keep class cn.rain.zagent.MainActivity { *; }
-keep class cn.rain.zagent.PluginActivity { *; }
-keep class cn.rain.zagent.SignatureCheck { *; }

# ---------- AndroidX / Material ----------

-keep class androidx.appcompat.** { *; }
-keep class com.google.android.material.* { *; }
-dontwarn androidx.**

# ---------- 杂项 ----------

-dontwarn org.slf4j.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# ---------- Android 内置 org.json ----------
-keep class org.json.** { *; }
# ---------- 日志分级（task 20）：Release 裁剪 ----------
# 只允许裁掉 d/i；w/e 任何构建都保留（错误必须上报）。
# lambda 版本要求 lambda 内不得有副作用——副作用在 Release 里也会一并消失。
-assumenosideeffects class cn.rain.zagent.Log {
    public static void d(java.lang.String);
    public static void d(kotlin.jvm.functions.Function0);
    public static void i(java.lang.String);
    public static void i(kotlin.jvm.functions.Function0);
}

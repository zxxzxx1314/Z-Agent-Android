# Chaquopy：保留 Kotlin/Java 混淆时的 Python 桥接类
-keep class com.chaquo.python.** { *; }
-keep class com.example.zagent.PythonBridge { *; }
-keep class com.example.zagent.AndroidAPI { *; }
-dontwarn org.slf4j.**

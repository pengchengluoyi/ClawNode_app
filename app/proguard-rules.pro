# Gson 通过反射解析模型类，保留模型字段名
-keepclassmembers,allowobfuscation class com.clawnode.agent.model.** {
    <fields>;
}
-keep class com.clawnode.agent.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

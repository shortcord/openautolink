# ProGuard rules for OpenAutoLink
# Keep kotlinx.serialization
# Covers all @Serializable classes including ControlMessage, etc.
# @SerialName values are baked into generated serializer code — no reflection needed.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.openautolink.app.**$$serializer { *; }
-keepclassmembers class com.openautolink.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.openautolink.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Protobuf-lite — keep generated message classes from being stripped
-keep class com.openautolink.app.proto.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Conscrypt SSL provider — suppress warnings for optional platform classes
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

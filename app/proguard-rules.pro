# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.eloa.launcher.**$$serializer { *; }
-keepclassmembers class dev.eloa.launcher.** { *** Companion; }
-keepclasseswithmembers class dev.eloa.launcher.** { kotlinx.serialization.KSerializer serializer(...); }
# OkHttp platform warnings
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# apksig optional dependencies
-dontwarn com.android.apksig.internal.**
-dontwarn javax.annotation.**

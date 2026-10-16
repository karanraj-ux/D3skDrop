# DeskDrop R8 / ProGuard Configuration

# Preserve line numbers and source files for readable stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- Netty & Ktor ---
# Netty uses reflection, unsafe access, and dynamic class loading for channels and event loops
-dontwarn io.netty.**
-keep class io.netty.** { *; }
-keep interface io.netty.** { *; }
-dontwarn sun.misc.**
-dontwarn java.lang.invoke.**

# Ktor Server & Plugins
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }

# --- Kotlinx Coroutines & Serialization ---
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- Media3 / ExoPlayer ---
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# --- ZXing QR Code ---
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }

# --- Application Models ---
-keep class com.example.ReceivedFile { *; }
-keepclassmembers class com.example.** { *; }

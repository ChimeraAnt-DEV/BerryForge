# BerryForge ProGuard rules.
#
# The app leans on reflection in three places that R8 cannot see through: kotlinx
# serialization, Sora Editor's TextMate grammar loading, and Termux's JNI bridge.
# Without these keeps the release build compiles but fails at runtime.

# ---- kotlinx.serialization ----
# Serializers are generated at compile time but looked up reflectively by the
# plugin's companion objects.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.chimeraant.berryforge.**$$serializer { *; }
-keepclassmembers class dev.chimeraant.berryforge.** {
    *** Companion;
}
-keepclasseswithmembers class dev.chimeraant.berryforge.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Model classes deserialized from GitHub and from disk.
-keep class dev.chimeraant.berryforge.data.github.Gh* { *; }
-keep class dev.chimeraant.berryforge.session.AgentSession { *; }
-keep class dev.chimeraant.berryforge.session.AgentEvent { *; }
-keep class dev.chimeraant.berryforge.session.FileChange { *; }

# ---- Termux terminal-emulator (JNI) ----
# libtermux.so resolves these by name, so renaming them breaks the PTY.
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
-keepclasseswithmembernames class com.termux.** {
    native <methods>;
}

# ---- Sora Editor / TextMate ----
# Grammar and theme classes are instantiated reflectively by the registry.
-keep class io.github.rosemoe.sora.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-dontwarn io.github.rosemoe.sora.**
-dontwarn org.eclipse.tm4e.**
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-dontwarn org.joni.**
-dontwarn org.jcodings.**

# ---- Archive extraction ----
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**

# ---- AndroidX Security (Tink) ----
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep line numbers so build-log and crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

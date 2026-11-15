# ULTRA IPTV – R8-Regeln
# R8 optimiert vor allem die Bibliotheken (Compose, Media3, Coroutines). Der eigene App-Code wird
# vollständig behalten und nicht umbenannt – keine Laufzeitüberraschungen durch Reflection.
-keep class de.ostlertekker.ultraiptv.** { *; }
-keepnames class de.ostlertekker.ultraiptv.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, SourceFile, LineNumberTable, Exceptions

# Retrofit (Streaming-Interface mit suspend-Funktionen)
-keep,allowobfuscation interface de.ostlertekker.ultraiptv.di.XtreamApi
-keep,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx.serialization (JSON-Baum)
-keep class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**

# Tink / Security-Crypto, Guava-Annotationen
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# Media3
-dontwarn androidx.media3.**

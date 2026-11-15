# Release wird derzeit ohne R8-Minify gebaut (Stabilität beim Sideloading).
# Regeln für einen späteren Minify-Build:
-keepattributes Signature, *Annotation*
-keep class de.ostlertekker.ultraiptv.core.database.** { *; }

# ---- Chaquopy (Python runtime for Android) ----
# Chaquopy uses JNI and reflection to bridge Java/Kotlin ↔ Python.
-keep class com.chaquo.** { *; }
-keepclassmembers class com.chaquo.** { *; }

# ---- Kotlin metadata & coroutines ----
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# ---- App data models (JSON serialization via org.json) ----
-keep class com.carya.jm.data.model.** { *; }

# ---- Download service & manager (foreground service, JSON metadata) ----
-keep class com.carya.jm.data.download.** { *; }

# ---- Image descrambler (used by reader & download service) ----
-keep class com.carya.jm.ui.reader.ImageDescrambler { *; }

# ---- App entry points ----
-keep class com.carya.jm.data.python.PythonService { *; }

# ---- Coil (image loading) ----
-keep class coil.** { *; }

# ---- Navigation Compose (uses reflection for route serialization) ----
-keep class androidx.navigation.** { *; }

# ---- Keep enum values (used in when() expressions) ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Preserve source file names & line numbers for crash reports ----
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

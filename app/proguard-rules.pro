# Native entry points and callback classes used by the locally supplied FFmpegKit.
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
# AndroidJUnitRunner loads Kotlin-based test components inside the release target process.
-keep class kotlin.** { *; }
-keep class androidx.tracing.** { *; }
-keep class com.streamcap.mobile.app.StreamCapApplication { *; }
-keep class com.streamcap.mobile.app.AppContainer { *; }
-keep class com.streamcap.mobile.platform.** { *; }
-keep class com.streamcap.mobile.platform.resolver.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class com.streamcap.mobile.domain.model.** { *; }
-keep class com.streamcap.mobile.data.repository.StreamCapRepository { *; }
-keep class com.streamcap.mobile.data.database.RecordingSessionEntity { *; }
-keep class com.streamcap.mobile.recording.hls.HlsSourceSelector { *; }
-keep class com.streamcap.mobile.service.RecordingService { *; }
-keep class com.streamcap.mobile.service.RecordingService$Companion { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

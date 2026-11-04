# Stable local history schema (Gson 2.8.9 uses reflective field names and generic signatures).
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class app.attune.attunenative.history.*HistoryRecord { <fields>; }
-keep class app.attune.attunenative.history.NativeHistoryStore$HistoryEnvelope { <fields>; }
-keep class app.attune.attunenative.history.NotificationRecordJsonAdapter { *; }
# Encrypted Focus state/model/timetable schema is serialized locally with Gson.
-keep class app.attune.attunenative.focus.** { <fields>; }

# Protobuf Lite reflects over generated fields (including MediaPipe SystemInfo.platform_).
# https://github.com/protocolbuffers/protobuf/blob/v26.1/java/lite.md
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# MediaPipe 0.10.26 AARs omit these upstream JNI/callback consumer rules.
# https://github.com/google-ai-edge/mediapipe/blob/v0.10.26/mediapipe/java/com/google/mediapipe/framework/proguard.pgcfg
-keep public interface com.google.mediapipe.framework.* { public *; }
-keep public class com.google.mediapipe.framework.Packet {
  public static *** create(***);
  public long getNativeHandle();
  public void release();
}
-keep public class com.google.mediapipe.framework.PacketCreator {
  *** releaseWithSyncToken(...);
}
-keep public class com.google.mediapipe.framework.MediaPipeException {
  <init>(int, byte[]);
}
-keep class com.google.mediapipe.framework.ProtoUtil$SerializedMessage { *; }

# MediaPipe's Flogger 0.6 backend discovers logger callers from the Java stack and
# loads its platform reflectively. R8 inlining breaks Graph's static initializer.
-keep class com.google.common.flogger.** { *; }

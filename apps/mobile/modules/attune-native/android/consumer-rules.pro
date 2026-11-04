# Stable local history schema (Gson 2.8.9 uses reflective field names and generic signatures).
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class app.attune.attunenative.history.*HistoryRecord { <fields>; }
-keep class app.attune.attunenative.history.NativeHistoryStore$HistoryEnvelope { <fields>; }
-keep class app.attune.attunenative.history.NotificationRecordJsonAdapter { *; }
# Encrypted Focus state/model/timetable schema is serialized locally with Gson.
-keep class app.attune.attunenative.focus.** { <fields>; }

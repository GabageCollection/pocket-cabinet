# 领域模型与实体被 Room/JSON 反射使用
-keep class com.ambercabinet.core.model.** { *; }
-keep class com.ambercabinet.core.data.db.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }

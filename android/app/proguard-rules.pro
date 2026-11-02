# org.json and the platform are kept by the default rules; zxing-android-embedded ships consumer rules.
# Keep line numbers for readable crash traces in diagnostics.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager uses Room internally and instantiates WorkDatabase_Impl reflectively
# (Room's generated *_Impl classes need their no-arg constructor). R8 stripped it -> startup crash.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class androidx.work.impl.** { *; }
-keep class androidx.startup.InitializationProvider { *; }
-keep class * extends androidx.startup.Initializer { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

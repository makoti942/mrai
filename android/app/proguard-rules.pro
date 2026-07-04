# VoiceAgent ProGuard Rules

# Keep the MainActivity and all inner classes (Service, BootReceiver)
-keep class com.voiceagent.app.MainActivity { *; }
-keep class com.voiceagent.app.MainActivity$VoiceCommandService { *; }
-keep class com.voiceagent.app.MainActivity$BootReceiver { *; }
-keep class com.voiceagent.app.NotificationListener { *; }

# Keep JavaScriptInterface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Serializable/Parcelable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep annotations
-keepattributes *Annotation*, JavascriptInterface

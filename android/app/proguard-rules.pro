# ============================================================
# ProGuard / R8 правила для Messenger
# ============================================================

# Сохраняем имена классов для стек-трейсов (через mapping.txt)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Retrofit / Gson (модели API) ─────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }

# Gson — не обфусцировать data-классы моделей
-keep class com.messenger.app.models.** { *; }
-keepclassmembers class com.messenger.app.models.** { *; }

# ── OkHttp ───────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ── WebRTC ───────────────────────────────────────────────────
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ── Glide ────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# ── Android Keystore / Crypto ─────────────────────────────────
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class com.messenger.app.crypto.** { *; }
-keep class com.messenger.app.security.** { *; }

# ── EncryptedSharedPreferences ────────────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Kotlin coroutines ─────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── Скрываем отладочную информацию ───────────────────────────
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# ── Удаляем assert() в релизе ────────────────────────────────
-assumenosideeffects class java.lang.Thread {
    public static void sleep(long);
}

# Не обфусцировать Enum
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Сериализуемые классы
-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

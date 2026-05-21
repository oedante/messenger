package com.messenger.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.io.File
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Проверки безопасности среды выполнения.
 */
object SecurityManager {

    private const val TAG = "SecurityManager"

    // ── Root Detection ────────────────────────────────────────────────────────
    /**
     * Проверяет наличие root-доступа на устройстве.
     * Не является 100% надёжным, но отсеивает большинство случаев.
     */
    fun isDeviceRooted(): Boolean {
        return checkSuBinary() || checkRootPackages() || checkBuildTags()
    }

    private fun checkSuBinary(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
            "/su/bin/su", "/magisk/.core/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkRootPackages(): Boolean {
        val packages = arrayOf(
            "com.noshufou.android.su", "com.thirdparty.superuser",
            "eu.chainfire.supersu", "com.koushikdutta.superuser",
            "com.zachspong.temprootremovejb", "com.ramdroid.appquarantine",
            "com.topjohnwu.magisk"
        )
        return packages.any { pkg ->
            try {
                val pm = android.app.Application().packageManager
                pm?.getPackageInfo(pkg, 0) != null
            } catch (_: PackageManager.NameNotFoundException) { false }
        }
    }

    private fun checkBuildTags(): Boolean {
        val tags = Build.TAGS
        return tags != null && tags.contains("test-keys")
    }

    // ── Emulator Detection ────────────────────────────────────────────────────
    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                || "google_sdk" == Build.PRODUCT)
    }

    // ── OkHttp с Certificate Pinning ─────────────────────────────────────────
    /**
     * Создаёт OkHttpClient с certificate pinning для продакшн-домена.
     *
     * Как получить pin:
     *   openssl s_client -connect yourdomain.com:443 | openssl x509 -pubkey -noout |
     *   openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
     *
     * ВАЖНО: Обновите PINNED_DOMAIN и CERTIFICATE_PINS перед деплоем!
     */
    fun buildSecureOkHttpClient(
        domain: String,
        pins: List<String>,
        token: () -> String?
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().apply {
                    token()?.let { addHeader("Authorization", "Bearer $it") }
                    // Дополнительные security headers
                    addHeader("X-Client-Version", "3.0.0")
                }.build())
            }
            // Таймауты
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30,    java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30,   java.util.concurrent.TimeUnit.SECONDS)

        // Certificate pinning (только если домен и пины указаны)
        if (domain.isNotBlank() && pins.isNotEmpty()) {
            val pinner = CertificatePinner.Builder().apply {
                pins.forEach { pin -> add(domain, "sha256/$pin") }
            }.build()
            builder.certificatePinner(pinner)
            Log.i(TAG, "Certificate pinning enabled for $domain")
        } else {
            Log.w(TAG, "Certificate pinning DISABLED — configure for production!")
        }

        return builder.build()
    }

    /**
     * OkHttpClient для DEBUG-сборок (без pinning).
     */
    fun buildDebugOkHttpClient(token: () -> String?): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().apply {
                    token()?.let { addHeader("Authorization", "Bearer $it") }
                }.build())
            }
            .addInterceptor(okhttp3.logging.HttpLoggingInterceptor().apply {
                level = okhttp3.logging.HttpLoggingInterceptor.Level.BODY
            })
            .build()

    // ── Проверка целостности приложения ───────────────────────────────────────
    /**
     * Проверяет подпись APK. Меняйте EXPECTED_SIGNATURE на свою.
     */
    fun isAppSignatureValid(context: Context): Boolean {
        return try {
            // В продакшне сравните с реальным хешем вашей подписи
            val EXPECTED_SIGNATURE = "YOUR_APP_SIGNATURE_SHA256_HERE"
            if (EXPECTED_SIGNATURE == "YOUR_APP_SIGNATURE_SHA256_HERE") {
                // Не настроено — пропускаем проверку
                return true
            }
            val pm = context.packageManager
            val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo.apkContentsSigners.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures.firstOrNull()
            }
            val sigHash = sig?.let {
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(it.toByteArray())
                    .joinToString("") { b -> "%02x".format(b) }
            }
            sigHash == EXPECTED_SIGNATURE
        } catch (e: Exception) {
            Log.e(TAG, "Signature check failed: ${e.message}")
            false
        }
    }

    // ── Отчёт о безопасности ─────────────────────────────────────────────────
    data class SecurityReport(
        val isRooted: Boolean,
        val isEmulator: Boolean,
        val isSignatureValid: Boolean
    ) {
        val isSafe get() = !isRooted && isSignatureValid
    }

    fun getSecurityReport(context: Context) = SecurityReport(
        isRooted         = isDeviceRooted(),
        isEmulator       = isEmulator(),
        isSignatureValid = isAppSignatureValid(context)
    )
}

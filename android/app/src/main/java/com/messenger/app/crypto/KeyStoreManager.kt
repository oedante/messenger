package com.messenger.app.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyPair

/**
 * Безопасное хранение приватного ключа E2E на Android.
 *
 * Приватный ключ шифруется через Android Keystore (TEE/SE)
 * и сохраняется в EncryptedSharedPreferences.
 * Публичный ключ доступен в открытом виде.
 *
 * ВАЖНО: При смене устройства/очистке данных пользователь
 * не сможет прочитать старые сообщения — это нормально для E2E.
 */
class KeyStoreManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .setUserAuthenticationRequired(false)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "e2e_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_PRIVATE = "identity_private_key"
        private const val KEY_PUBLIC  = "identity_public_key"
        private const val KEY_ALGO    = "identity_key_algorithm"
    }

    /**
     * Генерирует и сохраняет новую идентификационную пару ключей.
     * Возвращает публичный ключ в base64 для загрузки на сервер.
     */
    fun generateAndSaveIdentityKey(): String {
        val keyPair    = E2EManager.generateIdentityKeyPair()
        val privateB64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
        val publicB64  = Base64.encodeToString(keyPair.public.encoded,  Base64.NO_WRAP)
        val algo       = keyPair.private.algorithm

        prefs.edit()
            .putString(KEY_PRIVATE, privateB64)
            .putString(KEY_PUBLIC,  publicB64)
            .putString(KEY_ALGO,    algo)
            .apply()

        return publicB64
    }

    /**
     * Возвращает сохранённую пару ключей или null если не существует.
     */
    fun getIdentityKeyPair(): KeyPair? {
        val privB64 = prefs.getString(KEY_PRIVATE, null) ?: return null
        val pubB64  = prefs.getString(KEY_PUBLIC,  null) ?: return null
        val algo    = prefs.getString(KEY_ALGO,    "EC") ?: "EC"

        return try {
            val privBytes = Base64.decode(privB64, Base64.NO_WRAP)
            val pubBytes  = Base64.decode(pubB64,  Base64.NO_WRAP)

            val kf = java.security.KeyFactory.getInstance(algo)
            val privateKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privBytes))
            val publicKey  = kf.generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))

            KeyPair(publicKey, privateKey)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Возвращает публичный ключ в base64 (для регистрации/обновления).
     */
    fun getPublicKeyBase64(): String? = prefs.getString(KEY_PUBLIC, null)

    /**
     * Существует ли уже ключевая пара.
     */
    fun hasIdentityKey(): Boolean = prefs.getString(KEY_PRIVATE, null) != null

    /**
     * Удаляет ключи (например при выходе из аккаунта).
     * После удаления старые зашифрованные сообщения не расшифруются.
     */
    fun deleteIdentityKey() {
        prefs.edit().remove(KEY_PRIVATE).remove(KEY_PUBLIC).remove(KEY_ALGO).apply()
    }
}

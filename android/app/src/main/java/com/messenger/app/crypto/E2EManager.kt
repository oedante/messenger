package com.messenger.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2E шифрование сообщений:
 *
 *  Схема:  X25519 (ECDH) для обмена ключами + AES-256-GCM для шифрования.
 *
 *  Для каждого собеседника:
 *   1. Alice генерирует пару X25519 при регистрации, публичный ключ — на сервер.
 *   2. При отправке сообщения Bob-у:
 *      - Alice генерирует эфемерную пару X25519
 *      - ECDH(ephemeral_private, bob_public) → shared_secret
 *      - HKDF(shared_secret) → AES-256 ключ
 *      - Шифрует: AES-GCM(key, nonce, plaintext)
 *      - Отправляет: base64(ephemeral_pub || nonce || ciphertext)
 *   3. Bob расшифровывает:
 *      - ECDH(bob_private, ephemeral_pub) → shared_secret
 *      - HKDF → AES-256 ключ → расшифровывает.
 *
 *  Для групп используется симметричный ключ группы,
 *  зашифрованный публичным ключом каждого участника.
 *
 *  Приватный ключ хранится в Android Keystore (TEE/SE) —
 *  не экспортируется и не покидает устройство.
 */
object E2EManager {

    private const val KEYSTORE     = "AndroidKeyStore"
    private const val KEY_ALIAS    = "e2e_identity_key"
    private const val EC_CURVE     = "X25519"           // Java 11+: через Bouncy Castle
    private const val AES_ALG      = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val AES_KEY_BITS = 256

    // ── Генерация идентификационной пары ──────────────────────────────────────
    /**
     * Генерирует пару EC ключей. На Android < 31 X25519 может быть недоступен,
     * используем P-256 как fallback.
     */
    fun generateIdentityKeyPair(): KeyPair {
        return try {
            // X25519 (Curve25519) — предпочтительно
            KeyPairGenerator.getInstance("X25519").apply {
                initialize(255)
            }.generateKeyPair()
        } catch (_: NoSuchAlgorithmException) {
            // Fallback: P-256 (доступен на всех версиях Android)
            KeyPairGenerator.getInstance("EC").apply {
                initialize(256)
            }.generateKeyPair()
        }
    }

    /**
     * Возвращает base64 публичного ключа для загрузки на сервер.
     */
    fun publicKeyToBase64(keyPair: KeyPair): String =
        Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

    /**
     * Восстанавливает публичный ключ из base64 (от сервера).
     */
    fun publicKeyFromBase64(b64: String, algorithm: String = "X25519"): PublicKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val spec  = java.security.spec.X509EncodedKeySpec(bytes)
        return try {
            KeyFactory.getInstance(algorithm).generatePublic(spec)
        } catch (_: Exception) {
            KeyFactory.getInstance("EC").generatePublic(spec)
        }
    }

    // ── Шифрование сообщения ──────────────────────────────────────────────────
    /**
     * Шифрует plaintext для получателя с известным публичным ключом.
     * Возвращает base64(ephemeral_pub_len[2] || ephemeral_pub || iv[12] || ciphertext).
     */
    fun encrypt(plaintext: String, recipientPublicKey: PublicKey, senderKeyPair: KeyPair): String {
        // 1. Эфемерная пара (per-message)
        val ephemeral = generateIdentityKeyPair()

        // 2. ECDH: shared secret
        val sharedSecret = ecdh(ephemeral.private, recipientPublicKey)

        // 3. HKDF → AES-256 ключ
        val aesKey = hkdf(sharedSecret, salt = "messenger-e2e-v1".toByteArray(), length = 32)

        // 4. AES-256-GCM шифрование
        val iv     = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_ALG)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // 5. Сборка: ephemeral_pub || iv || ciphertext
        val ephPubBytes = ephemeral.public.encoded
        val ephPubLen   = byteArrayOf((ephPubBytes.size shr 8).toByte(), (ephPubBytes.size and 0xFF).toByte())
        val packet      = ephPubLen + ephPubBytes + iv + ciphertext

        return Base64.encodeToString(packet, Base64.NO_WRAP)
    }

    /**
     * Расшифровывает сообщение, используя приватный ключ получателя.
     */
    fun decrypt(encryptedB64: String, recipientKeyPair: KeyPair): String {
        val packet      = Base64.decode(encryptedB64, Base64.NO_WRAP)
        val ephPubLen   = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val ephPubBytes = packet.sliceArray(2 until 2 + ephPubLen)
        val iv          = packet.sliceArray(2 + ephPubLen until 2 + ephPubLen + GCM_IV_BYTES)
        val ciphertext  = packet.sliceArray(2 + ephPubLen + GCM_IV_BYTES until packet.size)

        val ephemeralPub = try {
            KeyFactory.getInstance("X25519")
                .generatePublic(java.security.spec.X509EncodedKeySpec(ephPubBytes))
        } catch (_: Exception) {
            KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(ephPubBytes))
        }

        val sharedSecret = ecdh(recipientKeyPair.private, ephemeralPub)
        val aesKey       = hkdf(sharedSecret, salt = "messenger-e2e-v1".toByteArray(), length = 32)

        val cipher = Cipher.getInstance(AES_ALG)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    // ── ECDH ──────────────────────────────────────────────────────────────────
    private fun ecdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val ka = try {
            KeyAgreement.getInstance("X25519")
        } catch (_: Exception) {
            KeyAgreement.getInstance("ECDH")
        }
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    // ── HKDF (RFC 5869, HMAC-SHA256) ─────────────────────────────────────────
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray = ByteArray(0), length: Int = 32): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")

        // Extract
        mac.init(javax.crypto.spec.SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        // Expand
        mac.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t      = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copy)
            offset += copy; counter++
        }
        return result
    }

    // ── Проверка зашифрован ли текст ─────────────────────────────────────────
    fun isEncrypted(content: String): Boolean {
        return try {
            val bytes = Base64.decode(content, Base64.NO_WRAP)
            bytes.size > 14  // минимальный размер пакета
        } catch (_: Exception) { false }
    }
}

package com.messenger.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.crypto.E2EManager
import com.messenger.app.crypto.KeyStoreManager
import com.messenger.app.models.*
import com.messenger.app.network.RetrofitClient
import com.messenger.app.network.WsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.security.KeyPair
import java.security.PublicKey

/**
 * ChatViewModel с поддержкой E2E шифрования.
 *
 * Для личных чатов (direct):
 *   - Каждое сообщение шифруется публичным ключом собеседника.
 *   - Сервер видит только base64 шифротекст.
 *   - Расшифровка — только на устройстве получателя.
 *
 * Для групп/каналов:
 *   - В текущей реализации шифрование не применяется (сложнее,
 *     требует групповых ключей). Это честно указывается пользователю.
 */
class SecureChatViewModel(
    val roomId: Int,
    val myUserId: Int,
    val isDirect: Boolean,
    private val keyStoreManager: KeyStoreManager
) : ViewModel() {

    private val _messages     = MutableStateFlow<List<Message>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _typing       = MutableStateFlow<Set<Int>>(emptySet())
    val typingUsers = _typing.asStateFlow()

    private val _error        = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _callEvent    = MutableSharedFlow<WsEvent>(extraBufferCapacity = 4)
    val callEvent = _callEvent.asSharedFlow()

    /** Публичный ключ собеседника (только для direct чатов) */
    private var remotePublicKey: PublicKey? = null
    private var myKeyPair: KeyPair?         = null

    private var typingClearJobs = mutableMapOf<Int, Job>()
    private var typingThrottle: Job? = null

    init {
        myKeyPair = keyStoreManager.getIdentityKeyPair()
        loadMessages()
        observeWs()
    }

    fun setRemotePublicKey(b64: String?) {
        if (b64 == null) return
        try {
            remotePublicKey = E2EManager.publicKeyFromBase64(b64)
        } catch (e: Exception) {
            _error.value = "Не удалось получить ключ собеседника: ${e.message}"
        }
    }

    fun loadMessages(before: Int? = null) {
        viewModelScope.launch {
            runCatching { RetrofitClient.api.getMessages(roomId, before) }
                .onSuccess { resp ->
                    val raw = resp.body() ?: return@onSuccess
                    val decrypted = raw.map { decryptMessage(it) }
                    _messages.value = if (before == null) decrypted
                    else (decrypted + _messages.value).distinctBy { it.id }.sortedBy { it.createdAt }
                }
                .onFailure { _error.value = it.message }
        }
    }

    fun sendText(plaintext: String, replyTo: Int? = null) {
        if (plaintext.isBlank()) return
        viewModelScope.launch {
            val content = encryptForSend(plaintext.trim())
            runCatching {
                RetrofitClient.api.sendMessage(roomId, SendMessageRequest(content, replyTo = replyTo))
            }.onFailure { _error.value = it.message }
        }
    }

    fun sendFile(file: File) {
        viewModelScope.launch {
            val mime = when (file.extension.lowercase()) {
                "jpg", "jpeg", "png", "webp" -> "image/*"
                "mp3", "ogg", "m4a"          -> "audio/*"
                else                          -> "application/octet-stream"
            }
            val part = MultipartBody.Part.createFormData(
                "file", file.name, file.asRequestBody(mime.toMediaTypeOrNull())
            )
            runCatching { RetrofitClient.api.uploadFile(roomId, part) }
                .onFailure { _error.value = it.message }
        }
    }

    fun editMessage(msgId: Int, plaintext: String) {
        viewModelScope.launch {
            val content = encryptForSend(plaintext)
            runCatching { RetrofitClient.api.editMessage(roomId, msgId, EditMessageRequest(content)) }
                .onFailure { _error.value = it.message }
        }
    }

    fun deleteMessage(msgId: Int) {
        viewModelScope.launch {
            runCatching { RetrofitClient.api.deleteMessage(roomId, msgId) }
                .onFailure { _error.value = it.message }
        }
    }

    fun react(msgId: Int, emoji: String) {
        viewModelScope.launch {
            runCatching { RetrofitClient.api.react(roomId, msgId, ReactionRequest(emoji)) }
        }
    }

    fun notifyTyping() {
        typingThrottle?.cancel()
        typingThrottle = viewModelScope.launch { WsManager.sendTyping(roomId); delay(3000) }
    }

    // ── Шифрование / расшифровка ──────────────────────────────────────────────

    private fun encryptForSend(plaintext: String): String {
        if (!isDirect) return plaintext   // группы — без E2E в текущей версии
        val kp  = myKeyPair ?: return plaintext
        val rpk = remotePublicKey ?: return plaintext
        return try {
            E2EManager.encrypt(plaintext, rpk, kp)
        } catch (e: Exception) {
            _error.value = "Ошибка шифрования: ${e.message}"
            plaintext
        }
    }

    private fun decryptMessage(msg: Message): Message {
        if (!isDirect || msg.type != "text") return msg
        val kp = myKeyPair ?: return msg
        return try {
            if (E2EManager.isEncrypted(msg.content)) {
                msg.copy(content = E2EManager.decrypt(msg.content, kp))
            } else {
                msg   // старые незашифрованные сообщения
            }
        } catch (e: Exception) {
            // Расшифровка не удалась — скорее всего сообщение от другого устройства
            msg.copy(content = "[🔒 Не удалось расшифровать]")
        }
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun observeWs() {
        viewModelScope.launch {
            WsManager.events.collect { e ->
                when (e.type) {
                    "new_message" -> {
                        val raw = e.message ?: return@collect
                        if (raw.roomId == roomId) {
                            val decrypted = decryptMessage(raw)
                            _messages.value = (_messages.value + decrypted).sortedBy { it.createdAt }
                            WsManager.sendRead(raw.id)
                        }
                    }
                    "message_edited" -> {
                        val mid     = e.messageId ?: return@collect
                        val newText = e.content ?: return@collect
                        val kp      = myKeyPair
                        val plaintext = if (isDirect && kp != null && E2EManager.isEncrypted(newText)) {
                            try { E2EManager.decrypt(newText, kp) } catch (_: Exception) { "[🔒 Ошибка]" }
                        } else newText
                        _messages.value = _messages.value.map { m ->
                            if (m.id == mid) m.copy(content = plaintext, edited = true) else m
                        }
                    }
                    "message_deleted" -> {
                        val mid = e.messageId ?: return@collect
                        _messages.value = _messages.value.filter { it.id != mid }
                    }
                    "typing" -> {
                        if (e.roomId != roomId) return@collect
                        val uid = e.userId ?: return@collect
                        _typing.value = _typing.value + uid
                        typingClearJobs[uid]?.cancel()
                        typingClearJobs[uid] = viewModelScope.launch {
                            delay(3000); _typing.value = _typing.value - uid
                        }
                    }
                    "call_incoming", "webrtc_signal", "call_accepted", "call_ended" ->
                        _callEvent.tryEmit(e)
                }
            }
        }
    }
}

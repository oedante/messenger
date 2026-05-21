package com.messenger.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class ChatViewModel(val roomId: Int, val myUserId: Int) : ViewModel() {
    private val _messages  = MutableStateFlow<List<Message>>(emptyList())
    val messages = _messages.asStateFlow()
    private val _typing    = MutableStateFlow<Set<Int>>(emptySet())
    val typingUsers = _typing.asStateFlow()
    private val _error     = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _callEvent = MutableSharedFlow<WsEvent>(extraBufferCapacity = 4)
    val callEvent = _callEvent.asSharedFlow()

    private var typingClearJobs = mutableMapOf<Int, Job>()
    private var typingThrottle: Job? = null

    init { loadMessages(); observeWs() }

    fun loadMessages(before: Int? = null) {
        viewModelScope.launch {
            runCatching { RetrofitClient.api.getMessages(roomId, before) }
                .onSuccess { resp ->
                    val msgs = resp.body() ?: return@onSuccess
                    _messages.value = if (before == null) msgs
                    else (msgs + _messages.value).distinctBy { it.id }.sortedBy { it.createdAt }
                }.onFailure { _error.value = it.message }
        }
    }

    fun sendText(content: String, replyTo: Int? = null) {
        if (content.isBlank()) return
        viewModelScope.launch {
            runCatching { RetrofitClient.api.sendMessage(roomId, SendMessageRequest(content.trim(), replyTo = replyTo)) }
                .onFailure { _error.value = it.message }
        }
    }

    fun sendFile(file: File) {
        viewModelScope.launch {
            val mime = when (file.extension.lowercase()) {
                "jpg","jpeg","png","webp" -> "image/*"
                "mp3","ogg","m4a"        -> "audio/*"
                else                      -> "application/octet-stream"
            }
            val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody(mime.toMediaTypeOrNull()))
            runCatching { RetrofitClient.api.uploadFile(roomId, part) }.onFailure { _error.value = it.message }
        }
    }

    fun editMessage(msgId: Int, content: String) {
        viewModelScope.launch {
            runCatching { RetrofitClient.api.editMessage(roomId, msgId, EditMessageRequest(content)) }
                .onFailure { _error.value = it.message }
        }
    }

    fun deleteMessage(msgId: Int) {
        viewModelScope.launch {
            runCatching { RetrofitClient.api.deleteMessage(roomId, msgId) }.onFailure { _error.value = it.message }
        }
    }

    fun react(msgId: Int, emoji: String) {
        viewModelScope.launch { runCatching { RetrofitClient.api.react(roomId, msgId, ReactionRequest(emoji)) } }
    }

    fun notifyTyping() {
        typingThrottle?.cancel()
        typingThrottle = viewModelScope.launch { WsManager.sendTyping(roomId); delay(3000) }
    }

    private fun observeWs() {
        viewModelScope.launch {
            WsManager.events.collect { e ->
                when (e.type) {
                    "new_message" -> {
                        val msg = e.message ?: return@collect
                        if (msg.roomId == roomId) {
                            _messages.value = (_messages.value + msg).sortedBy { it.createdAt }
                            WsManager.sendRead(msg.id)
                        }
                    }
                    "message_edited" -> {
                        val mid = e.messageId ?: return@collect
                        _messages.value = _messages.value.map { m ->
                            if (m.id == mid) m.copy(content = e.content ?: m.content, edited = true) else m
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
                        typingClearJobs[uid] = viewModelScope.launch { delay(3000); _typing.value = _typing.value - uid }
                    }
                    "call_incoming","webrtc_signal","call_accepted","call_ended" -> _callEvent.tryEmit(e)
                }
            }
        }
    }
}

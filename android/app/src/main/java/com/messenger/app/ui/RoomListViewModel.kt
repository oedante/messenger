package com.messenger.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.models.Room
import com.messenger.app.network.RetrofitClient
import com.messenger.app.network.WsManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RoomListViewModel : ViewModel() {
    private val _rooms   = MutableStateFlow<List<Room>>(emptyList())
    val rooms   = _rooms.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    init { load(); observeWs() }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            runCatching { RetrofitClient.api.getRooms() }
                .onSuccess { if (it.isSuccessful) _rooms.value = it.body() ?: emptyList() }
            _loading.value = false
        }
    }

    private fun observeWs() {
        viewModelScope.launch {
            WsManager.events.collect { e ->
                when (e.type) {
                    "new_message" -> {
                        val msg = e.message ?: return@collect
                        _rooms.value = _rooms.value.map { r ->
                            if (r.id == msg.roomId) r.copy(lastMsg = msg.content, lastMsgAt = msg.createdAt, unread = r.unread + 1) else r
                        }.sortedByDescending { it.lastMsgAt }
                    }
                    "user_status" -> {
                        val uid = e.userId ?: return@collect
                        _rooms.value = _rooms.value.map { r ->
                            if (r.otherId == uid) r.copy(online = e.online ?: false) else r
                        }
                    }
                }
            }
        }
    }
}

package com.messenger.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.*
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.messenger.app.databinding.ActivityNewRoomBinding
import com.messenger.app.models.CreateRoomRequest
import com.messenger.app.models.User
import com.messenger.app.network.RetrofitClient
import com.messenger.app.ui.chat.ChatActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NewRoomActivity : AppCompatActivity() {
    private lateinit var b: ActivityNewRoomBinding
    private val selected = mutableListOf<User>()
    private var searchJob: Job? = null
    private var mode = "direct"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNewRoomBinding.inflate(layoutInflater); setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.title = "Новый чат"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.btnDirect.setOnClickListener  { setMode("direct") }
        b.btnGroup.setOnClickListener   { setMode("group") }
        b.btnChannel.setOnClickListener { setMode("channel") }
        setMode("direct")

        val adapter = UserSelectAdapter(selected) { updateCreate() }
        b.recycler.layoutManager = LinearLayoutManager(this)  // ИСПРАВЛЕНО
        b.recycler.adapter = adapter

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                if ((s?.length ?: 0) < 1) return
                searchJob = lifecycleScope.launch {
                    delay(300)
                    runCatching { RetrofitClient.api.searchUsers(s.toString()) }
                        .onSuccess { resp -> if (resp.isSuccessful) adapter.setUsers(resp.body() ?: emptyList()) }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        b.fabCreate.setOnClickListener { createRoom() }
    }

    private fun setMode(m: String) {
        mode = m
        b.btnDirect.alpha  = if (m == "direct")  1f else 0.4f
        b.btnGroup.alpha   = if (m == "group")   1f else 0.4f
        b.btnChannel.alpha = if (m == "channel") 1f else 0.4f
        b.layoutTitle.visibility = if (m == "direct") View.GONE else View.VISIBLE
        updateCreate()
    }

    private fun updateCreate() {
        b.fabCreate.isEnabled = when (mode) { "direct" -> selected.size == 1; else -> selected.isNotEmpty() }
    }

    private fun createRoom() {
        val title = b.etTitle.text.toString().trim().ifEmpty { null }
        lifecycleScope.launch {
            runCatching {
                RetrofitClient.api.createRoom(CreateRoomRequest(mode, title, memberIds = selected.map { it.id }))
            }.onSuccess { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body()!!
                    startActivity(Intent(this@NewRoomActivity, ChatActivity::class.java).apply {
                        putExtra(ChatActivity.EXTRA_ROOM_ID,    body.id)
                        putExtra(ChatActivity.EXTRA_ROOM_NAME,  title ?: selected.firstOrNull()?.name ?: "Чат")
                        putExtra(ChatActivity.EXTRA_IS_CHANNEL, mode == "channel")
                    })
                    finish()
                }
            }.onFailure { Toast.makeText(this@NewRoomActivity, it.message, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

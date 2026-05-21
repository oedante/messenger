package com.messenger.app.ui.chat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import com.bumptech.glide.Glide
import com.messenger.app.BuildConfig
import com.messenger.app.R
import com.messenger.app.data.SessionManager
import com.messenger.app.databinding.ActivityChatBinding
import com.messenger.app.models.Message
import com.messenger.app.network.RetrofitClient
import com.messenger.app.ui.ChatViewModel
import com.messenger.app.ui.call.CallActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {
    private lateinit var b: ActivityChatBinding
    private lateinit var vm: ChatViewModel
    private lateinit var s: SessionManager

    val roomId    by lazy { intent.getIntExtra(EXTRA_ROOM_ID, -1) }
    val roomName  by lazy { intent.getStringExtra(EXTRA_ROOM_NAME) ?: "Чат" }
    val otherUid  by lazy { intent.getIntExtra(EXTRA_OTHER_UID, -1) }
    val isChannel by lazy { intent.getBooleanExtra(EXTRA_IS_CHANNEL, false) }

    companion object {
        const val EXTRA_ROOM_ID    = "room_id"
        const val EXTRA_ROOM_NAME  = "room_name"
        const val EXTRA_OTHER_UID  = "other_uid"
        const val EXTRA_IS_CHANNEL = "is_channel"
        const val PICK_FILE_RC     = 42
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityChatBinding.inflate(layoutInflater); setContentView(b.root)
        s = SessionManager(this)

        // Исправлено: создание ViewModel через фабрику
        vm = ChatViewModelFactory(roomId, s.userId).create(ChatViewModel::class.java)

        setSupportActionBar(b.toolbar)
        supportActionBar?.title = roomName; supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (isChannel) b.layoutInput.visibility = View.GONE

        val adapter = MessageAdapter(s.userId,
            onLongClick = { showMsgMenu(it) },
            onReact     = { msg, emoji -> vm.react(msg.id, emoji) })

        b.recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.recycler.adapter = adapter

        b.btnSend.setOnClickListener { vm.sendText(b.etMessage.text.toString()); b.etMessage.text?.clear() }
        b.btnAttach.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }, PICK_FILE_RC)
        }
        b.etMessage.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { vm.notifyTyping() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        lifecycleScope.launch {
            vm.messages.collectLatest { msgs ->
                adapter.submitList(msgs)
                if (msgs.isNotEmpty()) b.recycler.scrollToPosition(msgs.size - 1)
            }
        }
        lifecycleScope.launch {
            vm.typingUsers.collectLatest { users ->
                b.tvTyping.visibility = if (users.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        lifecycleScope.launch {
            vm.callEvent.collect { e ->
                if (e.type == "call_incoming")
                    showIncomingCallDialog(e.callId ?: return@collect, e.callType ?: "audio", e.callerId ?: return@collect)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu)
        menu.findItem(R.id.action_call_audio)?.isVisible = otherUid != -1
        menu.findItem(R.id.action_call_video)?.isVisible = otherUid != -1
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_call_audio -> { startCall(false); true }
        R.id.action_call_video -> { startCall(true);  true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun startCall(video: Boolean) {
        startActivity(Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_ROOM_ID,    roomId)
            putExtra(CallActivity.EXTRA_REMOTE_UID, otherUid)
            putExtra(CallActivity.EXTRA_IS_VIDEO,   video)
            putExtra(CallActivity.EXTRA_INCOMING,   false)
        })
    }

    private fun showIncomingCallDialog(callId: Int, callType: String, callerId: Int) {
        val isVideo = callType == "video"
        AlertDialog.Builder(this)
            .setTitle("Входящий \${if (isVideo) "видео" else "аудио"} звонок")
            .setMessage("Пользователь #$callerId звонит вам")
            .setPositiveButton("Ответить") { _, _ ->
                startActivity(Intent(this, CallActivity::class.java).apply {
                    putExtra(CallActivity.EXTRA_CALL_ID,    callId)
                    putExtra(CallActivity.EXTRA_ROOM_ID,    roomId)
                    putExtra(CallActivity.EXTRA_REMOTE_UID, callerId)
                    putExtra(CallActivity.EXTRA_IS_VIDEO,   isVideo)
                    putExtra(CallActivity.EXTRA_INCOMING,   true)
                })
            }
            .setNegativeButton("Отклонить") { _, _ ->
                lifecycleScope.launch { runCatching { RetrofitClient.api.endCall(callId) } }
            }.show()
    }

    private fun showMsgMenu(msg: Message) {
        val opts = buildList {
            if (msg.senderId == s.userId) { add("Редактировать"); add("Удалить") }
            add("Ответить"); add("Реакция")
        }
        AlertDialog.Builder(this).setItems(opts.toTypedArray()) { _, i ->
            when (opts[i]) {
                "Редактировать" -> {
                    val et = EditText(this).apply { setText(msg.content) }
                    AlertDialog.Builder(this).setTitle("Редактировать").setView(et)
                        .setPositiveButton("Сохранить") { _, _ -> vm.editMessage(msg.id, et.text.toString()) }
                        .setNegativeButton("Отмена", null).show()
                }
                "Удалить"  -> vm.deleteMessage(msg.id)
                "Ответить" -> b.etMessage.hint = "↩ \${msg.content}"
                "Реакция"  -> {
                    val emojis = arrayOf("😀","😂","❤️","👍","🔥","😢","😮","🎉")
                    AlertDialog.Builder(this).setTitle("Реакция").setItems(emojis) { _, j ->
                        vm.react(msg.id, emojis[j])
                    }.show()
                }
            }
        }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE_RC && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                val tmp = File.createTempFile("upload", null, cacheDir)
                contentResolver.openInputStream(uri)?.use { it.copyTo(tmp.outputStream()) }
                vm.sendFile(tmp)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

// ── ViewModelFactory ───────────────────────────────────────────────────────────
class ChatViewModelFactory(private val roomId: Int, private val userId: Int)
    : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(roomId, userId) as T
    }
}

// ── MessageAdapter ─────────────────────────────────────────────────────────────
private val MSG_DIFF = object : DiffUtil.ItemCallback<Message>() {
    override fun areItemsTheSame(a: Message, b: Message) = a.id == b.id
    override fun areContentsTheSame(a: Message, b: Message) = a == b
}

class MessageAdapter(
    private val myUid: Int,
    private val onLongClick: (Message) -> Unit,
    private val onReact: (Message, String) -> Unit
) : ListAdapter<Message, MessageAdapter.VH>(MSG_DIFF) {

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val TYPE_MINE  = 0
    private val TYPE_THEIR = 1

    override fun getItemViewType(p: Int) = if (getItem(p).senderId == myUid) TYPE_MINE else TYPE_THEIR

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH {
        val layout = if (vt == TYPE_MINE) R.layout.item_message_mine else R.layout.item_message_their
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val msg = getItem(pos)
        h.tvContent.text = when (msg.type) {
            "image" -> "🖼 \${msg.fileName ?: msg.content}"
            "audio" -> "🎵 \${msg.fileName ?: msg.content}"
            "file"  -> "📎 \${msg.fileName ?: msg.content}"
            else    -> if (msg.edited) "\${msg.content} (ред.)" else msg.content
        }
        h.tvTime.text = fmt.format(Date(msg.createdAt * 1000))
        h.tvSender?.text = msg.senderLabel

        h.ivAvatar?.let { iv ->
            if (msg.senderAvatar != null)
                Glide.with(iv).load("\${BuildConfig.SERVER_URL}/files/${msg.senderAvatar}").circleCrop().into(iv)
            else
                iv.setImageResource(R.drawable.ic_default_avatar)
        }
        h.itemView.setOnLongClickListener { onLongClick(msg); true }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvContent: TextView  = v.findViewById(R.id.tvContent)
        val tvTime:    TextView  = v.findViewById(R.id.tvTime)
        val tvSender:  TextView? = v.findViewById(R.id.tvSender)
        val ivAvatar:  ImageView? = v.findViewById(R.id.ivAvatar)
    }
}

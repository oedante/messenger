package com.messenger.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import com.bumptech.glide.Glide
import com.messenger.app.BuildConfig
import com.messenger.app.R
import com.messenger.app.data.SessionManager
import com.messenger.app.databinding.ActivityRoomListBinding
import com.messenger.app.models.Room
import com.messenger.app.network.RetrofitClient
import com.messenger.app.network.WsManager
import com.messenger.app.ui.chat.ChatActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class RoomListActivity : AppCompatActivity() {
    private lateinit var b: ActivityRoomListBinding
    private lateinit var vm: RoomListViewModel
    private lateinit var s: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRoomListBinding.inflate(layoutInflater); setContentView(b.root)
        s = SessionManager(this)
        if (!s.isLoggedIn) { goLogin(); return }

        RetrofitClient.setToken(s.token); WsManager.connect(s.token!!)
        vm = ViewModelProvider(this)[RoomListViewModel::class.java]
        setSupportActionBar(b.toolbar); supportActionBar?.title = "Мессенджер"

        val adapter = RoomAdapter { room ->
            startActivity(Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_ROOM_ID,    room.id)
                putExtra(ChatActivity.EXTRA_ROOM_NAME,  room.displayTitle)
                putExtra(ChatActivity.EXTRA_OTHER_UID,  room.otherId ?: -1)
                putExtra(ChatActivity.EXTRA_IS_CHANNEL, room.isChannel)
            })
        }
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter
        b.swipeRefresh.setOnRefreshListener { vm.load() }
        b.fabNew.setOnClickListener { startActivity(Intent(this, NewRoomActivity::class.java)) }

        lifecycleScope.launch {
            vm.rooms.collectLatest { rooms ->
                adapter.submitList(rooms); b.swipeRefresh.isRefreshing = false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_profile -> { startActivity(Intent(this, ProfileActivity::class.java)); true }
        R.id.action_logout  -> {
            lifecycleScope.launch {
                runCatching { RetrofitClient.api.logout() }
                WsManager.disconnect(); s.clear(); RetrofitClient.setToken(null); goLogin()
            }; true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() { super.onResume(); vm.load() }
    private fun goLogin() { startActivity(Intent(this, LoginActivity::class.java)); finish() }
}

// ── Adapter ───────────────────────────────────────────────────────────────────
private val DIFF = object : DiffUtil.ItemCallback<Room>() {
    override fun areItemsTheSame(a: Room, b: Room) = a.id == b.id
    override fun areContentsTheSame(a: Room, b: Room) = a == b
}

class RoomAdapter(private val onClick: (Room) -> Unit) : ListAdapter<Room, RoomAdapter.VH>(DIFF) {
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(p: ViewGroup, v: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_room, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = getItem(pos)
        h.tvName.text    = r.displayTitle
        h.tvMsg.text     = r.lastMsg ?: ""
        h.tvTime.text    = r.lastMsgAt?.let { fmt.format(Date(it * 1000)) } ?: ""
        h.tvUnread.visibility = if (r.unread > 0) View.VISIBLE else View.GONE
        h.tvUnread.text  = r.unread.toString()
        h.tvBadge.text   = when (r.type) { "group" -> "Группа"; "channel" -> "Канал"; else -> "" }
        h.tvBadge.visibility = if (r.isDirect) View.GONE else View.VISIBLE
        h.ivOnline.visibility = if (!r.isGroup && !r.isChannel && r.online) View.VISIBLE else View.GONE

        if (r.avatar != null)
            Glide.with(h.ivAvatar).load("${BuildConfig.SERVER_URL}/files/${r.avatar}")
                .circleCrop().placeholder(R.drawable.ic_default_avatar).into(h.ivAvatar)
        else h.ivAvatar.setImageResource(
            when (r.type) { "channel" -> R.drawable.ic_channel; "group" -> R.drawable.ic_group; else -> R.drawable.ic_default_avatar })
        h.itemView.setOnClickListener { onClick(r) }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:   TextView  = v.findViewById(R.id.tvName)
        val tvMsg:    TextView  = v.findViewById(R.id.tvLastMessage)
        val tvTime:   TextView  = v.findViewById(R.id.tvTime)
        val tvUnread: TextView  = v.findViewById(R.id.tvUnread)
        val tvBadge:  TextView  = v.findViewById(R.id.tvBadge)
        val ivAvatar: ImageView = v.findViewById(R.id.ivAvatar)
        val ivOnline: View      = v.findViewById(R.id.ivOnline)
    }
}

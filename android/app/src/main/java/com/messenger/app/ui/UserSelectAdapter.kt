package com.messenger.app.ui

import android.view.*
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.messenger.app.BuildConfig
import com.messenger.app.R
import com.messenger.app.models.User

class UserSelectAdapter(
    private val selected: MutableList<User>,
    private val onChange: () -> Unit
) : RecyclerView.Adapter<UserSelectAdapter.VH>() {

    private var users: List<User> = emptyList()

    fun setUsers(list: List<User>) { users = list; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_user_select, parent, false))

    override fun getItemCount() = users.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val u = users[pos]
        h.tvName.text = u.name
        h.cb.isChecked = selected.any { it.id == u.id }

        if (u.avatar != null)
            Glide.with(h.iv).load("${BuildConfig.SERVER_URL}/files/${u.avatar}").circleCrop().into(h.iv)
        else
            h.iv.setImageResource(R.drawable.ic_default_avatar)

        h.itemView.setOnClickListener {
            if (selected.any { it.id == u.id }) selected.removeAll { it.id == u.id }
            else selected.add(u)
            notifyItemChanged(pos)
            onChange()
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView  = v.findViewById(R.id.tvUsername)
        val iv: ImageView     = v.findViewById(R.id.ivAvatar)
        val cb: CheckBox      = v.findViewById(R.id.checkBox)
    }
}

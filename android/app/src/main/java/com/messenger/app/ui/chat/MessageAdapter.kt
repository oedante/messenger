package com.messenger.app.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.messenger.app.BuildConfig
import com.messenger.app.R
import com.messenger.app.models.Message
import java.text.SimpleDateFormat
import java.util.*

private val MSG_DIFF = object : DiffUtil.ItemCallback<Message>() {
    override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
        return oldItem.id == newItem.id
    }
    
    override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
        return oldItem == newItem
    }
}

class MessageAdapter(
    private val myUid: Int,
    private val onLongClick: (Message) -> Unit,
    private val onReact: (Message, String) -> Unit
) : ListAdapter<Message, MessageAdapter.ViewHolder>(MSG_DIFF) {

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    
    companion object {
        private const val TYPE_MINE = 0
        private const val TYPE_THEIR = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).senderId == myUid) TYPE_MINE else TYPE_THEIR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == TYPE_MINE) {
            R.layout.item_message_mine
        } else {
            R.layout.item_message_their
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = getItem(position)
        
        holder.tvContent.text = when (message.type) {
            "image" -> "🖼 ${message.fileName ?: message.content}"
            "audio" -> "🎵 ${message.fileName ?: message.content}"
            "file"  -> "📎 ${message.fileName ?: message.content}"
            else    -> if (message.edited) "${message.content} (ред.)" else message.content
        }
        
        holder.tvTime.text = fmt.format(Date(message.createdAt * 1000))
        holder.tvSender?.text = message.senderLabel

        holder.ivAvatar?.let { iv ->
            if (message.senderAvatar != null) {
                Glide.with(iv)
                    .load("${BuildConfig.SERVER_URL}/files/${message.senderAvatar}")
                    .circleCrop()
                    .into(iv)
            } else {
                iv.setImageResource(R.drawable.ic_default_avatar)
            }
        }
        
        holder.itemView.setOnLongClickListener { 
            onLongClick(message)
            true 
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvContent: TextView = itemView.findViewById(R.id.tvContent)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val tvSender: TextView? = itemView.findViewById(R.id.tvSender)
        val ivAvatar: ImageView? = itemView.findViewById(R.id.ivAvatar)
    }
}

package com.messenger.app.models
import com.google.gson.annotations.SerializedName

data class AuthRequest(val username: String, val password: String,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("device_name")  val deviceName: String? = null)
data class AuthResponse(val token: String, @SerializedName("user_id") val userId: Int)

data class User(val id: Int, val username: String,
    @SerializedName("display_name") val displayName: String?,
    val avatar: String?, val bio: String?,
    val online: Boolean = false,
    @SerializedName("last_seen") val lastSeen: Long = 0L,
    val phone: String? = null) {
    val name get() = displayName?.takeIf { it.isNotBlank() } ?: username
}
data class UpdateProfileRequest(
    @SerializedName("display_name") val displayName: String? = null,
    val bio: String? = null,
    val phone: String? = null)
data class AvatarResponse(val avatar: String)

data class Room(val id: Int, val type: String, val title: String?,
    val description: String?, val avatar: String?,
    @SerializedName("invite_link")  val inviteLink: String?,
    @SerializedName("last_msg")     val lastMsg: String?,
    @SerializedName("last_msg_at")  val lastMsgAt: Long?,
    val unread: Int = 0, val members: List<RoomMember>? = null,
    val pinned: List<Message>? = null,
    @SerializedName("other_id")     val otherId: Int? = null,
    val online: Boolean = false, val role: String = "member") {
    val displayTitle get() = title ?: "Чат"
    val isDirect  get() = type == "direct"
    val isGroup   get() = type == "group"
    val isChannel get() = type == "channel"
}
data class RoomMember(val id: Int, val username: String,
    @SerializedName("display_name") val displayName: String?,
    val avatar: String?, val online: Boolean, val role: String) {
    val name get() = displayName?.takeIf { it.isNotBlank() } ?: username
}
data class CreateRoomRequest(val type: String, val title: String? = null,
    val description: String? = null,
    @SerializedName("member_ids") val memberIds: List<Int> = emptyList())
data class CreateRoomResponse(val id: Int,
    @SerializedName("invite_link") val inviteLink: String?, val exists: Boolean)

data class Message(val id: Int,
    @SerializedName("room_id")        val roomId: Int,
    @SerializedName("sender_id")      val senderId: Int?,
    @SerializedName("sender_name")    val senderName: String?,
    @SerializedName("sender_display") val senderDisplay: String?,
    @SerializedName("sender_avatar")  val senderAvatar: String?,
    val content: String, val type: String = "text",
    @SerializedName("file_url")       val fileUrl: String?,
    @SerializedName("file_name")      val fileName: String?,
    @SerializedName("reply_to")       val replyTo: Int?,
    val edited: Boolean = false, val deleted: Boolean = false, val pin: Boolean = false,
    @SerializedName("created_at")     val createdAt: Long) {
    val senderLabel get() = senderDisplay?.takeIf { it.isNotBlank() } ?: senderName ?: "?"
}
data class SendMessageRequest(val content: String, val type: String = "text",
    @SerializedName("reply_to")       val replyTo: Int? = null,
    @SerializedName("forwarded_from") val forwardedFrom: Int? = null)
data class EditMessageRequest(val content: String)
data class ReactionRequest(val emoji: String)

data class InitCallRequest(@SerializedName("room_id") val roomId: Int, val type: String = "audio")
data class InitCallResponse(@SerializedName("call_id") val callId: Int)
data class CallSignalRequest(
    @SerializedName("call_id")        val callId: Int,
    @SerializedName("target_user_id") val targetUserId: Int,
    @SerializedName("signal_type")    val signalType: String,
    val payload: String)

data class WsEvent(val type: String, val message: Message? = null,
    @SerializedName("message_id")   val messageId: Int? = null,
    val content: String? = null,
    @SerializedName("user_id")      val userId: Int? = null,
    @SerializedName("room_id")      val roomId: Int? = null,
    @SerializedName("call_id")      val callId: Int? = null,
    @SerializedName("call_type")    val callType: String? = null,
    @SerializedName("caller_id")    val callerId: Int? = null,
    @SerializedName("from_user_id") val fromUserId: Int? = null,
    @SerializedName("signal_type")  val signalType: String? = null,
    val payload: String? = null, val online: Boolean? = null,
    val emoji: String? = null, val action: String? = null)

data class PublicKeyRequest(
    @SerializedName("public_key") val publicKey: String
)

// ── Phone Auth ───────────────────────────────────────────
data class PhoneCodeRequest(val phone: String)
data class PhoneCodeResponse(val ok: Boolean, val message: String? = null)
data class PhoneVerifyRequest(
    val phone: String,
    val code: String,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("device_name")  val deviceName: String? = null
)

// ── Contact Search ───────────────────────────────────────
data class FindContactsRequest(val phones: List<String>)

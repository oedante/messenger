package com.messenger.app.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.messenger.app.data.SessionManager
import com.messenger.app.databinding.ActivityCallBinding
import com.messenger.app.models.InitCallRequest
import com.messenger.app.network.CallManager
import com.messenger.app.network.RetrofitClient
import com.messenger.app.network.WsManager
import kotlinx.coroutines.launch

class CallActivity : AppCompatActivity() {
    private lateinit var b: ActivityCallBinding
    private lateinit var s: SessionManager
    private var callManager: CallManager? = null

    val roomId         by lazy { intent.getIntExtra(EXTRA_ROOM_ID,    -1)    }
    val remoteUid      by lazy { intent.getIntExtra(EXTRA_REMOTE_UID, -1)    }
    val isVideo        by lazy { intent.getBooleanExtra(EXTRA_IS_VIDEO, false) }
    val isIncoming     by lazy { intent.getBooleanExtra(EXTRA_INCOMING, false) }
    val incomingCallId by lazy { intent.getIntExtra(EXTRA_CALL_ID,    -1)    }

    private var callId     = -1
    private var audioMuted = false
    private var videoMuted = false

    companion object {
        const val EXTRA_ROOM_ID    = "room_id"
        const val EXTRA_REMOTE_UID = "remote_uid"
        const val EXTRA_IS_VIDEO   = "is_video"
        const val EXTRA_INCOMING   = "incoming"
        const val EXTRA_CALL_ID    = "call_id"
        private const val PERMS_RC = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCallBinding.inflate(layoutInflater); setContentView(b.root)
        s = SessionManager(this)

        b.localSurface.visibility  = if (isVideo) View.VISIBLE else View.GONE
        b.remoteSurface.visibility = if (isVideo) View.VISIBLE else View.GONE

        if (isIncoming) {
            b.layoutIncoming.visibility = View.VISIBLE
            b.layoutActive.visibility   = View.GONE
            b.btnAccept.setOnClickListener  { acceptCall() }
            b.btnDecline.setOnClickListener { declineAndFinish() }
        } else {
            b.layoutIncoming.visibility = View.GONE
            b.layoutActive.visibility   = View.VISIBLE
            requestPermissions()
        }

        b.btnEndCall.setOnClickListener   { endCall() }
        b.btnMuteAudio.setOnClickListener { toggleAudio() }
        b.btnMuteVideo.setOnClickListener { toggleVideo() }
        b.btnSwitchCam.setOnClickListener { callManager?.switchCamera() }
        b.btnSwitchCam.visibility = if (isVideo) View.VISIBLE else View.GONE

        observeSignals()
    }

    private fun requestPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO).apply {
            if (isVideo) add(Manifest.permission.CAMERA)
        }
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            initOutgoing()
        else
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMS_RC)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        if (rc == PERMS_RC && results.all { it == PackageManager.PERMISSION_GRANTED }) initOutgoing()
        else { Toast.makeText(this, "Нет разрешений", Toast.LENGTH_SHORT).show(); finish() }
    }

    private fun initOutgoing() {
        lifecycleScope.launch {
            runCatching { RetrofitClient.api.initCall(InitCallRequest(roomId, if (isVideo) "video" else "audio")) }
                .onSuccess { resp ->
                    if (resp.isSuccessful) {
                        callId = resp.body()!!.callId
                        setupCallManager(); callManager?.createOffer()
                        b.tvCallStatus.text = "Вызов…"
                    }
                }
                .onFailure { Toast.makeText(this@CallActivity, "Ошибка: ${it.message}", Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    private fun acceptCall() {
        callId = incomingCallId
        lifecycleScope.launch {
            runCatching { RetrofitClient.api.acceptCall(callId) }
                .onSuccess {
                    b.layoutIncoming.visibility = View.GONE; b.layoutActive.visibility = View.VISIBLE
                    requestPermissions()
                }
                .onFailure { finish() }
        }
    }

    private fun setupCallManager() {
        callManager = CallManager(this, s.userId, callId, remoteUid, isVideo, lifecycleScope).apply {
            localView  = if (isVideo) b.localSurface  else null
            remoteView = if (isVideo) b.remoteSurface else null
            onDisconnected = { runOnUiThread { endCall() } }
        }
        b.tvCallStatus.text = "Соединение…"
    }

    private fun observeSignals() {
        lifecycleScope.launch {
            WsManager.events.collect { e ->
                when (e.type) {
                    "call_accepted"  -> b.tvCallStatus.text = "Соединено"
                    "call_ended"     -> cleanupAndFinish()
                    "webrtc_signal"  -> {
                        if (e.callId != callId) return@collect
                        when (e.signalType) {
                            "offer"         -> { setupCallManager(); callManager?.handleOffer(e.payload ?: return@collect) }
                            "answer"        -> callManager?.handleAnswer(e.payload ?: return@collect)
                            "ice-candidate" -> callManager?.handleIceCandidate(e.payload ?: return@collect)
                            "end"           -> cleanupAndFinish()
                        }
                    }
                }
            }
        }
    }

    private fun endCall() {
        if (callId != -1) lifecycleScope.launch { runCatching { RetrofitClient.api.endCall(callId) } }
        cleanupAndFinish()
    }

    private fun declineAndFinish() {
        if (incomingCallId != -1) lifecycleScope.launch { runCatching { RetrofitClient.api.endCall(incomingCallId) } }
        finish()
    }

    private fun cleanupAndFinish() { callManager?.release(); callManager = null; finish() }

    private fun toggleAudio() {
        audioMuted = !audioMuted; callManager?.muteAudio(audioMuted)
        b.btnMuteAudio.alpha = if (audioMuted) 0.4f else 1.0f
    }

    private fun toggleVideo() {
        videoMuted = !videoMuted; callManager?.muteVideo(videoMuted)
        b.btnMuteVideo.alpha = if (videoMuted) 0.4f else 1.0f
    }

    override fun onDestroy() { super.onDestroy(); callManager?.release() }
}

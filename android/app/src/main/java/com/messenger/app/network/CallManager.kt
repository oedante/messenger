package com.messenger.app.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.messenger.app.models.CallSignalRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.webrtc.*

private const val TAG = "CallManager"

class CallManager(
    context: Context,
    private val myUserId: Int,
    private val callId: Int,
    private val remoteUserId: Int,
    private val isVideo: Boolean,
    private val scope: CoroutineScope
) {
    private val gson = Gson()
    private val eglBase = EglBase.create()

    private val factory: PeerConnectionFactory = run {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        // Для продакшена добавьте TURN-сервер:
        // PeerConnection.IceServer.builder("turn:YOUR_TURN:3478")
        //     .setUsername("user").setPassword("pass").createIceServer()
    )

    private val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null

    var localView: SurfaceViewRenderer? = null
    var remoteView: SurfaceViewRenderer? = null
    var onRemoteTrack: ((VideoTrack) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private val peerConnection: PeerConnection = factory.createPeerConnection(
        rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                signal("ice-candidate", gson.toJson(
                    mapOf("sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "sdp" to c.sdp)
                ))
            }
            override fun onTrack(t: RtpTransceiver) {
                val track = t.receiver.track() ?: return
                if (track is VideoTrack) { remoteView?.let { track.addSink(it) }; onRemoteTrack?.invoke(track) }
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE: $s")
                if (s == PeerConnection.IceConnectionState.DISCONNECTED ||
                    s == PeerConnection.IceConnectionState.FAILED) onDisconnected?.invoke()
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {}
        })!!

    init {
        localAudioTrack = factory.createAudioTrack("audio0",
            factory.createAudioSource(MediaConstraints()))
        peerConnection.addTrack(localAudioTrack!!)

        if (isVideo) {
            capturer = createCameraCapturer(context)
            capturer?.let { cap ->
                val src = factory.createVideoSource(cap.isScreencast)
                cap.initialize(SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext), context, src.capturerObserver)
                cap.startCapture(1280, 720, 30)
                localVideoTrack = factory.createVideoTrack("video0", src)
                localView?.let { localVideoTrack?.addSink(it) }
                peerConnection.addTrack(localVideoTrack!!)
            }
        }
    }

    fun createOffer() {
        val c = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            if (isVideo) mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection.setLocalDescription(SimpleSdpObserver(), sdp)
                signal("offer", gson.toJson(mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description)))
            }
        }, c)
    }

    fun handleOffer(json: String) {
        val m = gson.fromJson(json, Map::class.java)
        peerConnection.setRemoteDescription(SimpleSdpObserver(),
            SessionDescription(SessionDescription.Type.fromCanonicalForm(m["type"] as String), m["sdp"] as String))
        peerConnection.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection.setLocalDescription(SimpleSdpObserver(), sdp)
                signal("answer", gson.toJson(mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description)))
            }
        }, MediaConstraints())
    }

    fun handleAnswer(json: String) {
        val m = gson.fromJson(json, Map::class.java)
        peerConnection.setRemoteDescription(SimpleSdpObserver(),
            SessionDescription(SessionDescription.Type.fromCanonicalForm(m["type"] as String), m["sdp"] as String))
    }

    fun handleIceCandidate(json: String) {
        val m = gson.fromJson(json, Map::class.java)
        peerConnection.addIceCandidate(IceCandidate(
            m["sdpMid"] as String, (m["sdpMLineIndex"] as Double).toInt(), m["sdp"] as String))
    }

    fun muteAudio(muted: Boolean) { localAudioTrack?.setEnabled(!muted) }
    fun muteVideo(muted: Boolean) { localVideoTrack?.setEnabled(!muted) }
    fun switchCamera() { (capturer as? CameraVideoCapturer)?.switchCamera(null) }

    fun release() {
        capturer?.stopCapture(); capturer?.dispose()
        localVideoTrack?.dispose(); localAudioTrack?.dispose()
        peerConnection.close(); factory.dispose(); eglBase.release()
    }

    private fun signal(type: String, payload: String) {
        scope.launch {
            runCatching { RetrofitClient.api.sendSignal(CallSignalRequest(callId, remoteUserId, type, payload)) }
        }
    }

    private fun createCameraCapturer(ctx: Context): CameraVideoCapturer? {
        val e = Camera2Enumerator(ctx)
        for (name in e.deviceNames) if (e.isFrontFacing(name)) return e.createCapturer(name, null)
        for (name in e.deviceNames) return e.createCapturer(name, null)
        return null
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(s: String) { Log.e("SDP", "create fail: $s") }
    override fun onSetFailure(s: String) { Log.e("SDP", "set fail: $s") }
}

package com.messenger.app.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.messenger.app.BuildConfig
import com.messenger.app.models.WsEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private const val TAG = "Network"

object RetrofitClient {
    private var token: String? = null

    private val httpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().apply {
                    token?.let { addHeader("Authorization", "Bearer $it") }
                    addHeader("X-Client-Version", "3.0.0")
                    addHeader("Accept", "application/json")
                }.build())
            }

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }

        builder.build()
    }

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl("\${BuildConfig.SERVER_URL}/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    fun setToken(t: String?) { token = t }
}

object WsManager {
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var currentToken: String? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    private val MAX_RECONNECT_ATTEMPTS = 15
    private val BASE_RECONNECT_DELAY_MS = 2000L

    private val _events    = MutableSharedFlow<WsEvent>(extraBufferCapacity = 128)
    private val _connected = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)

    val events    = _events.asSharedFlow()
    val connected = _connected.asSharedFlow()

    fun connect(token: String) {
        currentToken = token
        shouldReconnect = true
        reconnectAttempts = 0
        doConnect(token)
    }

    private fun doConnect(token: String) {
        disconnect(reconnect = false)

        val wsUrl = BuildConfig.SERVER_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/ws?token=$token"

        if (BuildConfig.DEBUG) Log.d(TAG, "WS connecting to: $wsUrl")

        socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, r: Response) {
                    reconnectAttempts = 0
                    _connected.tryEmit(true)
                    if (BuildConfig.DEBUG) Log.d(TAG, "WS connected")
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val event = gson.fromJson(text, WsEvent::class.java)
                        if (event.type.isNotBlank()) _events.tryEmit(event)
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "WS parse: $e")
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                    _connected.tryEmit(false)
                    if (BuildConfig.DEBUG) Log.e(TAG, "WS fail: \${t.message}")
                    scheduleReconnect()
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    _connected.tryEmit(false)
                    if (code != 1000) scheduleReconnect()
                }
            })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val token = currentToken ?: return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "WS max reconnect attempts reached")
            return
        }
        reconnectAttempts++
        val delay = BASE_RECONNECT_DELAY_MS * minOf(reconnectAttempts.toLong(), 10)
        if (BuildConfig.DEBUG) Log.d(TAG, "WS reconnecting in \${delay}ms (attempt $reconnectAttempts)")
        handler.postDelayed({ doConnect(token) }, delay)
    }

    fun sendTyping(roomId: Int)  = send(mapOf("type" to "typing",  "room_id"    to roomId))
    fun sendRead(messageId: Int) = send(mapOf("type" to "read",    "message_id" to messageId))
    fun ping()                   = send(mapOf("type" to "ping"))

    private fun send(obj: Any) {
        try { socket?.send(gson.toJson(obj)) }
        catch (e: Exception) { if (BuildConfig.DEBUG) Log.e(TAG, "send: $e") }
    }

    fun disconnect(reconnect: Boolean = true) {
        if (!reconnect) shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        try { socket?.close(1000, "bye") } catch (_: Exception) {}
        socket = null
    }
}

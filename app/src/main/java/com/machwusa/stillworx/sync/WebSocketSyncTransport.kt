package com.machwusa.stillworx.sync

import android.util.Base64
import android.util.Log
import com.machwusa.stillworx.domain.model.ConnectionState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class WebSocketSyncTransport(
    private val client: OkHttpClient,
    private val serverUrl: String,
    private val boardId: String,
    private val scope: CoroutineScope,
) : SyncTransport {
    private val shouldRun = AtomicBoolean(false)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _manualReconnectRequired = MutableStateFlow(false)
    private val _incomingDocuments = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var consecutiveFailures = 0
    private var isManualAttempt = false

    override val connectionState = _connectionState.asStateFlow()
    override val manualReconnectRequired = _manualReconnectRequired.asStateFlow()
    override val incomingDocuments = _incomingDocuments.asSharedFlow()

    override fun connect() {
        shouldRun.set(true)
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        reconnectJob?.cancel()
        _connectionState.value = ConnectionState.CONNECTING
        webSocket = client.newWebSocket(
            Request.Builder().url(serverUrl).build(),
            Listener(),
        )
    }

    override fun retryConnection() {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        reconnectJob?.cancel()
        isManualAttempt = true
        _manualReconnectRequired.value = false
        connect()
    }

    override fun send(document: ByteArray): Boolean {
        val envelope = JSONObject()
            .put(Envelope.TYPE, Envelope.SYNC)
            .put(Envelope.BOARD_ID, boardId)
            .put(Envelope.DOCUMENT, Base64.encodeToString(document, Base64.NO_WRAP))
            .toString()
        return webSocket?.send(envelope) == true
    }

    override fun close() {
        shouldRun.set(false)
        reconnectJob?.cancel()
        webSocket?.close(1000, "App closed")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _manualReconnectRequired.value = false
        client.dispatcher.executorService.shutdown()
    }

    private fun disconnected(socket: WebSocket) {
        if (webSocket !== socket) return
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        if (!shouldRun.get()) return
        if (isManualAttempt) {
            isManualAttempt = false
            _manualReconnectRequired.value = true
            return
        }
        consecutiveFailures += 1
        if (consecutiveFailures > MAX_AUTOMATIC_RETRIES) {
            _manualReconnectRequired.value = true
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            connect()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (this@WebSocketSyncTransport.webSocket === webSocket) {
                consecutiveFailures = 0
                isManualAttempt = false
                _manualReconnectRequired.value = false
                _connectionState.value = ConnectionState.CONNECTED
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val envelope = JSONObject(text)
                if (envelope.optString(Envelope.TYPE) != Envelope.SYNC ||
                    envelope.optString(Envelope.BOARD_ID) != boardId
                ) return
                Base64.decode(envelope.getString(Envelope.DOCUMENT), Base64.DEFAULT)
            }.onSuccess(_incomingDocuments::tryEmit)
                .onFailure { Log.w(TAG, "Ignoring invalid sync envelope", it) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = disconnected(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.i(TAG, "WebSocket disconnected: ${t.message}")
            disconnected(webSocket)
        }
    }

    private object Envelope {
        const val TYPE = "type"
        const val SYNC = "sync"
        const val BOARD_ID = "boardId"
        const val DOCUMENT = "document"
    }

    private companion object {
        const val TAG = "WebSocketTransport"
        const val RECONNECT_DELAY_MS = 2_000L
        const val MAX_AUTOMATIC_RETRIES = 3
    }
}

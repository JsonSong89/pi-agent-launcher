package com.piagent.launcher.bridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.piagent.launcher.conversations.PiConversationService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback TCP bridge between IDEA and the pi extension
 * (~/.pi/agent/extensions/pi-launcher-bridge.ts).
 *
 * Protocol: one JSON object per line, per-request connections from pi side.
 * Envelope: {v, seq, type, tabKey, token, data}. Unknown types are dropped,
 * seq de-duplication is scoped per tabKey (each pi process counts its own).
 * Security: random per-instance port + token; messages with a wrong token
 * are rejected.
 */
@Service(Service.Level.PROJECT)
class PiBridgeServer(private val project: Project) {

    private val logger = Logger.getInstance(PiBridgeServer::class.java)
    private val started = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var token: String = ""

    private val gson = Gson()
    private val lastSeqByTab = ConcurrentHashMap<String, Long>()

    class Endpoint(val port: Int, val token: String)

    /**
     * Idempotent. Returns the endpoint pi should be told about, or null when
     * the server could not start (bridge degrades to no-op, core features fine).
     */
    fun ensureStarted(): Endpoint? {
        if (!started.compareAndSet(false, true)) return currentEndpoint()
        return try {
            val socket = ServerSocket()
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            token = token()
            serverSocket = socket
            val acceptThread = Thread({ acceptLoop(socket) }, "PiBridgeServer")
            acceptThread.isDaemon = true
            acceptThread.start()
            currentEndpoint()
        } catch (e: Exception) {
            started.set(false)
            logger.warn("Pi bridge server failed to start: ${e.message}")
            null
        }
    }

    private fun currentEndpoint(): Endpoint? =
        serverSocket?.let { Endpoint(it.localPort, token) }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                val handler = Thread({ handle(client) }, "PiBridgeClient")
                handler.isDaemon = true
                handler.start()
            } catch (_: Exception) {
                // server closed or transient accept failure
            }
        }
    }

    private fun handle(client: java.net.Socket) {
        try {
            client.use { s ->
                s.soTimeout = 10_000
                BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).useLines { lines ->
                    for (line in lines) {
                        if (line.isNotBlank()) handleMessage(line.trim())
                    }
                }
            }
        } catch (_: Exception) {
            // per-request connections; failures are expected
        }
    }

    private fun handleMessage(line: String) {
        val envelope = try {
            gson.fromJson(line, JsonObject::class.java)
        } catch (e: Exception) {
            logger.warn("Pi bridge: unparseable message: ${e.message}")
            return
        }

        val v = envelope.get("v")?.asInt ?: 0
        if (v != PROTOCOL_VERSION) {
            logger.warn("Pi bridge: dropping message with protocol v=$v (expected $PROTOCOL_VERSION)")
            return
        }
        if (envelope.get("token")?.asString != token) {
            logger.warn("Pi bridge: rejected message with bad token")
            return
        }

        val tabKey = envelope.get("tabKey")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val type = envelope.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val data = envelope.getAsJsonObject("data") ?: gson.toJsonTree(mapOf<String, Any>()).asJsonObject
        val seq = envelope.get("seq")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

        // Per-tabKey seq de-dup: each pi process counts independently.
        if (seq > 0) {
            val last = lastSeqByTab[tabKey] ?: 0L
            if (seq <= last) return
            lastSeqByTab[tabKey] = seq
        }

        // Orphan routing: handlers drop unknown tabKeys silently.
        when (type) {
            "session_changed" -> str(data, "sessionId")?.let {
                PiConversationService.getInstance(project).onBridgeSessionChanged(tabKey, it)
            }
            "session_stopped" ->
                PiConversationService.getInstance(project).onBridgeSessionStopped(tabKey)
            "agent_state" -> str(data, "state")?.let {
                PiConversationService.getInstance(project).onBridgeAgentState(tabKey, it)
            }
            "model_changed" -> str(data, "modelId")?.let { model ->
                PiConversationService.getInstance(project).onBridgeModelChanged(tabKey, model)
            }
            "file_modified" -> str(data, "path")?.let { path ->
                PiConversationService.getInstance(project).onBridgeFileModified(tabKey, path)
            }
            else -> logger.info("Pi bridge: ignored unknown type '$type'")
        }
    }

    private fun str(data: JsonObject, key: String): String? =
        data.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun token(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun toString(): String = "PiBridgeServer(port=${serverSocket?.localPort})"

    companion object {
        const val PROTOCOL_VERSION = 1

        fun getInstance(project: Project): PiBridgeServer = project.service()
    }
}

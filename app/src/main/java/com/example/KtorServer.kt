package com.example

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class KtorServer(private val context: Context, val port: Int = 8080) {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val activeSessions = ConcurrentHashMap<String, WebSocketSession>()
    private var serverInstance: ApplicationEngine? = null

    fun start() {
        if (serverInstance != null) return

        // Set up the listener in GameEngine to broadcast updates immediately
        GameEngine.onStateChangedListener = {
            broadcastState()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    install(WebSockets) {
                        // Keep-alive settings to prevent aggressive sleep modes on Android and iOS devices
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }

                    routing {
                        // 1. Serve static visual client index.html
                        get("/") {
                            try {
                                val htmlContent = this@KtorServer.context.assets.open("index.html").bufferedReader().use { it.readText() }
                                call.respondText(htmlContent, ContentType.Text.Html)
                            } catch (e: Throwable) {
                                call.respondText(
                                    "خطأ في سيرفر الحنيوك أثناء قراءة أصول اللعبة: ${e.localizedMessage}", 
                                    ContentType.Text.Plain
                                )
                            }
                        }

                        // Serve static tailwind.min.js offline
                        get("/tailwind.min.js") {
                            try {
                                val jsContent = this@KtorServer.context.assets.open("tailwind.min.js").bufferedReader().use { it.readText() }
                                call.respondText(jsContent, ContentType.parse("application/javascript"))
                            } catch (e: Throwable) {
                                call.respondText("", ContentType.parse("application/javascript"))
                            }
                        }

                        // 2. Real-time game logic communication channel (WebSocket)
                        webSocket("/game") {
                            val playerId = call.parameters["id"] ?: java.util.UUID.randomUUID().toString()
                            activeSessions[playerId] = this

                            // Match session
                            synchronized(GameEngine) {
                                val player = GameEngine.players.value.find { it.id == playerId }
                                if (player != null) {
                                    player.isOnline = true
                                    player.lastActive = System.currentTimeMillis()
                                    GameEngine.addLog("${player.name} استأنف اتصاله وتزامن مع اللعبة 🔄")
                                }
                            }

                            // Inform clients about newest connection list
                            broadcastState()

                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val text = frame.readText()
                                        handleClientPacket(playerId, text)
                                    }
                                }
                            } catch (e: Throwable) {
                                e.printStackTrace()
                            } finally {
                                // Mark player as offline, handle timeouts separately via GameEngine
                                activeSessions.remove(playerId)
                                GameEngine.markPlayerOffline(playerId)
                                broadcastState()
                            }
                        }
                    }
                }
                server.start(wait = false)
                synchronized(this@KtorServer) {
                    serverInstance = server
                }
            } catch (e: Throwable) {
                 e.printStackTrace()
                 synchronized(this@KtorServer) {
                     serverInstance = null
                 }
                 GameEngine.addLog("⚠️ فشل تشغيل سيرفر الشبكة: ${e.localizedMessage}. تم التحويل لنمط اللعب المحلي بالكامل.")
            }
        }
    }

    fun stop() {
        GameEngine.onStateChangedListener = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverInstance?.stop(1000, 2000)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            serverInstance = null
        }
    }

    /**
     * Sends the current game state to all connected web browsers.
     * GameEngine handles obfuscation per player to prevent cheating and screen peeping.
     */
    fun broadcastState() {
        val currentConnected = activeSessions.toMap()
        currentConnected.forEach { (playerId, session) ->
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val payload = GameEngine.getSerializedStateForPlayer(playerId)
                    session.send(Frame.Text(payload))
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleClientPacket(playerId: String, json: String) {
        try {
            val mapAdapter = moshi.adapter(Map::class.java)
            val data = mapAdapter.fromJson(json) ?: return
            val action = data["action"] as? String ?: return

            when (action) {
                "join" -> {
                    val name = data["name"] as? String ?: "لاعب غامض"
                    val avatar = data["avatar"] as? String ?: ""
                    GameEngine.registerPlayer(playerId, name, avatar)
                }
                "toggle_ready" -> {
                    GameEngine.togglePlayerReady(playerId)
                }
                "mafia_vote" -> {
                    val targetId = data["targetId"] as? String
                    GameEngine.submitMafiaVote(playerId, targetId)
                }
                "mafia_chat" -> {
                    val chatText = data["text"] as? String ?: ""
                    val sender = GameEngine.players.value.find { it.id == playerId }
                    if (sender != null && chatText.isNotEmpty()) {
                        GameEngine.submitMafiaChat(playerId, sender.name, chatText)
                    }
                }
                "doctor_vote" -> {
                    val targetId = data["targetId"] as? String
                    GameEngine.submitDoctorVote(playerId, targetId)
                }
                "detective_vote" -> {
                    val targetId = data["targetId"] as? String
                    GameEngine.submitDetectiveVote(playerId, targetId)
                }
                "day_vote" -> {
                    val targetId = data["targetId"] as? String
                    GameEngine.submitDayVote(playerId, targetId)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}

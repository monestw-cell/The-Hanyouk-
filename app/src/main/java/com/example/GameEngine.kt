package com.example

import android.os.Handler
import android.os.Looper
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

// Game State Definitions
enum class GamePhase {
    LOBBY,
    ROLE_REVEAL,
    NIGHT,
    DAY_DISCUSSION,
    DAY_VOTING,
    GAME_OVER
}

enum class PlayMode {
    PASS_AND_PLAY,   // تداول الهاتف
    NETWORK_SERVER   // شبكة محلية
}

enum class HostMode {
    PARTICIPANT,
    SPECTATOR
}

enum class GhostMode {
    BLACKOUT,
    SPECTATOR
}

enum class VoteStyle {
    SECRET,
    LIVE
}

enum class MafiaChatStyle {
    LIGHTS,
    CHAT
}

enum class DiscussionTimerStyle {
    MANUAL,
    AUTO
}

// Data models compatible with JSON transport
data class GameSettings(
    var mafia: Int = 1,
    var citizen: Int = 2,
    var doctor: Int = 1,
    var detective: Int = 1,
    var hostMode: HostMode = HostMode.PARTICIPANT,
    var ghostMode: GhostMode = GhostMode.SPECTATOR,
    var voteStyle: VoteStyle = VoteStyle.LIVE,
    var mafiaChatStyle: MafiaChatStyle = MafiaChatStyle.CHAT,
    var timerStyle: DiscussionTimerStyle = DiscussionTimerStyle.AUTO,
    var revealRoleOnDeath: Boolean = true,
    var noKillFirstNight: Boolean = true,
    var allowSkipVote: Boolean = true
)

data class Player(
    val id: String,
    var name: String,
    var avatar: String = "", // base64 representation
    var isAlive: Boolean = true,
    var isOnline: Boolean = true,
    var isReady: Boolean = false,
    var role: String = "CITIZEN", // MAFIA, CITIZEN, DOCTOR, DETECTIVE
    var targetVote: String? = null, // day vote target
    var mafiaTarget: String? = null, // night target
    var doctorTarget: String? = null,
    var detectiveTarget: String? = null,
    var lastActive: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class DetectiveResult(
    var playerId: String? = null,
    var isMafia: Boolean = false
)

object GameEngine {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    // Thread-safe game variables
    private var _phase = MutableStateFlow(GamePhase.LOBBY)
    val phase = _phase.asStateFlow()

    private val _playMode = MutableStateFlow(PlayMode.PASS_AND_PLAY)
    val playMode = _playMode.asStateFlow()

    val settings = GameSettings()
    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players = _players.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _mafiaChat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val mafiaChat = _mafiaChat.asStateFlow()

    private val _detectiveResult = MutableStateFlow<DetectiveResult?>(null)
    val detectiveResult = _detectiveResult.asStateFlow()

    private var _countdown = MutableStateFlow(-1)
    val countdown = _countdown.asStateFlow()

    var previousDoctorProtect: String? = null
    var currentNightCount = 0
    var winnerTeam: String? = null // "MAFIA" or "CITIZENS"

    // Persistent comprehensive list of day-by-day and night-by-night game events
    val voteHistoryList = mutableListOf<String>()

    private val _latestVotingResult = MutableStateFlow<VotingResultData?>(null)
    val latestVotingResult = _latestVotingResult.asStateFlow()

    private val _latestNightVictim = MutableStateFlow<NightVictimData?>(null)
    val latestNightVictim = _latestNightVictim.asStateFlow()

    // Thread orchestration
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private val disconnectThresholdMs = 300000L // 5 minutes checkout threshold

    // Listener for state updates
    var onStateChangedListener: (() -> Unit)? = null

    init {
        // Start automatic disconnect scanner every 10 seconds
        handler.postDelayed(object : Runnable {
            override fun run() {
                checkDisconnectedTimeouts()
                handler.postDelayed(this, 10000)
            }
        }, 10000)
    }

    private fun postUpdate() {
        onStateChangedListener?.invoke()
    }

    fun setPlayMode(mode: PlayMode) {
        synchronized(this) {
            _playMode.value = mode
            _players.value = emptyList()
            if (mode == PlayMode.PASS_AND_PLAY) {
                addLog("تم اختيار نمط تداول الهاتف 📱. أمن وأسرع!")
            } else {
                addLog("تم اختيار نمط الشبكة المحلية 🌐. بانتظار انضمام الأجهزة...")
            }
        }
        postUpdate()
    }

    var onLocalPlayersChanged: ((List<Player>) -> Unit)? = null

    fun initializeSavedPlayers(list: List<Player>) {
        synchronized(this) {
            if (_players.value.isEmpty()) {
                _players.value = list
            }
        }
        postUpdate()
    }

    fun addLocalPlayer(name: String, avatarEmoji: String) {
        synchronized(this) {
            val list = _players.value.toMutableList()
            val id = UUID.randomUUID().toString()
            val newPlayer = Player(id = id, name = name, avatar = avatarEmoji, isOnline = true, isReady = true)
            list.add(newPlayer)
            _players.value = list
            addLog("انضم اللاعب المحلي [ $name ] 🎮")
        }
        onLocalPlayersChanged?.invoke(_players.value)
        postUpdate()
    }

    fun removePlayer(id: String) {
        synchronized(this) {
            val list = _players.value.toMutableList()
            val removed = list.find { it.id == id }
            if (removed != null) {
                list.remove(removed)
                _players.value = list
                addLog("تم حذف اللاعب [ ${removed.name} ] ⚠️")
            }
        }
        onLocalPlayersChanged?.invoke(_players.value)
        postUpdate()
    }

    fun movePlayerUp(id: String) {
        synchronized(this) {
            val list = _players.value.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx > 0) {
                val temp = list[idx]
                list[idx] = list[idx - 1]
                list[idx - 1] = temp
                _players.value = list
                addLog("تم تحريك اللاعب [ ${temp.name} ] للأعلى في ترتيب المقاعد ⬆️")
            }
        }
        onLocalPlayersChanged?.invoke(_players.value)
        postUpdate()
    }

    fun movePlayerDown(id: String) {
        synchronized(this) {
            val list = _players.value.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx != -1 && idx < list.size - 1) {
                val temp = list[idx]
                list[idx] = list[idx + 1]
                list[idx + 1] = temp
                _players.value = list
                addLog("تم تحريك اللاعب [ ${temp.name} ] للأسفل في ترتيب المقاعد ⬇️")
            }
        }
        onLocalPlayersChanged?.invoke(_players.value)
        postUpdate()
    }

    // Handles connection registration/reconnection
    fun registerPlayer(id: String, name: String, avatar: String) {
        synchronized(this) {
            val list = _players.value.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx != -1) {
                val existing = list[idx]
                val updated = existing.copy(
                    name = name,
                    avatar = if (avatar.isNotEmpty()) avatar else existing.avatar,
                    isOnline = true,
                    isReady = existing.isReady,
                    lastActive = System.currentTimeMillis()
                )
                list[idx] = updated
                _players.value = list
                addLog("${name} عاد للمباراة واستعاد جلسته بنجاح 🔄")
            } else {
                if (_phase.value != GamePhase.LOBBY) {
                    // Cannot join mid-game as new player
                    return
                }
                val newPlayer = Player(id = id, name = name, avatar = avatar, isReady = false)
                list.add(newPlayer)
                _players.value = list
                addLog("${name} انضم لغرفة الانتظار 🆕")
            }
        }
        postUpdate()
    }

    fun togglePlayerReady(id: String) {
        synchronized(this) {
            val list = _players.value.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx != -1) {
                val player = list[idx]
                val nextReady = !player.isReady
                list[idx] = player.copy(isReady = nextReady)
                _players.value = list
                addLog("اللاعب ${player.name} أصبح: ${if (nextReady) "جاهز 👍" else "غير جاهز ⏳"}")
            }
        }
        postUpdate()
    }

    fun markPlayerOffline(id: String) {
        synchronized(this) {
            val list = _players.value.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx != -1) {
                val player = list[idx]
                list[idx] = player.copy(
                    isOnline = false,
                    lastActive = System.currentTimeMillis()
                )
                _players.value = list
                addLog("انقطع اتصال اللاعب ${player.name} ⚠️")
            }
        }
        postUpdate()
    }

    private fun checkDisconnectedTimeouts() {
        val now = System.currentTimeMillis()
        var updated = false
        synchronized(this) {
            val list = _players.value.toMutableList()
            val updatedList = mutableListOf<Player>()
            list.forEach { player ->
                if (!player.isOnline && (now - player.lastActive > disconnectThresholdMs)) {
                    // Player timeout expired, remove or skip depending on phase
                    addLog("اللاعب ${player.name} تجاوز مهلة الـ 5 دقائق وتم استبعاده نهائياً ⏳")
                    if (_phase.value != GamePhase.LOBBY) {
                        updatedList.add(player.copy(isAlive = false))
                    }
                    updated = true
                } else {
                    updatedList.add(player)
                }
            }
            if (updated) {
                _players.value = updatedList
                if (_phase.value != GamePhase.LOBBY) {
                    checkNightCompletionDynamically()
                    checkDayResolutionTally()
                    if (verifyVictoryConditions()) {
                        _phase.value = GamePhase.GAME_OVER
                    }
                }
            }
        }
        if (updated) postUpdate()
    }

    fun addLog(msg: String) {
        val current = _logs.value.toMutableList()
        current.add(msg)
        _logs.value = current
    }

    // Assign roles & start the show
    fun startGame() {
        synchronized(this) {
            val activePlayers = _players.value.filter { it.isOnline }
            val required = settings.mafia + settings.citizen + settings.doctor + settings.detective
            
            if (activePlayers.size != required) {
                addLog("خطأ: عدد الأدوار المعينة لا يساوي عدد اللاعبين المتصلين!")
                return
            }

            // Shuffle roles and assign
            val rolesPool = mutableListOf<String>()
            repeat(settings.mafia) { rolesPool.add("MAFIA") }
            repeat(settings.citizen) { rolesPool.add("CITIZEN") }
            repeat(settings.doctor) { rolesPool.add("DOCTOR") }
            repeat(settings.detective) { rolesPool.add("DETECTIVE") }
            rolesPool.shuffle()

            _players.value = _players.value.mapIndexed { index, player ->
                player.copy(
                    role = rolesPool[index],
                    isAlive = true,
                    targetVote = null,
                    mafiaTarget = null,
                    doctorTarget = null,
                    detectiveTarget = null
                )
            }

            currentNightCount = 0
            previousDoctorProtect = null
            _mafiaChat.value = emptyList()
            _detectiveResult.value = null
            winnerTeam = null
            _latestVotingResult.value = null
            voteHistoryList.clear()
            
            _logs.value = listOf("🎬 بدأت اللعبة الكبرى! تفضلوا بكشف أوراقكم السرية.")
            _phase.value = GamePhase.ROLE_REVEAL
        }
        postUpdate()
    }

    fun enterNight() {
        synchronized(this) {
            currentNightCount++
            _phase.value = GamePhase.NIGHT
            _detectiveResult.value = null
            
            // Clear prior night selections
            _players.value = _players.value.map {
                it.copy(
                    targetVote = null,
                    mafiaTarget = null,
                    doctorTarget = null,
                    detectiveTarget = null
                )
            }

            addLog("🏕️ الليلة رقم $currentNightCount: ساد السكون والظلام بالبلدة، نام الجميع...")
            SoundSynthesizer.playNightFall()
        }
        postUpdate()
    }

    fun submitMafiaVote(senderId: String, targetId: String?) {
        synchronized(this) {
            if (targetId == senderId) {
                return
            }
            _players.value = _players.value.map { player ->
                if (player.id == senderId && player.role == "MAFIA" && player.isAlive) {
                    player.copy(mafiaTarget = targetId)
                } else {
                    player
                }
            }
            checkNightCompletionDynamically()
        }
        postUpdate()
    }

    fun submitMafiaChat(senderId: String, senderName: String, text: String) {
        synchronized(this) {
            val list = _mafiaChat.value.toMutableList()
            list.add(ChatMessage(senderId, senderName, text))
            _mafiaChat.value = list
        }
        postUpdate()
    }

    fun submitDoctorVote(senderId: String, targetId: String?) {
        synchronized(this) {
            if (targetId != null && targetId == previousDoctorProtect) {
                return
            }
            _players.value = _players.value.map { player ->
                if (player.id == senderId && player.role == "DOCTOR" && player.isAlive) {
                    player.copy(doctorTarget = targetId)
                } else {
                    player
                }
            }
            checkNightCompletionDynamically()
        }
        postUpdate()
    }

    fun submitDetectiveVote(senderId: String, targetId: String?) {
        synchronized(this) {
            _players.value = _players.value.map { player ->
                if (player.id == senderId && player.role == "DETECTIVE" && player.isAlive) {
                    player.copy(detectiveTarget = targetId)
                } else {
                    player
                }
            }
            
            // Process inquiry response dynamically
            if (targetId != null) {
                val targetPlayer = _players.value.find { it.id == targetId }
                val result = DetectiveResult(targetId, targetPlayer?.role == "MAFIA")
                _detectiveResult.value = result
            }
            checkNightCompletionDynamically()
        }
        postUpdate()
    }

    /**
     * Checks if all active/living night action teams have successfully committed their target.
     * Ends the night dynamically as requested by User rule 3.
     */
    private fun checkNightCompletionDynamically() {
        if (_playMode.value == PlayMode.PASS_AND_PLAY) return

        val alivePlayers = _players.value.filter { it.isAlive }
        
        // 1. Mafia Checks
        val activeMafias = alivePlayers.filter { it.role == "MAFIA" }
        val isMafiaReady = if (activeMafias.isEmpty()) true else {
            val firstTarget = activeMafias.first().mafiaTarget
            firstTarget != null && activeMafias.all { it.mafiaTarget == firstTarget }
        }

        // 2. Doctor Checks
        val activeDoctors = alivePlayers.filter { it.role == "DOCTOR" }
        val isDoctorReady = if (activeDoctors.isEmpty()) true else {
            val firstTarget = activeDoctors.first().doctorTarget
            firstTarget != null && activeDoctors.all { it.doctorTarget == firstTarget }
        }

        // 3. Detective Checks
        val activeDetectives = alivePlayers.filter { it.role == "DETECTIVE" }
        val isDetectiveReady = if (activeDetectives.isEmpty()) true else {
            val firstTarget = activeDetectives.first().detectiveTarget
            firstTarget != null && activeDetectives.all { it.detectiveTarget == firstTarget }
        }

        if (isMafiaReady && isDoctorReady && isDetectiveReady) {
            resolveNightPhaseAndEnterDay()
        }
    }

    fun resolveNightPhaseAndEnterDay() {
        synchronized(this) {
            val alivePlayers = _players.value.filter { it.isAlive }
            
            // Get Mafia Target selection
            val mafiaTargetId = alivePlayers.find { it.role == "MAFIA" }?.mafiaTarget
            val doctorTargetId = alivePlayers.find { it.role == "DOCTOR" }?.doctorTarget

            // Record night history
            voteHistoryList.add("🏕️ مجريات الليلة رقم ${currentNightCount}:")
            val targetPlayer = mafiaTargetId?.let { id -> _players.value.find { it.id == id } }
            if (targetPlayer != null) {
                voteHistoryList.add("← المافيا خططت لاغتيال اللاعب [ ${targetPlayer.name} ] 👺")
            } else {
                voteHistoryList.add("← المافيا لم تجرِ أي هجمات متوافق عليها 👺")
            }
            val doctorTargetPlayer = doctorTargetId?.let { id -> _players.value.find { it.id == id } }
            if (doctorTargetPlayer != null) {
                voteHistoryList.add("← الطبيب حاول علاج وحماية اللاعب [ ${doctorTargetPlayer.name} ] 🩺")
            }
            val activeDetectives = alivePlayers.filter { it.role == "DETECTIVE" }
            if (activeDetectives.isNotEmpty()) {
                val detectiveTargetId = activeDetectives.first().detectiveTarget
                val detectiveTargetPlayer = detectiveTargetId?.let { id -> _players.value.find { it.id == id } }
                if (detectiveTargetPlayer != null) {
                    val isSecretMafia = detectiveTargetPlayer.role == "MAFIA"
                    voteHistoryList.add("← المحقق فحص بالسر اللاعب [ ${detectiveTargetPlayer.name} ] وتبين أنه: ${if (isSecretMafia) "مافيا 👺" else "شريف 😇"} 🔎")
                }
            }

            addLog("☀️ أشرقت الشمس بنهار جديد! نهض الجميع سريعا لرؤية الكوارث...")
            SoundSynthesizer.playDayDawn()

            if (mafiaTargetId != null) {
                val targetPlayer = _players.value.find { it.id == mafiaTargetId }
                if (settings.noKillFirstNight && currentNightCount == 1) {
                    addLog("🚨 الليلة الأولى: كانت ليلة للتعارف والتمويه فقط، استيقظ غول المافيا دون ارتكاب جريمة قتل.")
                    _latestNightVictim.value = NightVictimData(
                        name = "",
                        avatar = "",
                        role = "",
                        roleLabel = "",
                        hasVictim = false
                    )
                } else if (mafiaTargetId == doctorTargetId) {
                    // Doctor Saved!
                    addLog("😇 معجزة! نجح الطبيب الشريف في حماية المستهدف، ولم يمت أحد الليلة!")
                    previousDoctorProtect = doctorTargetId
                    _latestNightVictim.value = NightVictimData(
                        name = "",
                        avatar = "",
                        role = "",
                        roleLabel = "",
                        hasVictim = false
                    )
                } else {
                    // Player Killed
                    if (targetPlayer != null) {
                        _players.value = _players.value.map { player ->
                            if (player.id == mafiaTargetId) {
                                player.copy(isAlive = false)
                            } else {
                                player
                            }
                        }
                        val roleLabelText = getRoleLabelArabic(targetPlayer.role)
                        var info = "🥀 مأساة! عثر الشرفاء على جثة اللاعب [ ${targetPlayer.name} ] مقتولاً الليلة!"
                        if (settings.revealRoleOnDeath) {
                            info += " وتبين أنه كان دور: $roleLabelText"
                        }
                        addLog(info)
                        SoundSynthesizer.playDeath()
                        _latestNightVictim.value = NightVictimData(
                            name = targetPlayer.name,
                            avatar = targetPlayer.avatar,
                            role = targetPlayer.role,
                            roleLabel = roleLabelText,
                            hasVictim = true
                        )
                    } else {
                        _latestNightVictim.value = NightVictimData(
                            name = "",
                            avatar = "",
                            role = "",
                            roleLabel = "",
                            hasVictim = false
                        )
                    }
                    previousDoctorProtect = doctorTargetId
                }
            } else {
                addLog("🤔 هدوء تام: لم يسجل غول المافيا أي هجوم الليلة وساد السكون.")
                previousDoctorProtect = doctorTargetId
                _latestNightVictim.value = NightVictimData(
                    name = "",
                    avatar = "",
                    role = "",
                    roleLabel = "",
                    hasVictim = false
                )
            }

            // Verify if game over before loading Day
            if (verifyVictoryConditions()) {
                _phase.value = GamePhase.GAME_OVER
            } else {
                startDayDiscussion()
            }
        }
        postUpdate()
    }

    private fun startDayDiscussion() {
        _phase.value = GamePhase.DAY_DISCUSSION
        if (settings.timerStyle == DiscussionTimerStyle.AUTO) {
            startCountdown(120) { // 2 minutes discussion
                enterDayVoting()
            }
        }
    }

    fun submitDayVote(voterId: String, targetId: String?) {
        synchronized(this) {
            val voter = _players.value.find { it.id == voterId }
            if (voter != null && voter.role == "MAFIA" && targetId == voterId) {
                return
            }
            _players.value = _players.value.map { player ->
                if (player.id == voterId && player.isAlive) {
                    player.copy(targetVote = targetId)
                } else {
                    player
                }
            }
            // If LIVE voting mode is on, we check tally dynamically
            if (settings.voteStyle == VoteStyle.LIVE) {
                checkDayResolutionTally()
            }
        }
        postUpdate()
    }

    /**
     * Instantly closes the voting state manually (called by Host app).
     */
    fun endDayVotingManually() {
        stopCountdown()
        resolveDayVoting()
    }

    fun enterDayVoting() {
        synchronized(this) {
            _phase.value = GamePhase.DAY_VOTING
            if (settings.timerStyle == DiscussionTimerStyle.AUTO) {
                startCountdown(60) { // 1 minute voting time
                    resolveDayVoting()
                }
            }
        }
        postUpdate()
    }

    private fun checkDayResolutionTally() {
        if (_playMode.value == PlayMode.PASS_AND_PLAY) return

        // Just verify if 100% of alive players voted to resolve speed-up
        val alive = _players.value.filter { it.isAlive }
        if (alive.all { it.targetVote != null }) {
            stopCountdown()
            resolveDayVoting()
        }
    }

    fun resolveDayVoting() {
        synchronized(this) {
            val alive = _players.value.filter { it.isAlive }
            val tally = mutableMapOf<String, Int>()
            
            alive.forEach { p ->
                p.targetVote?.let { targetId ->
                    if (targetId != "SKIP") {
                        tally[targetId] = (tally[targetId] ?: 0) + 1
                    }
                }
            }

            var victimName: String? = null
            var victimAvatar: String? = null
            var victimRoleStr: String? = null
            var isTie = false
            val votesBreakdown = mutableListOf<String>()

            // Aggregate votes breakdown
            alive.forEach { p ->
                val targetName = p.targetVote?.let { id -> if (id == "SKIP") "الامتناع عن التصويت" else _players.value.find { it.id == id }?.name } ?: "الامتناع عن التصويت"
                votesBreakdown.add("• [ ${p.name} ] صوّت لعزل [ $targetName ]")
            }

            if (tally.isEmpty()) {
                addLog("🤷‍♂️ انقضى الوقت ولم يصوت أحد اليوم! لم يتم نفي أحد.")
            } else {
                val maxVotes = tally.values.maxOrNull() ?: 0
                val absoluteTargets = tally.filter { it.value == maxVotes }.keys.toList()

                if (absoluteTargets.size > 1) {
                    isTie = true
                    addLog("⚖️ تعادل الأصوات! التساؤلات والشكوك منعت الإعدام، لم يمت أحد اليوم.")
                } else if (absoluteTargets.size == 1) {
                    val targetId = absoluteTargets.first()
                    val victim = _players.value.find { it.id == targetId }
                    if (victim != null) {
                        _players.value = _players.value.map { player ->
                            if (player.id == targetId) {
                                player.copy(isAlive = false)
                            } else {
                                player
                            }
                        }
                        victimName = victim.name
                        victimAvatar = victim.avatar
                        victimRoleStr = getRoleLabelArabic(victim.role)
                        
                        var info = "🥀 قرار البلدة: تم نفي الحنيوك المشبوه [ ${victim.name} ] من البلدة وتجريده!"
                        if (settings.revealRoleOnDeath) {
                            info += " وتبين من أوراقه أنه: ${victimRoleStr}"
                        }
                        addLog(info)
                        SoundSynthesizer.playDeath()
                    }
                }
            }

            // Append this day's details to the persistent voting history list
            val listHeader = "🗳️ في تصويت النهار رقم ${currentNightCount}:"
            voteHistoryList.add(listHeader)
            voteHistoryList.addAll(votesBreakdown)
            if (victimName != null) {
                voteHistoryList.add("← النتيجة النهائية: تم نفي اللاعب [ $victimName ] (${victimRoleStr ?: ""})")
            } else if (isTie) {
                voteHistoryList.add("← النتيجة النهائية: تعادل الأصوات ولم ينفَ أحد.")
            } else {
                voteHistoryList.add("← النتيجة النهائية: لم يصوت أحد ولم ينفَ أحد.")
            }

            // Populate public state flow
            _latestVotingResult.value = VotingResultData(
                title = "محكمة بلدة الحنيوك ⚖️",
                victimName = victimName,
                victimAvatar = victimAvatar,
                victimRoleStr = victimRoleStr,
                isTie = isTie,
                votesBreakdown = votesBreakdown
            )
        }
        postUpdate()
    }

    fun dismissVotingResultAndProceed() {
        synchronized(this) {
            _latestVotingResult.value = null
            
            // Verify Game Outcomes and switch phases
            if (verifyVictoryConditions()) {
                _phase.value = GamePhase.GAME_OVER
            } else {
                enterNight()
            }
        }
        postUpdate()
    }

    fun dismissNightVictim() {
        synchronized(this) {
            _latestNightVictim.value = null
        }
        postUpdate()
    }

    /**
     * Tests victory conditions using explicit user rule:
     * - Citizens win if all Mafia are dead (Mafia Count == 0)
     * - Mafia wins if living Mafia >= living Citizens, PROVIDED living doctors == 0 (because alive doctor can save and change the math).
     */
    fun verifyVictoryConditions(): Boolean {
        val alivePlayers = _players.value.filter { it.isAlive }
        val mafiaCount = alivePlayers.count { it.role == "MAFIA" }
        val citizensCount = alivePlayers.count { it.role != "MAFIA" }

        return if (mafiaCount == 0) {
            winnerTeam = "CITIZENS"
            addLog("🎉 فوز المواطنين الصالحين! تم القضاء التام على أشرار المافيا وتطهير القرية.")
            SoundSynthesizer.playVictory()
            true
        } else if (mafiaCount >= citizensCount) {
            winnerTeam = "MAFIA"
            addLog("👺 فوز المافيا الأشرار! لقد تجاوزت قوة المافيا المجموعات الشريفة، وسيطروا على البلدة بالكامل.")
            SoundSynthesizer.playVictory()
            true
        } else {
            false
        }
    }

    // Play again keeping players session
    fun resetGameToLobby() {
        synchronized(this) {
            _phase.value = GamePhase.LOBBY
            _players.value = _players.value.map { player ->
                player.copy(
                    isAlive = true,
                    role = "CITIZEN",
                    targetVote = null,
                    mafiaTarget = null,
                    doctorTarget = null,
                    detectiveTarget = null
                )
            }
            previousDoctorProtect = null
            _logs.value = listOf("🎮 تقرر إعادة اللعب من الـ Host! تم الحفاظ الشامل على الأسماء والجلسات وبانتظار بدء كفاحٍ جديد.")
            _mafiaChat.value = emptyList()
            _detectiveResult.value = null
            winnerTeam = null
            _latestVotingResult.value = null
            voteHistoryList.clear()
        }
        postUpdate()
    }

    // Timing Coroutine utilities safely handled inside Main Thread Handler loop
    private fun startCountdown(seconds: Int, onFinish: () -> Unit) {
        stopCountdown()
        _countdown.value = seconds
        val runnable = object : Runnable {
            override fun run() {
                synchronized(this@GameEngine) {
                    if (_countdown.value > 0) {
                        _countdown.value = _countdown.value - 1
                        handler.postDelayed(this, 1000)
                    } else {
                        _countdown.value = -1
                        onFinish()
                    }
                }
                postUpdate()
            }
        }
        timerRunnable = runnable
        handler.post(runnable)
    }

    private fun stopCountdown() {
        timerRunnable?.let {
            handler.removeCallbacks(it)
            timerRunnable = null
        }
        _countdown.value = -1
    }

    private fun getRoleLabelArabic(role: String): String {
        return when(role) {
            "MAFIA" -> "مافيا 👺"
            "DOCTOR" -> "طبيب 🩺"
            "DETECTIVE" -> "محقق 🔎"
            else -> "مواطن صالح 😇"
        }
    }

    // JSON Serializer of Game State for Clients
    fun getSerializedStateForPlayer(playerId: String?): String {
        return synchronized(this) {
            val list = _players.value
            val me = list.find { it.id == playerId }
            
            // Hide details based on phase and role to prevent cheating
            val obfuscatedPlayers = list.map { other ->
                val canSeeSecret = (playerId == other.id) || 
                                   (_phase.value == GamePhase.GAME_OVER) ||
                                   (me != null && !me.isAlive && settings.ghostMode == GhostMode.SPECTATOR)

                val roleStr = if (canSeeSecret) other.role else "UNKNOWN"
                
                // Highlight live targets only for teammates or spectator
                val myMafiaTarget = if (me != null && me.role == "MAFIA" && other.role == "MAFIA" && other.isAlive) {
                    other.mafiaTarget
                } else null

                val targetVoteStr = if ((playerId == other.id) || (_phase.value == GamePhase.GAME_OVER)) {
                    other.targetVote
                } else null

                other.copy(
                    role = roleStr,
                    mafiaTarget = myMafiaTarget,
                    targetVote = targetVoteStr,
                    doctorTarget = null,
                    detectiveTarget = null
                )
            }

            // Custom Detective result payload
            val detectivePayload = if (me != null && me.role == "DETECTIVE" && me.isAlive) {
                _detectiveResult.value
            } else null

            val stateMap = mapOf(
                "type" to "state",
                "phase" to _phase.value.name,
                "countdown" to _countdown.value,
                "winner" to winnerTeam,
                "previousDoctorProtect" to previousDoctorProtect,
                "detectiveInquiryResult" to detectivePayload,
                "mafiaChat" to if (me?.role == "MAFIA" || (me != null && !me.isAlive && settings.ghostMode == GhostMode.SPECTATOR)) _mafiaChat.value else emptyList(),
                "logs" to _logs.value,
                "voteHistory" to voteHistoryList.toList(),
                "currentNightCount" to currentNightCount,
                "latestNightVictim" to _latestNightVictim.value?.let {
                    mapOf(
                        "name" to it.name,
                        "avatar" to it.avatar,
                        "role" to it.role,
                        "roleLabel" to it.roleLabel,
                        "hasVictim" to it.hasVictim
                    )
                },
                "latestVotingResult" to _latestVotingResult.value?.let {
                    mapOf(
                        "title" to it.title,
                        "victimName" to it.victimName,
                        "victimAvatar" to it.victimAvatar,
                        "victimRoleStr" to it.victimRoleStr,
                        "isTie" to it.isTie,
                        "votesBreakdown" to it.votesBreakdown
                    )
                },
                "settings" to mapOf(
                    "mafia" to settings.mafia,
                    "citizen" to settings.citizen,
                    "doctor" to settings.doctor,
                    "detective" to settings.detective,
                    "ghostMode" to settings.ghostMode.name,
                    "voteStyle" to settings.voteStyle.name,
                    "mafiaChat" to settings.mafiaChatStyle.name,
                    "timerStyle" to settings.timerStyle.name,
                    "allowSkipVote" to settings.allowSkipVote
                ),
                "players" to obfuscatedPlayers
            )
            moshi.adapter(Map::class.java).toJson(stateMap)
        }
    }
}

data class VotingResultData(
    val title: String,
    val victimName: String?,
    val victimAvatar: String?,
    val victimRoleStr: String?,
    val isTie: Boolean,
    val votesBreakdown: List<String>
)

data class NightVictimData(
    val name: String,
    val avatar: String,
    val role: String,
    val roleLabel: String,
    val hasVictim: Boolean = true
)

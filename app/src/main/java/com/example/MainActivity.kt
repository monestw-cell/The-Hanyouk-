package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView

fun uriToBase64(contentResolver: android.content.ContentResolver, uri: android.net.Uri): String? {
    return try {
        val targetDim = 250 // Highly optimized size perfectly matching standard avatars
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        val srcWidth = options.outWidth
        val srcHeight = options.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return null

        var inSampleSize = 1
        val maxDim = Math.max(srcWidth, srcHeight)
        if (maxDim > targetDim) {
            inSampleSize = maxDim / targetDim
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        
        val decodedBitmap = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val (finalW, finalH) = if (decodedBitmap.width > decodedBitmap.height) {
            val scale = targetDim.toFloat() / decodedBitmap.width.toFloat()
            ((decodedBitmap.width * scale).toInt().coerceAtLeast(1) to (decodedBitmap.height * scale).toInt().coerceAtLeast(1))
        } else {
            val scale = targetDim.toFloat() / decodedBitmap.height.toFloat()
            ((decodedBitmap.width * scale).toInt().coerceAtLeast(1) to (decodedBitmap.height * scale).toInt().coerceAtLeast(1))
        }

        val scaledBitmap = Bitmap.createScaledBitmap(decodedBitmap, finalW, finalH, true)
        val out = java.io.ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, out) // 70% quality utilizes significantly less data size over sockets and databases
        val bytes = out.toByteArray()
        
        if (scaledBitmap != decodedBitmap) {
            scaledBitmap.recycle()
        }
        decodedBitmap.recycle()
        
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: Throwable) {
        e.printStackTrace()
        null
    }
}

class MainActivity : ComponentActivity() {

    private var server: KtorServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = com.example.AppDatabase.getDatabase(applicationContext)
        val dao = db.savedPlayerDao()

        // Load saved players on launch
        lifecycleScope.launch(Dispatchers.IO) {
            val savedList = dao.getAllPlayers()
            if (savedList.isNotEmpty()) {
                val list = savedList.map {
                    Player(
                        id = it.id,
                        name = it.name,
                        avatar = it.avatar,
                        isOnline = true,
                        isReady = true
                    )
                }
                withContext(Dispatchers.Main) {
                    GameEngine.initializeSavedPlayers(list)
                }
            }
        }

        // Setup dynamic callback mapping memory to DB
        GameEngine.onLocalPlayersChanged = { list ->
            lifecycleScope.launch(Dispatchers.IO) {
                dao.deleteAllPlayers()
                val entities = list.mapIndexed { idx, p ->
                    SavedPlayer(id = p.id, name = p.name, avatar = p.avatar, seatOrder = idx)
                }
                dao.insertAll(entities)
            }
        }

        // Start background server asynchronously
        server = KtorServer(applicationContext, port = 8080)
        server?.start()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF000000) // Pure dark mode background
                ) {
                    var localIP by remember { mutableStateOf("127.0.0.1") }
                    LaunchedEffect(Unit) {
                        try {
                            val ip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                NetworkUtils.getLocalIPAddress()
                            }
                            localIP = ip
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                    HostDashboardScreen(localIP)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        GameEngine.onLocalPlayersChanged = null
        server?.stop()
        server = null
    }
}

// Composable Decoded Base64 Preview image helper for dynamic player selfie displays
@Composable
fun Base64Image(base64Str: String, modifier: Modifier = Modifier) {
    val displayStr = base64Str.ifEmpty { "👤" }
    if (displayStr.length < 15) {
        Box(
            modifier = modifier.background(Color(0xFF2C2C2E), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(displayStr, fontSize = 20.sp)
        }
        return
    }

    var bitmap by remember(displayStr) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(displayStr) {
        withContext(Dispatchers.IO) {
            try {
                val pureBase64 = if (displayStr.startsWith("data:image")) {
                    displayStr.substringAfter(",")
                } else {
                    displayStr
                }
                val decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT)
                val decoded = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                withContext(Dispatchers.Main) {
                    bitmap = decoded
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = "صورة اللاعب",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        // Fallback placeholder image while decoding
        Box(
            modifier = modifier.background(Color(0xFF18181B)),
            contentAlignment = Alignment.Center
        ) {
            Text("👤", fontSize = 24.sp)
        }
    }
}

@Composable
fun HostDashboardScreen(ipAddress: String) {
    // Collect states reactively from the single global GameEngine state flow
    val phase by GameEngine.phase.collectAsState()
    val playMode by GameEngine.playMode.collectAsState()
    val players by GameEngine.players.collectAsState()
    val logs by GameEngine.logs.collectAsState()
    val countdown by GameEngine.countdown.collectAsState()
    val latestVotingResult by GameEngine.latestVotingResult.collectAsState()
    val latestNightVictim by GameEngine.latestNightVictim.collectAsState()
    val settings = GameEngine.settings

    // Local Pass & Play Input States
    var localPlayerName by remember { mutableStateOf("") }
    var selectedEmoji by remember { mutableStateOf("👤") }
    val emojiAvatars = listOf("👤", "👺", "🥸", "🤡", "🦊", "🐯", "🦁", "🦖", "🦄", "👽", "🤖", "👻", "🧙‍♂️", "👮", "🧑‍⚕️", "🕵️")

    val context = androidx.compose.ui.platform.LocalContext.current
    var uploadedAvatarBase64 by remember { mutableStateOf<String?>(null) }
    var showReorderPlayer by remember { mutableStateOf<Player?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val base64 = uriToBase64(context.contentResolver, uri)
                if (base64 != null) {
                    withContext(Dispatchers.Main) {
                        uploadedAvatarBase64 = base64
                    }
                }
            }
        }
    }

    // Local configuration stats
    var mafiaCount by remember { mutableStateOf(settings.mafia) }
    var citizenCount by remember { mutableStateOf(settings.citizen) }
    var doctorCount by remember { mutableStateOf(settings.doctor) }
    var detectiveCount by remember { mutableStateOf(settings.detective) }

    var hostModeState by remember { mutableStateOf(settings.hostMode) }
    var ghostModeState by remember { mutableStateOf(settings.ghostMode) }
    var voteStyleState by remember { mutableStateOf(settings.voteStyle) }
    var mafiaChatStyleState by remember { mutableStateOf(settings.mafiaChatStyle) }
    var timerStyleState by remember { mutableStateOf(settings.timerStyle) }
    var revealRoleOnDeathState by remember { mutableStateOf(settings.revealRoleOnDeath) }
    var noKillFirstNightState by remember { mutableStateOf(settings.noKillFirstNight) }
    var allowSkipVoteState by remember { mutableStateOf(settings.allowSkipVote) }
    var hostActiveTab by remember { mutableStateOf(0) } // 0 = Server panel, 1 = Player WebView

    var isHostRoleRevealed by remember { mutableStateOf(false) }
    var hostLocalVoteTargetId by remember { mutableStateOf<String?>(null) }
    var hostLocalHasSkippedVote by remember { mutableStateOf(false) }
    var hostNightTargetId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(phase) {
        isHostRoleRevealed = false
        hostLocalVoteTargetId = null
        hostLocalHasSkippedVote = false
        hostNightTargetId = null
    }

    // Synchronize settings changes
    LaunchedEffect(mafiaCount, citizenCount, doctorCount, detectiveCount, hostModeState, ghostModeState, voteStyleState, mafiaChatStyleState, timerStyleState, revealRoleOnDeathState, noKillFirstNightState, allowSkipVoteState) {
        settings.mafia = mafiaCount
        settings.citizen = citizenCount
        settings.doctor = doctorCount
        settings.detective = detectiveCount
        settings.hostMode = hostModeState
        settings.ghostMode = ghostModeState
        settings.voteStyle = voteStyleState
        settings.mafiaChatStyle = mafiaChatStyleState
        settings.timerStyle = timerStyleState
        settings.revealRoleOnDeath = revealRoleOnDeathState
        settings.noKillFirstNight = noKillFirstNightState
        settings.allowSkipVote = allowSkipVoteState
    }

    LaunchedEffect(players.size) {
        val totalPlayers = players.size
        if (totalPlayers > 0) {
            while (mafiaCount + citizenCount + doctorCount + detectiveCount > totalPlayers) {
                if (citizenCount > 0) citizenCount--
                else if (detectiveCount > 0) detectiveCount--
                else if (doctorCount > 0) doctorCount--
                else if (mafiaCount > 0) mafiaCount--
                else break
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF07070A),
        topBar = {
            if (phase == GamePhase.LOBBY) {
                if (playMode == PlayMode.NETWORK_SERVER) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color(0xFF12121A), Color(0xFF07070A))))
                            .padding(top = 40.dp, bottom = 12.dp, start = 16.dp, end = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "الحنيوك",
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Al-Hanyouk",
                                    color = Color.LightGray.copy(alpha = 0.8f),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Card(
                                modifier = Modifier.size(85.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                                border = BorderStroke(1.5.dp, Color(0xFF30D158)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                val url = "http://$ipAddress:8080"
                                val qrBitmap = remember(ipAddress) {
                                    NetworkUtils.generateQRCode(url, 200)
                                }
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Image(
                                        bitmap = qrBitmap.asImageBitmap(),
                                        contentDescription = "امسح للانضمام",
                                        modifier = Modifier.size(52.dp)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "امسح للانضمام 📲",
                                        color = Color.LightGray,
                                        fontSize = 7.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0E0E14)),
                            border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color(0xFF32D74B), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "رابط انضمام الأصدقاء محلياً:",
                                        color = Color.LightGray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = "http://$ipAddress:8080",
                                    color = Color(0xFFFFD700),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color(0xFF12121A), Color(0xFF07070A))))
                            .padding(top = 40.dp, bottom = 12.dp, start = 16.dp, end = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "الحنيوك",
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Al-Hanyouk",
                                    color = Color.LightGray.copy(alpha = 0.8f),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFFF453A).copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                                    .border(1.dp, Color(0xFFFF453A), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "لعب محلي 📱 Offline",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else if (phase != GamePhase.GAME_OVER) {
                // Compact active header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xFF12121A), Color(0xFF07070A))))
                        .padding(top = 40.dp, bottom = 12.dp, start = 16.dp, end = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "الحنيوك ⚙️",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black
                            )
                            if (playMode == PlayMode.NETWORK_SERVER) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "السيرفر النشط: http://$ipAddress:8080",
                                    color = Color(0xFFFFD700),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF30D158).copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                                .border(1.dp, Color(0xFF30D158), RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (playMode == PlayMode.NETWORK_SERVER) "لعب شبكي 🌐" else "لعب محلي 📱",
                                color = Color(0xFF30D158),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .then(
                    if (!(phase == GamePhase.LOBBY || playMode == PlayMode.NETWORK_SERVER)) {
                        Modifier.statusBarsPadding()
                    } else {
                        Modifier
                    }
                )
                .fillMaxSize()
        ) {
            val hostPlayer = remember(players) { players.find { it.id == "HOST_PLAYER_ID" } }
            if (phase != GamePhase.LOBBY && hostPlayer != null) {
                HostIntegratedActiveScreen(
                    hostPlayer = hostPlayer,
                    players = players,
                    phase = phase,
                    logs = logs,
                    allowSkipVoteState = allowSkipVoteState
                )
            } else if (false) {
                // Fully Native Player Screen for the Host
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    val hostPlayer = players.find { it.id == "HOST_PLAYER_ID" }
                    
                    if (hostPlayer == null) {
                        // Show Host registration screen beautifully
                        Text(
                            text = "تسجيل هوية اللاعب لمضيف الجلسة 👥🎮",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = "بصفتك المضيف (السيرفر)، يمكنك أيضاً الانضمام والتنافس كلاعب حقيقي من نفس الجوال! اختر اسماً وصورة رمزية للمشاركة:",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF15151A)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF3B30).copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                OutlinedTextField(
                                    value = localPlayerName,
                                    onValueChange = { localPlayerName = it },
                                    label = { Text("اسم الشهرة", color = Color.Gray, fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFFFF3B30),
                                        unfocusedBorderColor = Color(0xFF2C2C2E),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0C0C0C), RoundedCornerShape(8.dp))
                                        .padding(6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("أيقونتي:", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                                    emojiAvatars.take(6).forEach { emoji ->
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(
                                                    if (selectedEmoji == emoji) Color(0xFFFF3B30).copy(alpha = 0.3f) else Color.Transparent,
                                                    CircleShape
                                                )
                                                .border(
                                                    1.dp,
                                                    if (selectedEmoji == emoji) Color(0xFFFF3B30) else Color.Transparent,
                                                    CircleShape
                                                )
                                                .clickable { selectedEmoji = emoji },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(emoji, fontSize = 15.sp)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "أو اختر صورة من جهازك 🖼️:",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { imagePickerLauncher.launch("image/*") },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF453A)),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color(0xFFFF453A))
                                    ) {
                                        Text("رفع صورة من الملفات 📂", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (uploadedAvatarBase64 != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Base64Image(
                                                base64Str = uploadedAvatarBase64 ?: "",
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .border(1.dp, Color(0xFFFF453A), CircleShape)
                                            )
                                            Text(
                                                text = "جاهز! ✅",
                                                color = Color(0xFF34C759),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            IconButton(
                                                onClick = { uploadedAvatarBase64 = null },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Text("❌", color = Color.White, fontSize = 10.sp)
                                            }
                                        }
                                    } else {
                                        Text("لم يتم تحديد صورة", color = Color.Gray, fontSize = 10.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        if (localPlayerName.isNotBlank()) {
                                            val avatar = uploadedAvatarBase64 ?: selectedEmoji
                                            GameEngine.registerPlayer("HOST_PLAYER_ID", localPlayerName.trim(), avatar)
                                            localPlayerName = ""
                                            uploadedAvatarBase64 = null
                                        }
                                    },
                                    enabled = localPlayerName.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFF3B30),
                                        disabledContainerColor = Color(0xFF2C2C2E),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().height(42.dp)
                                ) {
                                    Text("تسجيل كلاعب في السيرفر المحلي 🎮", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // Host is registered, display player interactive screen natively
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF3B30).copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Base64Image(base64Str = hostPlayer.avatar.ifEmpty { "👤" }, modifier = Modifier.size(36.dp).clip(CircleShape).border(1.5.dp, Color(0xFF1F1F2C), CircleShape))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "شاشتي كلاعب (${hostPlayer.name})",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (!hostPlayer.isAlive) {
                                            Text(text = "🥀 ميت", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        } else {
                                            Text(text = "💚 حيّ", color = Color.Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Button(
                                            onClick = {
                                                GameEngine.removePlayer("HOST_PLAYER_ID")
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C1010)),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text("انسحاب 🗑️", color = Color.White, fontSize = 10.sp)
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(10.dp))
                                
                                if (phase == GamePhase.LOBBY) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF040406)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(10.dp))
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text("سجلت بنجاح في ردهة الانتظار ⏳", color = Color.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "بانتظار بدء الجيم من لوحة تحكم السيرفر من قبل مشرف الغرفة...",
                                                color = Color.LightGray,
                                                fontSize = 11.sp,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            val isReady = hostPlayer.isReady
                                            Button(
                                                onClick = { GameEngine.togglePlayerReady("HOST_PLAYER_ID") },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isReady) Color(0xFF30D158) else Color(0xFFFF3B30)
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth().height(38.dp)
                                            ) {
                                                Text(
                                                    text = if (isReady) "أنت مستعد! جاهز ✅" else "اضغط لتأكيد الجاهزية ⏳",
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                } else if (phase == GamePhase.ROLE_REVEAL) {
                                    if (!isHostRoleRevealed) {
                                        Button(
                                            onClick = { isHostRoleRevealed = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth().height(38.dp)
                                        ) {
                                            Text("كشف دوري السري 🫣", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        val label = getRoleLabelArabic(hostPlayer.role)
                                        val desc = when (hostPlayer.role) {
                                            "MAFIA" -> "أنت المافيا الشرير! تصفية الشرفاء بالليل والتضليل بالنهار... 👺"
                                            "DOCTOR" -> "أنت الطبيب المنقذ! اعطِ مصل الحماية لمواطن كل ليلة... 🩺"
                                            "DETECTIVE" -> "أنت المحقق الخارق! استجوب هوية مشتبه به كل ليلة... 🔎"
                                            else -> "أنت مواطن شريف وصالح! تصديق الحدس والتصويت بالنهار... 😇"
                                        }
                                        val roleCol = when (hostPlayer.role) {
                                            "MAFIA" -> Color(0xFFFF453A)
                                            "DOCTOR" -> Color(0xFF34C759)
                                            "DETECTIVE" -> Color(0xFF0A84FF)
                                            else -> Color.White
                                        }
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF040406)),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(10.dp))
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(text = "دورك السري هو:", color = Color.Gray, fontSize = 11.sp)
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(text = label, color = roleCol, fontSize = 18.sp, fontWeight = FontWeight.Black)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(text = desc, color = Color.LightGray, fontSize = 11.sp, textAlign = TextAlign.Center)
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Button(
                                                    onClick = { isHostRoleRevealed = false },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)),
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.height(28.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                                ) {
                                                    Text("إخفاء دوري 🤫", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                } else if (phase == GamePhase.NIGHT) {
                                    if (!hostPlayer.isAlive) {
                                        Text("أنت ميت! تفضل بمتابعة اللعبة ومشاهدتها بصمت... 🌌", color = Color.Gray, fontSize = 11.sp)
                                    } else {
                                        when (hostPlayer.role) {
                                            "MAFIA" -> {
                                                val candidateVictims = players.filter { it.isAlive && it.role != "MAFIA" }
                                                Text("اختر مواطناً لتصفيته الليلة 👺:", color = Color.LightGray, fontSize = 11.sp)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                
                                                candidateVictims.forEach { candidate ->
                                                    val isSelected = hostNightTargetId == candidate.id
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 2.dp)
                                                            .clickable { hostNightTargetId = candidate.id }
                                                            .border(1.dp, if (isSelected) Color(0xFFFF453A) else Color.Transparent, RoundedCornerShape(6.dp)),
                                                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFFFF3B30).copy(alpha = 0.12f) else Color(0xFF040406))
                                                    ) {
                                                        Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            Text(candidate.avatar.ifEmpty { "👤" }, fontSize = 16.sp)
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Text(candidate.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                val isMafiaVoted = hostPlayer.mafiaTarget != null
                                                Button(
                                                    onClick = { GameEngine.submitMafiaVote(hostPlayer.id, hostNightTargetId) },
                                                    enabled = hostNightTargetId != null,
                                                    colors = ButtonDefaults.buttonColors(containerColor = if (isMafiaVoted) Color.Gray else Color(0xFFFF453A)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.fillMaxWidth().height(36.dp)
                                                ) {
                                                    Text(if (isMafiaVoted) "تم تأكيد طلب التصفية نهاراً ⌛" else "تأكيد التصفية 🩸", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            "DOCTOR" -> {
                                                val candidateHeals = players.filter { it.isAlive && it.id != GameEngine.previousDoctorProtect }
                                                Text("اختر مواطناً لحمايته الليلة 🩺:", color = Color.LightGray, fontSize = 11.sp)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                
                                                candidateHeals.forEach { candidate ->
                                                    val isSelected = hostNightTargetId == candidate.id
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 2.dp)
                                                            .clickable { hostNightTargetId = candidate.id }
                                                            .border(1.dp, if (isSelected) Color(0xFF34C759) else Color.Transparent, RoundedCornerShape(6.dp)),
                                                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF34C759).copy(alpha = 0.12f) else Color(0xFF040406))
                                                    ) {
                                                        Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            Text(candidate.avatar.ifEmpty { "👤" }, fontSize = 16.sp)
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Text(candidate.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                val isDoctorVoted = hostPlayer.doctorTarget != null
                                                Button(
                                                    onClick = { GameEngine.submitDoctorVote(hostPlayer.id, hostNightTargetId) },
                                                    enabled = hostNightTargetId != null,
                                                    colors = ButtonDefaults.buttonColors(containerColor = if (isDoctorVoted) Color.Gray else Color(0xFF34C759)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.fillMaxWidth().height(36.dp)
                                                ) {
                                                    Text(if (isDoctorVoted) "تم إرسال ترياق العلاج ⌛" else "تأكيد ترياق الحماية 🧪", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            "DETECTIVE" -> {
                                                val candidateInquiries = players.filter { it.isAlive && it.id != hostPlayer.id }
                                                Text("اختر مشتبهاً لاستجوابه الليلة 🔎:", color = Color.LightGray, fontSize = 11.sp)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                
                                                candidateInquiries.forEach { candidate ->
                                                    val isSelected = hostNightTargetId == candidate.id
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 2.dp)
                                                            .clickable { hostNightTargetId = candidate.id }
                                                            .border(1.dp, if (isSelected) Color(0xFF0A84FF) else Color.Transparent, RoundedCornerShape(6.dp)),
                                                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF0A84FF).copy(alpha = 0.12f) else Color(0xFF040406))
                                                    ) {
                                                        Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            Text(candidate.avatar.ifEmpty { "👤" }, fontSize = 16.sp)
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Text(candidate.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                val detectiveResult by GameEngine.detectiveResult.collectAsState()
                                                if (detectiveResult != null && detectiveResult?.playerId == hostNightTargetId) {
                                                    val isTargetMafia = detectiveResult?.isMafia == true
                                                    Card(
                                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                                    ) {
                                                        Text(
                                                            text = "نتيجة الفحص 🔎: هذا اللاعب هو ${if (isTargetMafia) "مافيا شرير! 👺" else "شريف صالح! 😇"}",
                                                            color = if (isTargetMafia) Color.Red else Color.Green,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(6.dp)
                                                        )
                                                    }
                                                }
                                                val isDetectiveVoted = hostPlayer.detectiveTarget != null
                                                Button(
                                                    onClick = { GameEngine.submitDetectiveVote(hostPlayer.id, hostNightTargetId) },
                                                    enabled = hostNightTargetId != null,
                                                    colors = ButtonDefaults.buttonColors(containerColor = if (isDetectiveVoted) Color.Gray else Color(0xFF0A84FF)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.fillMaxWidth().height(36.dp)
                                                ) {
                                                    Text(if (isDetectiveVoted) "تم الفحص وسجلت الهوية ⌛" else "استجواب الهوية 🔎", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            else -> {
                                                Text("أنت مواطن شريف، نم هانئاً وصالحاً الليلة بأمان... 💤🌃", color = Color.LightGray, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                } else if (phase == GamePhase.DAY_DISCUSSION) {
                                    Text("نقاش في النهار! تحاور وتبادل التهم مع رفاقك لحماية بلدتكم... 🗣️☀️", color = Color.LightGray, fontSize = 12.sp)
                                } else if (phase == GamePhase.DAY_VOTING) {
                                    if (!hostPlayer.isAlive) {
                                        Text("أنت ميت! لا يحق لك التصويت بالنهار... 🥀", color = Color.Gray, fontSize = 11.sp)
                                    } else {
                                        val potentialSuspects = players.filter { it.isAlive && it.id != hostPlayer.id }
                                        Text("اختر المشتبه به لتصوت لنفيه من بلدتكم ⚖️:", color = Color.LightGray, fontSize = 11.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        
                                        if (allowSkipVoteState) {
                                            val isSkipChosen = hostLocalHasSkippedVote
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp)
                                                    .clickable { 
                                                        hostLocalVoteTargetId = "SKIP"
                                                        hostLocalHasSkippedVote = true 
                                                    }
                                                    .border(1.dp, if (isSkipChosen) Color.Green else Color.Transparent, RoundedCornerShape(6.dp)),
                                                colors = CardDefaults.cardColors(containerColor = if (isSkipChosen) Color.Green.copy(alpha = 0.12f) else Color(0xFF040406))
                                            ) {
                                                Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Text("🤷‍♂️", fontSize = 16.sp)
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text("الامتناع عن التصويت (Skip Vote)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        
                                        potentialSuspects.forEach { suspect ->
                                            val isChosen = hostLocalVoteTargetId == suspect.id && !hostLocalHasSkippedVote
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp)
                                                    .clickable { 
                                                        hostLocalVoteTargetId = suspect.id
                                                        hostLocalHasSkippedVote = false 
                                                    }
                                                    .border(1.dp, if (isChosen) Color(0xFFFF3B30) else Color.Transparent, RoundedCornerShape(6.dp)),
                                                colors = CardDefaults.cardColors(containerColor = if (isChosen) Color(0xFFFF3B30).copy(alpha = 0.12f) else Color(0xFF040406))
                                            ) {
                                                Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(suspect.avatar.ifEmpty { "👤" }, fontSize = 16.sp)
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(suspect.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val hasMadeDecision = hostLocalVoteTargetId != null || hostLocalHasSkippedVote
                                        val isDayVoted = hostPlayer.targetVote != null
                                        Button(
                                            onClick = { 
                                                GameEngine.submitDayVote("HOST_PLAYER_ID", if (hostLocalHasSkippedVote) "SKIP" else hostLocalVoteTargetId) 
                                            },
                                            enabled = hasMadeDecision && !isDayVoted,
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isDayVoted) Color.Gray else Color(0xFFFF3B30)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth().height(36.dp)
                                        ) {
                                            Text(if (isDayVoted) "تم إرسال صوتك بنجاح 🔒" else "إرسال التصويت وتأكيده 🗳️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (phase == GamePhase.LOBBY) {
                // Pre-game Setup Dashboard
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // PlayMode Segmented Selection Tab
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F0F14), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFF1F1F2C), RoundedCornerShape(16.dp))
                            .padding(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (playMode == PlayMode.PASS_AND_PLAY) Color(0xFFFF453A).copy(alpha = 0.15f) else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    if (playMode == PlayMode.PASS_AND_PLAY) Color(0xFFFF453A) else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { GameEngine.setPlayMode(PlayMode.PASS_AND_PLAY) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📱 تداول الجوال", color = if (playMode == PlayMode.PASS_AND_PLAY) Color.White else Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("تمرير هاتف واحد للجميع", color = if (playMode == PlayMode.PASS_AND_PLAY) Color.LightGray else Color.DarkGray, fontSize = 9.sp)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (playMode == PlayMode.NETWORK_SERVER) Color(0xFFFF453A).copy(alpha = 0.15f) else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    if (playMode == PlayMode.NETWORK_SERVER) Color(0xFFFF453A) else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { GameEngine.setPlayMode(PlayMode.NETWORK_SERVER) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🌐 شبكة محلية (سيرفر)", color = if (playMode == PlayMode.NETWORK_SERVER) Color.White else Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("الأصدقاء ينضمون بهواتفهم", color = if (playMode == PlayMode.NETWORK_SERVER) Color.LightGray else Color.DarkGray, fontSize = 9.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    if (playMode == PlayMode.PASS_AND_PLAY) {
                        // Pass & Play Setup Dashboard Row
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                            border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "أضف أصدقائك بأسماءهم 👥",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = localPlayerName,
                                    onValueChange = { localPlayerName = it },
                                    label = { Text("اسم اللاعب الجديد", color = Color.Gray, fontSize = 12.sp) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFFFF453A),
                                        unfocusedBorderColor = Color(0xFF1F1F2C),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color(0xFF07070A),
                                        unfocusedContainerColor = Color(0xFF07070A)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "اختر رمزاً مجازاً (Avatar emoji):",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .background(Color(0xFF07070A), RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFF1F1F2C), RoundedCornerShape(12.dp))
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    emojiAvatars.take(8).forEach { emoji ->
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .background(
                                                    if (selectedEmoji == emoji) Color(0xFFFF453A).copy(alpha = 0.25f) else Color.Transparent,
                                                    CircleShape
                                                )
                                                .border(
                                                    1.dp,
                                                    if (selectedEmoji == emoji) Color(0xFFFF453A) else Color.Transparent,
                                                    CircleShape
                                                )
                                                .clickable { selectedEmoji = emoji },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(emoji, fontSize = 18.sp)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "أو اختر صورة من جهازك 🖼️:",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { imagePickerLauncher.launch("image/*") },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF453A)),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, Color(0xFFFF453A))
                                    ) {
                                        Text("رفع صورة من الملفات 📂", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (uploadedAvatarBase64 != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Base64Image(
                                                base64Str = uploadedAvatarBase64 ?: "",
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .border(1.2.dp, Color(0xFFFF453A), CircleShape)
                                            )
                                            Text(
                                                text = "جاهز! ✅",
                                                color = Color(0xFF34C759),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            IconButton(
                                                onClick = { uploadedAvatarBase64 = null },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Text("❌", color = Color.White, fontSize = 11.sp)
                                            }
                                        }
                                    } else {
                                        Text("لم يتم تحديد صورة بعد", color = Color.Gray, fontSize = 11.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.height(18.dp))
                                Button(
                                    onClick = {
                                        if (localPlayerName.isNotBlank()) {
                                            val avatar = uploadedAvatarBase64 ?: selectedEmoji
                                            GameEngine.addLocalPlayer(localPlayerName.trim(), avatar)
                                            localPlayerName = ""
                                            uploadedAvatarBase64 = null
                                        }
                                    },
                                    enabled = localPlayerName.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFF453A),
                                        disabledContainerColor = Color(0xFF1F1F2C),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Text("إضافة لاعب للقرية ➕", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // Original QR/Connected panel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // QR Code generator card
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(160.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                                border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    val url = "http://$ipAddress:8080"
                                    val qrBitmap = remember(ipAddress) {
                                        NetworkUtils.generateQRCode(url, 300)
                                    }
                                    Image(
                                        bitmap = qrBitmap.asImageBitmap(),
                                        contentDescription = "مسح كود انضمام الأصدقاء",
                                        modifier = Modifier
                                            .size(105.dp)
                                            .border(1.5.dp, Color.White, RoundedCornerShape(8.dp))
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "امسح كود QR للإدخال",
                                        color = Color.LightGray,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Connected players total cards
                            Card(
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(160.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                                border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "${players.size}",
                                        color = Color(0xFFFF453A),
                                        fontSize = 48.sp,
                                        fontWeight = FontWeight.Black,
                                        lineHeight = 48.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "لاعبين متصلين",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val expectedRoles = mafiaCount + citizenCount + doctorCount + detectiveCount
                                    Text(
                                        text = "الأدوار المقدرة: $expectedRoles",
                                        color = if (expectedRoles == players.size) Color(0xFF30D158) else Color(0xFFFFD700),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Native Host Player Registration
                    val isHostRegistered = players.any { it.id == "HOST_PLAYER_ID" }
                    val hostPlayer = players.find { it.id == "HOST_PLAYER_ID" }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                        border = BorderStroke(1.dp, Color(0xFFFF453A).copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = if (isHostRegistered) "تم تسجيلك كلاعب: ${hostPlayer?.name} ✅" else "تسجيل الهوست كلاعب في الجيم (اختياري) 👥",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            if (!isHostRegistered) {
                                OutlinedTextField(
                                    value = localPlayerName,
                                    onValueChange = { localPlayerName = it },
                                    label = { Text("اسم الهوست المشترك", color = Color.Gray, fontSize = 12.sp) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFFFF453A),
                                        unfocusedBorderColor = Color(0xFF1F1F2C),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color(0xFF07070A),
                                        unfocusedContainerColor = Color(0xFF07070A)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF07070A), RoundedCornerShape(10.dp))
                                        .border(1.dp, Color(0xFF1F1F2C), RoundedCornerShape(10.dp))
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("الأيقونة:", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                                    emojiAvatars.take(6).forEach { emoji ->
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .background(
                                                    if (selectedEmoji == emoji) Color(0xFFFF453A).copy(alpha = 0.25f) else Color.Transparent,
                                                    CircleShape
                                                )
                                                .border(
                                                    1.dp,
                                                    if (selectedEmoji == emoji) Color(0xFFFF453A) else Color.Transparent,
                                                    CircleShape
                                                )
                                                .clickable { selectedEmoji = emoji },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(emoji, fontSize = 16.sp)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "أو اختر صورة من جهازك 🖼️:",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { imagePickerLauncher.launch("image/*") },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF453A)),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color(0xFFFF453A))
                                    ) {
                                        Text("رفع صورة من الملفات 📂", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (uploadedAvatarBase64 != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Base64Image(
                                                base64Str = uploadedAvatarBase64 ?: "",
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .border(1.dp, Color(0xFFFF453A), CircleShape)
                                            )
                                            Text(
                                                text = "جاهز! ✅",
                                                color = Color(0xFF34C759),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            IconButton(
                                                onClick = { uploadedAvatarBase64 = null },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Text("❌", color = Color.White, fontSize = 10.sp)
                                            }
                                        }
                                    } else {
                                        Text("لم يتم تحديد صورة", color = Color.Gray, fontSize = 10.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        if (localPlayerName.isNotBlank()) {
                                            val avatar = uploadedAvatarBase64 ?: selectedEmoji
                                            GameEngine.registerPlayer("HOST_PLAYER_ID", localPlayerName.trim(), avatar)
                                            localPlayerName = ""
                                            uploadedAvatarBase64 = null
                                        }
                                    },
                                    enabled = localPlayerName.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFF453A),
                                        disabledContainerColor = Color(0xFF1F1F2C),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(44.dp)
                                ) {
                                    Text("سجل كلاعب في اللعبة 🎮", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Base64Image(base64Str = hostPlayer?.avatar ?: "👤", modifier = Modifier.size(48.dp).clip(CircleShape).border(1.dp, Color(0xFF1F1F2C), CircleShape))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("أنت مسجل كلاعب باسم: ${hostPlayer?.name}", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                    Button(
                                        onClick = {
                                            GameEngine.removePlayer("HOST_PLAYER_ID")
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E1212)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("إلغاء 🗑️", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A130B)),
                        border = BorderStroke(1.dp, Color(0xFFD08A22).copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("💡", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "حل مشاكل ربط الجوالات (Host)",
                                    color = Color(0xFFD08A22),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "١. تأكد من اتصال كافة الهواتف بـنفس شبكة الواي فاي بالضبط.\n" +
                                       "٢. إذا لم يتوفر راوتر مشترك، قم بتفعيل نقطة الاتصال المحمولة (Hotspot) من هذا الجوال، واجعل الهواتف الأخرى تتصل بشبكتك ونقطة اتصالك مباشرة.\n" +
                                       "٣. افتح الرابط في متصفح خارجي مستقل مثل Google Chrome وتجنب المتصفحات الداخلية المدمجة في تطبيقات التواصل.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                textAlign = TextAlign.Right
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // LOBBY CONFIGURATION BLOCK
                    Text(
                        text = "١. ضبط حزمة الأدوار السرية:",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val totalRolesSelected = mafiaCount + citizenCount + doctorCount + detectiveCount
                        val canAddMore = totalRolesSelected < players.size

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                RoleCard(
                                    title = "مافيا 👺",
                                    count = mafiaCount,
                                    color = Color(0xFFFF453A),
                                    icon = "👺",
                                    onPlus = { if (canAddMore) mafiaCount++ },
                                    onMinus = { if (mafiaCount > 0) mafiaCount-- }
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                RoleCard(
                                    title = "مواطن صالح 😇",
                                    count = citizenCount,
                                    color = Color(0xFF0A84FF),
                                    icon = "😇",
                                    onPlus = { if (canAddMore) citizenCount++ },
                                    onMinus = { if (citizenCount > 0) citizenCount-- }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                RoleCard(
                                    title = "طبيب 🩺",
                                    count = doctorCount,
                                    color = Color(0xFF30D158),
                                    icon = "🩺",
                                    onPlus = { if (canAddMore) doctorCount++ },
                                    onMinus = { if (doctorCount > 0) doctorCount-- }
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                RoleCard(
                                    title = "محقق 🔎",
                                    count = detectiveCount,
                                    color = Color(0xFF8E55FC),
                                    icon = "🔎",
                                    onPlus = { if (canAddMore) detectiveCount++ },
                                    onMinus = { if (detectiveCount > 0) detectiveCount-- }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "٢. تفضيلات ونمط الفعالية:",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Options Grid/List
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                        border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Ghost Mode Options
                            ConfigOptionRow(
                                title = "نمط الخروج (بعد الموت)",
                                leftLabel = "شاشة معتمة 🌌",
                                rightLabel = "مشاهد خارق 👁️",
                                isLeftSelected = ghostModeState == GhostMode.BLACKOUT,
                                onToggle = {
                                    ghostModeState = if (ghostModeState == GhostMode.BLACKOUT) GhostMode.SPECTATOR else GhostMode.BLACKOUT
                                }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            // Voting Options
                            ConfigOptionRow(
                                title = "طريقة التصويت بالنهار",
                                leftLabel = "سري مغلق 🤐",
                                rightLabel = "علني تفاعلي 🗳️",
                                isLeftSelected = voteStyleState == VoteStyle.SECRET,
                                onToggle = {
                                    voteStyleState = if (voteStyleState == VoteStyle.SECRET) VoteStyle.LIVE else VoteStyle.SECRET
                                }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            // Timer Options
                            ConfigOptionRow(
                                title = "مؤقت النقاش",
                                leftLabel = "يدوي بيد الهوست ⏳",
                                rightLabel = "تنازلي إجباري 🕰️",
                                isLeftSelected = timerStyleState == DiscussionTimerStyle.MANUAL,
                                onToggle = {
                                    timerStyleState = if (timerStyleState == DiscussionTimerStyle.MANUAL) DiscussionTimerStyle.AUTO else DiscussionTimerStyle.MANUAL
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            // Reveal role on death
                            ConfigToggleRow(
                                title = "كشف دور الميت مباشرة",
                                checked = revealRoleOnDeathState,
                                onCheckedChange = { revealRoleOnDeathState = it }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            // No kill first night
                            ConfigToggleRow(
                                title = "تفعيل الليلة الأولى للتعارف",
                                checked = noKillFirstNightState,
                                onCheckedChange = { noKillFirstNightState = it }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            // Show/hide skip vote option
                            ConfigToggleRow(
                                title = "إتاحة زر تخطي التصويت (الامتناع عن التصويت)",
                                checked = allowSkipVoteState,
                                onCheckedChange = { allowSkipVoteState = it }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    val requiredSum = mafiaCount + citizenCount + doctorCount + detectiveCount
                    val allReady = players.all { it.isReady }
                    val isStartEnabled = players.size > 0 && players.size == requiredSum && allReady

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Total Players: ${players.size} / Roles Assigned: $requiredSum ${if (players.size == requiredSum) "✅" else "⚠️"}",
                            color = if (players.size == requiredSum) Color(0xFF30D158) else Color(0xFFFFCC00),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { GameEngine.startGame() },
                            enabled = isStartEnabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .border(
                                    border = BorderStroke(1.5.dp, if (isStartEnabled) Color(0xFF30D158) else Color(0xFF1F1F2C)),
                                    shape = RoundedCornerShape(16.dp)
                                ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isStartEnabled) Color(0xFF152A1B) else Color(0xFF0F0F14),
                                contentColor = if (isStartEnabled) Color(0xFF30D158) else Color.Gray,
                                disabledContainerColor = Color(0xFF0F0F14),
                                disabledContentColor = Color.Gray
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = "بدء اللعبة 🔥",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .background(Color(0xFF0F0F14), RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFF1F1F2C), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("⚙️", fontSize = 18.sp)
                                Text("Game Log", color = Color.Gray, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "٣. اللاعبين المتصلين بالقرية (${players.size}):",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    if (players.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .border(1.dp, Color(0xFF1F1F2C), RoundedCornerShape(16.dp))
                                .background(Color(0xFF0F0F14)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (playMode == PlayMode.PASS_AND_PLAY) "⏳ لا يوجد لاعبين بعد...\nأضف اسم لاعب واضغط على زر الإضافة مع الأيقونة!" else "⏳ لا توجد اتصالات بعد...\nامسح كود QR أو اتصل بالـ Hotspot",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 18.sp
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F0F14), RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFF1F1F2C), RoundedCornerShape(16.dp))
                                .padding(14.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            players.forEach { p ->
                                Box(
                                    modifier = Modifier
                                        .size(68.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Bottom
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .pointerInput(p.id) {
                                                    detectTapGestures(
                                                        onTap = { GameEngine.togglePlayerReady(p.id) },
                                                        onLongPress = { showReorderPlayer = p }
                                                    )
                                                }
                                        ) {
                                            Base64Image(
                                                base64Str = p.avatar,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(CircleShape)
                                                    .border(2.dp, if (p.isReady) Color(0xFF30D158) else Color(0xFFFF453A), CircleShape)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = p.name,
                                            color = if (p.isReady) Color(0xFF30D158) else Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .background(Color(0xFF1C1C1E), CircleShape)
                                            .border(1.dp, Color(0xFF2C2C2E), CircleShape)
                                            .align(Alignment.TopEnd)
                                            .clickable { GameEngine.removePlayer(p.id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "×",
                                            color = Color.LightGray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier.offset(y = (-1).dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val localReorderPlayer = showReorderPlayer
                    if (localReorderPlayer != null) {
                        val player = localReorderPlayer
                        val playerIndex = players.indexOfFirst { it.id == player.id }
                        AlertDialog(
                            onDismissRequest = { showReorderPlayer = null },
                            title = {
                                Text(
                                    text = "ترتيب المقاعد 🪑",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            text = {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "تغيير ترتيب مقعد اللاعب: ${player.name}",
                                        color = Color.LightGray,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "الترتيب الحالي: #${playerIndex + 1}",
                                        color = Color.Gray,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        Button(
                                            onClick = {
                                                GameEngine.movePlayerUp(player.id)
                                                showReorderPlayer = null
                                            },
                                            enabled = playerIndex > 0,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF1F1F2C),
                                                disabledContainerColor = Color(0xFF0F0F14),
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("تحريك للأعلى ⬆️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = {
                                                GameEngine.movePlayerDown(player.id)
                                                showReorderPlayer = null
                                            },
                                            enabled = playerIndex < players.size - 1,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF1F1F2C),
                                                disabledContainerColor = Color(0xFF0F0F14),
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("تحريك للأسفل ⬇️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showReorderPlayer = null }) {
                                    Text("إغلاق", color = Color(0xFFFF453A), fontWeight = FontWeight.Black)
                                }
                            },
                            containerColor = Color(0xFF0F0F14),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(30.dp))
                }
            } else {
                // ACTIVE GAME CONTROL CENTER VIEW
                if (playMode == PlayMode.PASS_AND_PLAY && (phase == GamePhase.ROLE_REVEAL || phase == GamePhase.NIGHT || phase == GamePhase.DAY_VOTING)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        // Pass & Play Special Game Wizard
                        if (phase == GamePhase.ROLE_REVEAL) {
                            var currentRevealIndex by remember { mutableStateOf(0) }
                            var isRoleRevealed by remember { mutableStateOf(false) }

                            LaunchedEffect(phase) {
                                currentRevealIndex = 0
                                isRoleRevealed = false
                            }

                            if (currentRevealIndex < players.size) {
                                val currentPlayer = players[currentRevealIndex]
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                                    border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "جولة كشف الأدوار السرية 🤫",
                                            color = Color(0xFFFFD60A),
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(20.dp))
                                        Text(
                                            text = "مرّر الجوّال وسلّمه بهدوء إلى:",
                                            color = Color.LightGray,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        
                                        // Player Name
                                        Text(
                                            text = currentPlayer.name,
                                            color = Color.White,
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Black,
                                            textAlign = TextAlign.Center
                                        )
                                        
                                        // Large Avatar representation
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = 20.dp)
                                                .size(114.dp)
                                                .border(
                                                    BorderStroke(3.dp, Brush.horizontalGradient(listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0)))),
                                                    CircleShape
                                                )
                                                .background(Color(0xFF07070A), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Base64Image(
                                                base64Str = currentPlayer.avatar,
                                                modifier = Modifier
                                                    .size(100.dp)
                                                    .clip(CircleShape)
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        if (!isRoleRevealed) {
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = Color(0x14FF453A)),
                                                border = BorderStroke(1.dp, Color(0x33FF453A)),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "⚠️ الحنيوك لا يرحم! احرص على ألا يرى أحد شاشتك الحالية.",
                                                    color = Color(0xFFFF453A),
                                                    fontSize = 12.sp,
                                                    textAlign = TextAlign.Center,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(12.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(24.dp))
                                            Button(
                                                onClick = { isRoleRevealed = true },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier.fillMaxWidth().height(52.dp)
                                            ) {
                                                Text("اكشف دوري السري 🫣", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            val label = getRoleLabelArabic(currentPlayer.role)
                                            val description = when(currentPlayer.role) {
                                                "MAFIA" -> "أنت المافيا الشرير! غايتك تصفية كافة شرفاء بلدة الحنيوك لكي تنتصر... تآمر في الليل واقنعهم ببراءتك في النهار! 👺"
                                                "DOCTOR" -> "أنت الطبيب المنقذ! مهمتك السامية هي اختيار مواطن لحمايته وترياقه كل ليلة لإنقاذه من مخالب المافيا... 🩺"
                                                "DETECTIVE" -> "أنت المحقق الخارق! قم باستجواب مشتبه به كل ليلة لتكشف هويته وتعرف إن كان مافيا شريراً أم شريفاً، واقنع أهل البلدة بالتصويت ضده! 🔎"
                                                else -> "أنت مواطن شريف وصالح! ليس لديك قوى ليلية، ولكن سلاحك الأعظم هو عقلك ولسانك وتصويتك الجريء نهاراً لطرد الأشرار! 😇"
                                            }
                                            
                                            val roleColor = when(currentPlayer.role) {
                                                "MAFIA" -> Color(0xFFFF453A)
                                                "DOCTOR" -> Color(0xFF30D158)
                                                "DETECTIVE" -> Color(0xFFFFD60A)
                                                else -> Color(0xFF0A84FF)
                                            }

                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFF07070A)),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF1F1F2C), RoundedCornerShape(16.dp))
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(text = "دورك السري هو:", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(text = label, color = roleColor, fontSize = 26.sp, fontWeight = FontWeight.Black)
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    Text(text = description, color = Color.LightGray, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                                                }
                                            }
                                            
                                            Spacer(modifier = Modifier.height(24.dp))
                                            Button(
                                                onClick = {
                                                    isRoleRevealed = false
                                                    currentRevealIndex++
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier.fillMaxWidth().height(52.dp)
                                            ) {
                                                Text("فهمت، غطِّ شاشتي وامضِ للغد 🤫", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            } else {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                                    border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("🎉 اكتمل كشف الهويات للجميع!", color = Color(0xFF30D158), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("الآن يبدأ غسق الليل المهيب... خذوا مواقعكم واستعدوا للمكائد السرية والخطط الليلية.", color = Color.LightGray, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                                        Spacer(modifier = Modifier.height(24.dp))
                                        Button(
                                            onClick = { GameEngine.enterNight() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth().height(54.dp)
                                        ) {
                                            Text("بدء جولة الليل المغلقة Camping 🏕️", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else if (phase == GamePhase.NIGHT) {
                            val livingPlayersForNight = remember(players) {
                                players.filter { it.isAlive }
                            }
                            var nightPlayerIndex by remember { mutableStateOf(0) }
                            var isNightActionRevealed by remember { mutableStateOf(false) }
                            var nightSelectionId by remember { mutableStateOf<String?>(null) }
                            var detectiveRevealResult by remember { mutableStateOf<String?>(null) }
                            
                            var citizenDummySelectionId by remember { mutableStateOf<String?>(null) }
                            val citizenDummyClue = remember(citizenDummySelectionId) {
                                val clues = listOf(
                                    "تحسست دقات قلب متسارعة من ناحيته... 🧐",
                                    "يبدو غارقاً في نوم عميق مع أصوات هادئة... 💤",
                                    "رائحة غريبة وأنفاس مشبوهة تفوح من مكانه... 🕵️‍♂️",
                                    "سمعت حركته الخافتة تبدو مريبة بعض الشيء... 👣"
                                )
                                if (citizenDummySelectionId != null) clues.random() else null
                            }

                            LaunchedEffect(phase) {
                                nightPlayerIndex = 0
                                isNightActionRevealed = false
                                nightSelectionId = null
                                detectiveRevealResult = null
                                citizenDummySelectionId = null
                            }

                            if (nightPlayerIndex < livingPlayersForNight.size) {
                                val currentPlayer = livingPlayersForNight[nightPlayerIndex]
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                                    border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "🏕️ غسق الليل المهيب والسرية",
                                            color = Color(0xFF0A84FF),
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(20.dp))
                                        Text(
                                            text = "يرجى تمرير الهاتف وسلّمه بهدوء تام إلى اللاعب التالي:",
                                            color = Color.LightGray,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        
                                        // Player Name
                                        Text(
                                            text = currentPlayer.name,
                                            color = Color.White,
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Black,
                                            textAlign = TextAlign.Center
                                        )
                                        
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = 16.dp)
                                                .size(90.dp)
                                                .border(
                                                    BorderStroke(3.dp, Brush.horizontalGradient(listOf(Color(0xFF0A84FF), Color(0xFF00FFCC)))),
                                                    CircleShape
                                                )
                                                .background(Color(0xFF07070A), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val emoji = if (currentPlayer.avatar.length < 5 && currentPlayer.avatar.isNotEmpty()) currentPlayer.avatar else "👤"
                                            Text(emoji, fontSize = 42.sp)
                                        }
                                        
                                        Spacer(modifier = Modifier.height(8.dp))

                                        if (!isNightActionRevealed) {
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = Color(0x14FF453A)),
                                                border = BorderStroke(1.dp, Color(0x33FF453A)),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "🤫 جميع اللاعبين الآخرين يغلقون أعينهم الآن! لمنع كشف الأدوار والتلصص.",
                                                    color = Color(0xFFFF453A),
                                                    fontSize = 11.sp,
                                                    textAlign = TextAlign.Center,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(12.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(24.dp))
                                            Button(
                                                onClick = { 
                                                    isNightActionRevealed = true 
                                                    if (currentPlayer.role == "MAFIA") {
                                                        nightSelectionId = players.find { it.role == "MAFIA" && it.isAlive }?.mafiaTarget
                                                    } else if (currentPlayer.role == "DOCTOR") {
                                                        nightSelectionId = players.find { it.role == "DOCTOR" && it.isAlive }?.doctorTarget
                                                    } else if (currentPlayer.role == "DETECTIVE") {
                                                        nightSelectionId = players.find { it.role == "DETECTIVE" && it.isAlive }?.detectiveTarget
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier.fillMaxWidth().height(52.dp)
                                            ) {
                                                Text("أنا ${currentPlayer.name}، ابدأ دوري بالسر 🫣", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            if (currentPlayer.role == "MAFIA") {
                                                Text(
                                                    text = "أنت المافيا الشرير 👺! اختر الضحية لتصفيتها الليلة:",
                                                    color = Color.LightGray,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(bottom = 12.dp),
                                                    textAlign = TextAlign.Center
                                                )
                                                
                                                val livingVictims = players.filter { it.isAlive }
                                                livingVictims.forEach { victim ->
                                                    val isSelected = nightSelectionId == victim.id
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 5.dp)
                                                            .clickable { nightSelectionId = victim.id }
                                                            .border(
                                                                1.dp,
                                                                if (isSelected) Color(0xFFFF453A) else Color(0xFF1F1F2C),
                                                                RoundedCornerShape(14.dp)
                                                            ),
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isSelected) Color(0x22FF453A) else Color(0xFF07070A)
                                                        ),
                                                        shape = RoundedCornerShape(14.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(12.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Base64Image(base64Str = victim.avatar, modifier = Modifier.size(36.dp).clip(CircleShape).border(1.dp, Color(0xFF1F1F2C), CircleShape))
                                                            Spacer(modifier = Modifier.width(12.dp))
                                                            Text(victim.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                
                                                Spacer(modifier = Modifier.height(16.dp))
                                                Button(
                                                    onClick = {
                                                        players.forEach { p ->
                                                            if (p.role == "MAFIA" && p.isAlive) {
                                                                p.mafiaTarget = nightSelectionId
                                                            }
                                                        }
                                                        isNightActionRevealed = false
                                                        nightSelectionId = null
                                                        nightPlayerIndex++
                                                    },
                                                    enabled = nightSelectionId != null,
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF30D158),
                                                        disabledContainerColor = Color(0xFF1F1F2C),
                                                        disabledContentColor = Color.Gray
                                                    ),
                                                    shape = RoundedCornerShape(14.dp),
                                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                                ) {
                                                    Text("تأكيد وحفظ صمت الليل 🤫", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                                }
                                            } else if (currentPlayer.role == "DOCTOR") {
                                                Text(
                                                    text = "أنت الطبيب المنقذ 🩺! اختر مواطناً لحمايته وترياقه كل ليلة لإنقاذه من مخالب المافيا:",
                                                    color = Color.LightGray,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(bottom = 12.dp),
                                                    textAlign = TextAlign.Center
                                                )
                                                
                                                val livingVictims = players.filter { it.isAlive }
                                                livingVictims.forEach { victim ->
                                                    val isSelectable = victim.id != GameEngine.previousDoctorProtect
                                                    val isSelected = nightSelectionId == victim.id
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 5.dp)
                                                            .clickable(enabled = isSelectable) { nightSelectionId = victim.id }
                                                            .border(
                                                                1.dp,
                                                                if (isSelected) Color(0xFF30D158) else if (!isSelectable) Color(0x33FF453A) else Color(0xFF1F1F2C),
                                                                RoundedCornerShape(14.dp)
                                                            ),
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isSelected) Color(0x1C30D158) else if (!isSelectable) Color(0x0F0F0F14) else Color(0xFF07070A)
                                                        ),
                                                        shape = RoundedCornerShape(14.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(12.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Base64Image(base64Str = victim.avatar, modifier = Modifier.size(36.dp).clip(CircleShape).border(1.dp, Color(0xFF1F1F2C), CircleShape))
                                                            Spacer(modifier = Modifier.width(12.dp))
                                                            Column {
                                                                Text(
                                                                    text = victim.name,
                                                                    color = if (isSelectable) Color.White else Color.Gray,
                                                                    fontSize = 14.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                                if (!isSelectable) {
                                                                    Text("محمي بالليلة الفائتة (لا يمكن حمايته مرتين متتاليتين)", color = Color(0xFFFF453A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                
                                                Spacer(modifier = Modifier.height(16.dp))
                                                Button(
                                                    onClick = {
                                                        players.forEach { p ->
                                                            if (p.role == "DOCTOR" && p.isAlive) {
                                                                p.doctorTarget = nightSelectionId
                                                            }
                                                        }
                                                        isNightActionRevealed = false
                                                        nightSelectionId = null
                                                        nightPlayerIndex++
                                                    },
                                                    enabled = nightSelectionId != null,
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF30D158),
                                                        disabledContainerColor = Color(0xFF1F1F2C),
                                                        disabledContentColor = Color.Gray
                                                    ),
                                                    shape = RoundedCornerShape(14.dp),
                                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                                ) {
                                                    Text("تأكيد وحفظ صمت الليل 🤫", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                                }
                                            } else if (currentPlayer.role == "DETECTIVE") {
                                                Text(
                                                    text = "أنت المحقق الخارق 🔎! استجوب مشتبهاً لفحص هويته وتبيان أمر المافيا:",
                                                    color = Color.LightGray,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(bottom = 12.dp),
                                                    textAlign = TextAlign.Center
                                                )
                                                
                                                val livingVictims = players.filter { it.isAlive && it.role != "DETECTIVE" }
                                                livingVictims.forEach { victim ->
                                                    val isSelected = nightSelectionId == victim.id
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 5.dp)
                                                            .clickable { nightSelectionId = victim.id }
                                                            .border(
                                                                1.dp,
                                                                if (isSelected) Color(0xFFFFD60A) else Color(0xFF1F1F2C),
                                                                RoundedCornerShape(14.dp)
                                                            ),
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isSelected) Color(0x1CFFD60A) else Color(0xFF07070A)
                                                        ),
                                                        shape = RoundedCornerShape(14.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(12.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Base64Image(base64Str = victim.avatar, modifier = Modifier.size(36.dp).clip(CircleShape).border(1.dp, Color(0xFF1F1F2C), CircleShape))
                                                            Spacer(modifier = Modifier.width(12.dp))
                                                            Text(victim.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                
                                                Spacer(modifier = Modifier.height(16.dp))
                                                
                                                if (nightSelectionId != null && detectiveRevealResult == null) {
                                                    val questioned = players.find { it.id == nightSelectionId }
                                                    Button(
                                                        onClick = {
                                                            detectiveRevealResult = if (questioned?.role == "MAFIA") "مذنب! مافيا شرير 👺🚨" else "صالح! شريف حقيقي 😇💚"
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD60A), contentColor = Color.Black),
                                                        shape = RoundedCornerShape(14.dp),
                                                        modifier = Modifier.fillMaxWidth().height(48.dp)
                                                    ) {
                                                        Text("تشييد الاستجواب ومعاينة الأوراق 🔎", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                                                    }
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                }
        
                                                val localRevealResult = detectiveRevealResult
                                                if (localRevealResult != null) {
                                                    val isEnemyDetected = localRevealResult.contains("مذنب")
                                                    Card(
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isEnemyDetected) Color(0x22FF453A) else Color(0x2230D158)
                                                        ),
                                                        border = BorderStroke(1.dp, if (isEnemyDetected) Color(0xFFFF453A) else Color(0xFF30D158)),
                                                        shape = RoundedCornerShape(12.dp),
                                                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                                    ) {
                                                        Text(
                                                            text = "الملف السري: $detectiveRevealResult",
                                                            color = if (isEnemyDetected) Color(0xFFFF453A) else Color(0xFF30D158),
                                                            fontSize = 15.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                                            textAlign = TextAlign.Center
                                                        )
                                                    }
                                                }
                                                
                                                Button(
                                                    onClick = {
                                                        players.forEach { p ->
                                                            if (p.role == "DETECTIVE" && p.isAlive) {
                                                                p.detectiveTarget = nightSelectionId
                                                            }
                                                        }
                                                        GameEngine.submitDetectiveVote(currentPlayer.id, nightSelectionId)
                                                        isNightActionRevealed = false
                                                        nightSelectionId = null
                                                        detectiveRevealResult = null
                                                        nightPlayerIndex++
                                                    },
                                                    enabled = nightSelectionId != null && detectiveRevealResult != null,
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF30D158),
                                                        disabledContainerColor = Color(0xFF1F1F2C),
                                                        disabledContentColor = Color.Gray
                                                    ),
                                                    shape = RoundedCornerShape(14.dp),
                                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                                ) {
                                                    Text("تأكيد وحفظ صمت الليل 🤫", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                                }
                                            } else {
                                                Text(
                                                    text = "أنت مواطن شريف وصالح 😇\nنم بسلام وغط الشاشة، ولكن تنصّت بحذر لخطوات الليل المشبوهة تضامناً مع القرية!",
                                                    color = Color.LightGray,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(bottom = 12.dp),
                                                    textAlign = TextAlign.Center,
                                                    lineHeight = 20.sp
                                                )
                                                
                                                Text(
                                                    text = "للتمويه وإيهام الناظرين، اختر المشتبه به الذي تشعر بأصوات مريبة قادمة منه:",
                                                    color = Color.Gray,
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.padding(bottom = 10.dp),
                                                    textAlign = TextAlign.Center
                                                )
                                                
                                                val potentialSuspects = players.filter { it.isAlive && it.id != currentPlayer.id }
                                                potentialSuspects.forEach { victim ->
                                                    val isSelected = citizenDummySelectionId == victim.id
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 5.dp)
                                                            .clickable { citizenDummySelectionId = victim.id },
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isSelected) Color(0x22FFD60A) else Color(0xFF07070A)
                                                        ),
                                                        border = BorderStroke(1.dp, if (isSelected) Color(0xFFFFD60A) else Color(0xFF1F1F2C)),
                                                        shape = RoundedCornerShape(14.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(12.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Base64Image(base64Str = victim.avatar, modifier = Modifier.size(36.dp).clip(CircleShape).border(1.dp, Color(0xFF1F1F2C), CircleShape))
                                                            Spacer(modifier = Modifier.width(12.dp))
                                                            Text(victim.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                
                                                Spacer(modifier = Modifier.height(16.dp))
        
                                                Button(
                                                    onClick = {
                                                        isNightActionRevealed = false
                                                        citizenDummySelectionId = null
                                                        nightPlayerIndex++
                                                    },
                                                    enabled = citizenDummySelectionId != null,
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF30D158),
                                                        disabledContainerColor = Color(0xFF1F1F2C),
                                                        disabledContentColor = Color.Gray
                                                    ),
                                                    shape = RoundedCornerShape(14.dp),
                                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                                ) {
                                                    Text("حفظ ودعم صمت الليل 🤫", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                                    border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("🌌 أنهت المخاوف الليلة دورتها بالكامل!", color = Color(0xFF30D158), fontSize = 22.sp, fontWeight = FontWeight.Black)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("جميع الأدوار الليلية اكتملت بنجاح وتكتم. استعدوا لمعرفة ما حدث في الصباح الباكر وقرار المأساة!", color = Color.LightGray, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                                        Spacer(modifier = Modifier.height(24.dp))
                                        Button(
                                            onClick = { GameEngine.resolveNightPhaseAndEnterDay() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500)),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth().height(54.dp)
                                        ) {
                                            Text("أشرقت الشمس! استيقاظ القرية ☀️", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else if (phase == GamePhase.DAY_VOTING) {
                            val votingSequence = remember(players) {
                                players.filter { it.isAlive }
                            }
                            var votingIndex by remember { mutableStateOf(0) }
                            var isVoteViewRevealed by remember { mutableStateOf(false) }
                            var localVoteTargetId by remember { mutableStateOf<String?>(null) }
                            var hasSkippedVote by remember { mutableStateOf(false) }

                            LaunchedEffect(phase) {
                                votingIndex = 0
                                isVoteViewRevealed = false
                                localVoteTargetId = null
                                hasSkippedVote = false
                                players.forEach { it.targetVote = null }
                            }

                            if (votingIndex < votingSequence.size) {
                                val voterPlayer = votingSequence[votingIndex]
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                                    border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "🗳️ صندوق اقتراع التصويت السري",
                                            color = Color(0xFFFF9500),
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(20.dp))
                                        Text(
                                            text = "يرجى تمرير الهاتف وسلّمه بهدوء إلى اللاعب الحالي:",
                                            color = Color.LightGray,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = voterPlayer.name,
                                            color = Color.White,
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Black,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))

                                        if (!isVoteViewRevealed) {
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = Color(0x14FF9500)),
                                                border = BorderStroke(1.dp, Color(0x33FF9500)),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                            ) {
                                                Text(
                                                    text = "🤫 الشاشة مغلقة للحفاظ على سرية قراركم وطرد الكاذب الأكبر من القرية بهدوء!",
                                                    color = Color(0xFFFF9500),
                                                    fontSize = 12.sp,
                                                    textAlign = TextAlign.Center,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(12.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Button(
                                                onClick = { isVoteViewRevealed = true },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500)),
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier.fillMaxWidth().height(52.dp)
                                            ) {
                                                Text("الإشفاق وصوت صندوقي السري 🤫", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            val potentialSuspects = players.filter { it.isAlive && it.id != voterPlayer.id }
                                            
                                            Text(
                                                text = "اختر المشتبه به لتصوت لطردة أو امتنع وعطّل تصويتك:",
                                                color = Color.LightGray,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(bottom = 12.dp),
                                                textAlign = TextAlign.Center
                                            )

                                            if (allowSkipVoteState) {
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 5.dp)
                                                        .clickable { 
                                                            localVoteTargetId = "SKIP"
                                                            hasSkippedVote = true 
                                                        }
                                                        .border(
                                                            1.dp,
                                                            if (hasSkippedVote) Color(0xFF30D158) else Color(0xFF1F1F2C),
                                                            RoundedCornerShape(14.dp)
                                                        ),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (hasSkippedVote) Color(0x1C30D158) else Color(0xFF07070A)
                                                    ),
                                                    shape = RoundedCornerShape(14.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(12.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text("🤷‍♂️", fontSize = 24.sp)
                                                        Spacer(modifier = Modifier.width(12.dp))
                                                        Text("الامتناع عن التصويت (Skip Vote)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }

                                            potentialSuspects.forEach { suspect ->
                                                val isChosen = localVoteTargetId == suspect.id && !hasSkippedVote
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 5.dp)
                                                        .clickable { 
                                                            localVoteTargetId = suspect.id
                                                            hasSkippedVote = false
                                                        }
                                                        .border(
                                                            1.dp,
                                                            if (isChosen) Color(0xFFFF453A) else Color(0xFF1F1F2C),
                                                            RoundedCornerShape(14.dp)
                                                        ),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (isChosen) Color(0x22FF453A) else Color(0xFF07070A)
                                                    ),
                                                    shape = RoundedCornerShape(14.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(12.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Base64Image(base64Str = suspect.avatar, modifier = Modifier.size(36.dp).clip(CircleShape).border(1.dp, Color(0xFF1F1F2C), CircleShape))
                                                        Spacer(modifier = Modifier.width(12.dp))
                                                        Text(suspect.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(24.dp))
                                            val hasMadeDecision = localVoteTargetId != null || hasSkippedVote
                                            Button(
                                                onClick = {
                                                    GameEngine.submitDayVote(voterPlayer.id, localVoteTargetId)
                                                    
                                                    isVoteViewRevealed = false
                                                    localVoteTargetId = null
                                                    hasSkippedVote = false
                                                    votingIndex++
                                                },
                                                enabled = hasMadeDecision,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF30D158),
                                                    disabledContainerColor = Color(0xFF1F1F2C),
                                                    disabledContentColor = Color.Gray
                                                ),
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier.fillMaxWidth().height(52.dp)
                                            ) {
                                                Text("تأكيد وحفظ شفرة صوتي 🤫", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            } else {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                                    border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("🎉 تم تدوين وفرز كامل صندوق الأصوات!", color = Color(0xFF30D158), fontSize = 22.sp, fontWeight = FontWeight.Black)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("كل قراراتكم تم تشفيرها في الصندوق بأمان. سلّم الجوال للهوست فوراً لرؤية من سينفي عن بلدة الحنيوك غياهب التخفي والشكوك!", color = Color.LightGray, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                                        Spacer(modifier = Modifier.height(24.dp))
                                        Button(
                                            onClick = { GameEngine.resolveDayVoting() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth().height(54.dp)
                                        ) {
                                            Text("إعلان الإعدام وفرز التلّي ⚖️", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (phase == GamePhase.GAME_OVER) {
                        GameOverHostView(players = players, onPlayAgain = { GameEngine.resetGameToLobby() })
                    } else {
                        // ACTIVE GAME CONTROL CENTER VIEW
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            // Active Phase visual board
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                                border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = getPhaseTitle(phase),
                                        color = getPhaseColor(phase),
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Black,
                                        textAlign = TextAlign.Center
                                    )
                                    
                                    if (countdown >= 0) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Text("⏱️", fontSize = 14.sp)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "المتبقي: ${countdown} ثانية",
                                                color = Color(0xFFFFF136),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            
                            NewsBanner(logs = logs)
                            
                            Spacer(modifier = Modifier.height(16.dp))

                            val ostPlayer = players.find { it.id == "HOST_PLAYER_ID" }
                            if (ostPlayer != null && ostPlayer.isAlive) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                                    border = BorderStroke(1.dp, Color(0xFFFF453A).copy(alpha = 0.4f)),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("🎮", fontSize = 16.sp)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "شاشتي كلاعب (${ostPlayer.name})",
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            if (!ostPlayer.isAlive) {
                                                Text(text = "🥀 ميت", color = Color(0xFFFF453A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            } else {
                                                Text(text = "💚 حيّ", color = Color(0xFF30D158), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        
                                        Spacer(modifier = Modifier.height(12.dp))
                                        
                                        if (phase == GamePhase.ROLE_REVEAL) {
                                            if (!isHostRoleRevealed) {
                                                Button(
                                                    onClick = { isHostRoleRevealed = true },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                                                    shape = RoundedCornerShape(14.dp),
                                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                                ) {
                                                    Text("كشف دوري السري 🫣", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                }
                                            } else {
                                                val label = getRoleLabelArabic(ostPlayer.role)
                                                val desc = when (ostPlayer.role) {
                                                    "MAFIA" -> "أنت المافيا الشرير! تصفية الشرفاء بالليل والتضليل بالنهار... 👺"
                                                    "DOCTOR" -> "أنت الطبيب المنقذ! اعطِ مصل الحماية لمواطن كل ليلة... 🩺"
                                                    "DETECTIVE" -> "أنت المحقق الخارق! استجوب هوية مشتبه به كل ليلة... 🔎"
                                                    else -> "أنت مواطن شريف وصالح! تصديق الحدس والتصويت بالنهار... 😇"
                                                }
                                                val roleCol = when (ostPlayer.role) {
                                                    "MAFIA" -> Color(0xFFFF453A)
                                                    "DOCTOR" -> Color(0xFF30D158)
                                                    "DETECTIVE" -> Color(0xFF0A84FF)
                                                    else -> Color.White
                                                }
                                                Card(
                                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF07070A)),
                                                    shape = RoundedCornerShape(14.dp),
                                                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF1F1F2C), RoundedCornerShape(14.dp))
                                                ) {
                                                    Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text(text = "دورك السري هو:", color = Color.Gray, fontSize = 12.sp)
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text(text = label, color = roleCol, fontSize = 20.sp, fontWeight = FontWeight.Black)
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Text(text = desc, color = Color.LightGray, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
                                                        Spacer(modifier = Modifier.height(12.dp))
                                                        Button(
                                                            onClick = { isHostRoleRevealed = false },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F1F2C)),
                                                            shape = RoundedCornerShape(10.dp),
                                                            modifier = Modifier.height(36.dp),
                                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                                                        ) {
                                                            Text("إخفاء دوري 🤫", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }
                                        } else if (phase == GamePhase.NIGHT) {
                                            if (!ostPlayer.isAlive) {
                                                Text("أنت ميت! تفضل بمتابعة اللعبة ومشاهدتها بصمت... 🌌", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                            } else {
                                                when (ostPlayer.role) {
                                                    "MAFIA" -> {
                                                        val candidateVictims = players.filter { it.isAlive && it.role != "MAFIA" }
                                                        Text("اختر مواطناً لتصفيته الليلة 👺:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        
                                                        candidateVictims.forEach { candidate ->
                                                            val isSelected = hostNightTargetId == candidate.id
                                                            Card(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(vertical = 4.dp)
                                                                    .clickable { hostNightTargetId = candidate.id }
                                                                    .border(1.dp, if (isSelected) Color(0xFFFF453A) else Color(0xFF1F1F2C), RoundedCornerShape(12.dp)),
                                                                colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0x22FF453A) else Color(0xFF07070A)),
                                                                shape = RoundedCornerShape(12.dp)
                                                            ) {
                                                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                                    Text(candidate.avatar.ifEmpty { "👤" }, fontSize = 18.sp)
                                                                    Spacer(modifier = Modifier.width(10.dp))
                                                                    Text(candidate.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                                }
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.height(10.dp))
                                                        val isMafiaVoted = ostPlayer.mafiaTarget != null
                                                        Button(
                                                            onClick = { GameEngine.submitMafiaVote(ostPlayer.id, hostNightTargetId) },
                                                            enabled = hostNightTargetId != null,
                                                            colors = ButtonDefaults.buttonColors(containerColor = if (isMafiaVoted) Color.Gray else Color(0xFFFF453A)),
                                                            shape = RoundedCornerShape(12.dp),
                                                            modifier = Modifier.fillMaxWidth().height(42.dp)
                                                        ) {
                                                            Text(if (isMafiaVoted) "تم تأكيد طلب التصفية نهاراً ⌛" else "تأكيد التصفية 🩸", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                    "DOCTOR" -> {
                                                        val candidateHeals = players.filter { it.isAlive && it.id != GameEngine.previousDoctorProtect }
                                                        Text("اختر مواطناً لحمايته الليلة 🩺:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        
                                                        candidateHeals.forEach { candidate ->
                                                            val isSelected = hostNightTargetId == candidate.id
                                                            Card(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(vertical = 4.dp)
                                                                    .clickable { hostNightTargetId = candidate.id }
                                                                    .border(1.dp, if (isSelected) Color(0xFF30D158) else Color(0xFF1F1F2C), RoundedCornerShape(12.dp)),
                                                                colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0x1C30D158) else Color(0xFF07070A)),
                                                                shape = RoundedCornerShape(12.dp)
                                                            ) {
                                                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                                    Text(candidate.avatar.ifEmpty { "👤" }, fontSize = 18.sp)
                                                                    Spacer(modifier = Modifier.width(10.dp))
                                                                    Text(candidate.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                                }
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.height(10.dp))
                                                        val isDoctorVoted = ostPlayer.doctorTarget != null
                                                        Button(
                                                            onClick = { GameEngine.submitDoctorVote(ostPlayer.id, hostNightTargetId) },
                                                            enabled = hostNightTargetId != null,
                                                            colors = ButtonDefaults.buttonColors(containerColor = if (isDoctorVoted) Color.Gray else Color(0xFF30D158)),
                                                            shape = RoundedCornerShape(12.dp),
                                                            modifier = Modifier.fillMaxWidth().height(42.dp)
                                                        ) {
                                                            Text(if (isDoctorVoted) "تم إرسال ترياق العلاج ⌛" else "تأكيد ترياق الحماية 🧪", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                    "DETECTIVE" -> {
                                                        val candidateInquiries = players.filter { it.isAlive && it.id != ostPlayer.id }
                                                        Text("اختر مشتبهاً لاستجوابه الليلة 🔎:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        
                                                        candidateInquiries.forEach { candidate ->
                                                            val isSelected = hostNightTargetId == candidate.id
                                                            Card(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(vertical = 4.dp)
                                                                    .clickable { hostNightTargetId = candidate.id }
                                                                    .border(1.dp, if (isSelected) Color(0xFF0A84FF) else Color(0xFF1F1F2C), RoundedCornerShape(12.dp)),
                                                                colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0x1C0A84FF) else Color(0xFF07070A)),
                                                                shape = RoundedCornerShape(12.dp)
                                                            ) {
                                                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                                    Text(candidate.avatar.ifEmpty { "👤" }, fontSize = 18.sp)
                                                                    Spacer(modifier = Modifier.width(10.dp))
                                                                    Text(candidate.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                                }
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.height(10.dp))
                                                        val detectiveResult by GameEngine.detectiveResult.collectAsState()
                                                        if (detectiveResult != null && detectiveResult?.playerId == hostNightTargetId) {
                                                            val isTargetMafia = detectiveResult?.isMafia == true
                                                            Card(
                                                                colors = CardDefaults.cardColors(containerColor = Color(0x1A0A84FF)),
                                                                border = BorderStroke(1.dp, Color(0xFF0A84FF).copy(alpha = 0.5f)),
                                                                shape = RoundedCornerShape(12.dp),
                                                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                                            ) {
                                                                Text(
                                                                    text = "نتيجة الفحص 🔎: هذا اللاعب هو ${if (isTargetMafia) "مافيا شرير! 👺" else "شريف صالح! 😇"}",
                                                                    color = if (isTargetMafia) Color(0xFFFF453A) else Color(0xFF30D158),
                                                                    fontSize = 13.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(12.dp),
                                                                    textAlign = TextAlign.Center
                                                                )
                                                            }
                                                        }
                                                        val isDetectiveVoted = ostPlayer.detectiveTarget != null
                                                        Button(
                                                            onClick = { GameEngine.submitDetectiveVote(ostPlayer.id, hostNightTargetId) },
                                                            enabled = hostNightTargetId != null,
                                                            colors = ButtonDefaults.buttonColors(containerColor = if (isDetectiveVoted) Color.Gray else Color(0xFF0A84FF)),
                                                            shape = RoundedCornerShape(12.dp),
                                                            modifier = Modifier.fillMaxWidth().height(42.dp)
                                                        ) {
                                                            Text(if (isDetectiveVoted) "تم الفحص وسجلت الهوية ⌛" else "استجواب الهوية 🔎", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                    else -> {
                                                        Text("أنت مواطن شريف، نم هانئاً وصالحاً الليلة بأمان... 💤🌃", color = Color.LightGray, fontSize = 13.sp)
                                                    }
                                                }
                                            }
                                        } else if (phase == GamePhase.DAY_DISCUSSION) {
                                            Text("نقاش في النهار! تحاور وتبادل التهم مع رفاقك لحنيوك بلدتكم... 🗣️☀️", color = Color.LightGray, fontSize = 13.sp)
                                        } else if (phase == GamePhase.DAY_VOTING) {
                                            if (!ostPlayer.isAlive) {
                                                Text("أنت ميت! لا يحق لك التصويت بالنهار... 🥀", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            } else {
                                                val potentialSuspects = players.filter { it.isAlive && it.id != ostPlayer.id }
                                                Text("اختر المشتبه به لتصوت لنفيه من بلدتكم ⚖️:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                Spacer(modifier = Modifier.height(8.dp))
                                                
                                                if (allowSkipVoteState) {
                                                    val isSkipChosen = hostLocalHasSkippedVote
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 4.dp)
                                                            .clickable { 
                                                                hostLocalVoteTargetId = "SKIP"
                                                                hostLocalHasSkippedVote = true 
                                                            }
                                                            .border(1.dp, if (isSkipChosen) Color(0xFF30D158) else Color(0xFF1F1F2C), RoundedCornerShape(12.dp)),
                                                        colors = CardDefaults.cardColors(containerColor = if (isSkipChosen) Color(0x1C30D158) else Color(0xFF07070A)),
                                                        shape = RoundedCornerShape(12.dp)
                                                    ) {
                                                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            Text("🤷‍♂️", fontSize = 18.sp)
                                                            Spacer(modifier = Modifier.width(10.dp))
                                                            Text("الامتناع عن التصويت (Skip Vote)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                
                                                potentialSuspects.forEach { suspect ->
                                                    val isChosen = hostLocalVoteTargetId == suspect.id && !hostLocalHasSkippedVote
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 4.dp)
                                                            .clickable { 
                                                                hostLocalVoteTargetId = suspect.id
                                                                hostLocalHasSkippedVote = false 
                                                            }
                                                            .border(1.dp, if (isChosen) Color(0xFFFF453A) else Color(0xFF1F1F2C), RoundedCornerShape(12.dp)),
                                                        colors = CardDefaults.cardColors(containerColor = if (isChosen) Color(0x22FF453A) else Color(0xFF07070A)),
                                                        shape = RoundedCornerShape(12.dp)
                                                    ) {
                                                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            Text(suspect.avatar.ifEmpty { "👤" }, fontSize = 18.sp)
                                                            Spacer(modifier = Modifier.width(10.dp))
                                                            Text(suspect.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                
                                                Spacer(modifier = Modifier.height(10.dp))
                                                val hasMadeDecision = hostLocalVoteTargetId != null || hostLocalHasSkippedVote
                                                val isDayVoted = ostPlayer.targetVote != null
                                                Button(
                                                    onClick = { 
                                                        GameEngine.submitDayVote(ostPlayer.id, if (hostLocalHasSkippedVote) "SKIP" else hostLocalVoteTargetId) 
                                                    },
                                                    enabled = hasMadeDecision && !isDayVoted,
                                                    colors = ButtonDefaults.buttonColors(containerColor = if (isDayVoted) Color.Gray else Color(0xFFFF453A)),
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.fillMaxWidth().height(42.dp)
                                                ) {
                                                    Text(if (isDayVoted) "تم إرسال صوتك بنجاح 🔒" else "إرسال التصويت وتأكيده 🗳️", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            // Alive / Deceased Player List Tracking
                            Text(
                                text = "👤 قائمة اللاعبين النشطين ومصيرهم:",
                                color = Color.LightGray,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            // Compact Vertical row of players mapping status
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                players.forEach { p ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (p.isAlive) Color(0xFF0F0F14) else Color(0x14FF453A),
                                                RoundedCornerShape(14.dp)
                                            )
                                            .border(
                                                1.dp,
                                                if (p.isAlive) Color(0xFF1F1F2C) else Color(0x33FF453A),
                                                RoundedCornerShape(14.dp)
                                            )
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Base64Image(
                                                base64Str = p.avatar,
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .border(1.dp, if (p.isAlive) Color(0xFF1F1F2C) else Color(0x33FF453A), CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = p.name,
                                                    color = if (p.isAlive) Color.White else Color.Gray,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                val displayRole = if (phase == GamePhase.GAME_OVER) {
                                                    getRoleLabelArabic(p.role)
                                                } else if (!p.isAlive && GameEngine.settings.revealRoleOnDeath) {
                                                    getRoleLabelArabic(p.role)
                                                } else {
                                                    "دور سري 🤫"
                                                }
                                                Text(
                                                    text = displayRole,
                                                    color = Color.LightGray,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }

                                        if (!p.isAlive) {
                                            Text(
                                                text = "🥀 ميت",
                                                color = Color(0xFFFF453A),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        } else {
                                            Text(
                                                text = "💚 حيّ",
                                                color = Color(0xFF30D158),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                    if (phase == GamePhase.GAME_OVER) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Logs and Server Tickers
                        Text(
                            text = "📊 التفاصيل الشامل ومجريات التصويت التاريخية لبلدة الحنيوك:",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        // Scrollable log screen
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0C0F)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                GameEngine.voteHistoryList.forEach { logLine ->
                                    val isHeader = logLine.startsWith("🗳️") || logLine.startsWith("🏕️")
                                    val logColor = if (isHeader) Color(0xFFFEBC2C) else if (logLine.contains("←")) Color(0xFF90FF90) else Color.LightGray
                                    val logWeight = if (isHeader) FontWeight.Black else FontWeight.Normal
                                    Text(
                                        text = logLine,
                                        color = logColor,
                                        fontSize = 12.sp,
                                        fontWeight = logWeight,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Host overrides
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (phase == GamePhase.ROLE_REVEAL) {
                            Button(
                                onClick = { GameEngine.enterNight() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("إيقاظ ليل الموت 🏕️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (phase == GamePhase.DAY_DISCUSSION) {
                            Button(
                                onClick = { GameEngine.enterDayVoting() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("بدء التصويت الآن 🗳️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (phase == GamePhase.DAY_VOTING) {
                            Button(
                                onClick = { GameEngine.endDayVotingManually() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("إقامة الإعدام فوراً ❌", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (phase == GamePhase.GAME_OVER) {
                            Button(
                                onClick = { GameEngine.resetGameToLobby() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("إعادة اللعب والتشييد 🔁", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Force Exit Game button
                        Button(
                            onClick = { GameEngine.resetGameToLobby() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E24)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(0.8f)
                        ) {
                            Text("العودة للوبي 🏠", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    }
                }
            }
            }
        }
    }

    val currentVotingResult = latestVotingResult
    if (currentVotingResult != null) {
        FullScreenVotingResultOverlay(votingResult = currentVotingResult, onDismiss = { GameEngine.dismissVotingResultAndProceed() })
    }

    val currentNightVictim = latestNightVictim
    if (currentNightVictim != null) {
        FullScreenNightVictimOverlay(victim = currentNightVictim, onDismiss = { GameEngine.dismissNightVictim() })
    }
}
}

// Helpers GUI Toggles and layout items
@Composable
fun RoleAdjustRow(
    title: String,
    color: Color,
    count: Int,
    onPlus: () -> Unit,
    onMinus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(10.dp))
                    .clickable { onMinus() },
                contentAlignment = Alignment.Center
            ) {
                Text("-", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "$count",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(10.dp))
                    .clickable { onPlus() },
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun RoleCard(
    title: String,
    count: Int,
    color: Color,
    icon: String,
    onPlus: () -> Unit,
    onMinus: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
        border = BorderStroke(1.5.dp, color.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape)
                    .border(1.dp, color.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 26.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(10.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clickable { onMinus() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("-", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "$count",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clickable { onPlus() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ConfigOptionRow(
    title: String,
    leftLabel: String,
    rightLabel: String,
    isLeftSelected: Boolean,
    onToggle: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1C1C1E), RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isLeftSelected) Color(0xFFFF3B30).copy(alpha = 0.2f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        if (isLeftSelected) Color(0xFFFF3B30) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { if (!isLeftSelected) onToggle() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = leftLabel,
                    color = if (isLeftSelected) Color.White else Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (!isLeftSelected) Color(0xFFFF3B30).copy(alpha = 0.2f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        if (!isLeftSelected) Color(0xFFFF3B30) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { if (isLeftSelected) onToggle() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = rightLabel,
                    color = if (!isLeftSelected) Color.White else Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ConfigToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF34C759),
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color(0xFF1C1C1E)
            )
        )
    }
}

fun getPhaseTitle(phase: GamePhase): String {
    return when(phase) {
        GamePhase.LOBBY -> "غرفة الانتظار (Lobby)"
        GamePhase.ROLE_REVEAL -> "بطاقات الهوية السرية 🤫"
        GamePhase.NIGHT -> "🏕️ الليل يسود القرية (نوم الجميع)"
        GamePhase.DAY_DISCUSSION -> "☀️ نقاش النهار والمداولة"
        GamePhase.DAY_VOTING -> "🗳️ التصويت والبحث عن الحنيوك"
        GamePhase.GAME_OVER -> "🏆 انتهاء اللعبة الكبرى!"
    }
}

fun getPhaseColor(phase: GamePhase): Color {
    return when(phase) {
        GamePhase.ROLE_REVEAL -> Color.Yellow
        GamePhase.NIGHT -> Color(0xFF0A84FF)
        GamePhase.DAY_DISCUSSION -> Color.White
        GamePhase.DAY_VOTING -> Color(0xFFFF9500)
        GamePhase.GAME_OVER -> Color(0xFF34C759)
        else -> Color.Gray
    }
}

fun getRoleLabelArabic(role: String): String {
    return when(role) {
        "MAFIA" -> "مافيا 👺"
        "DOCTOR" -> "طبيب 🩺"
        "DETECTIVE" -> "محقق 🔎"
        else -> "مواطن صالح 😇"
    }
}

@Composable
fun GameOverHostView(
    players: List<Player>,
    onPlayAgain: () -> Unit
) {
    val winner = GameEngine.winnerTeam ?: "MAFIA"
    val isMafiaWin = winner == "MAFIA"
    
    // Gradient backgrounds and highlight colors based on winner
    val primaryColor = if (isMafiaWin) Color(0xFFFF453A) else Color(0xFF30D158)
    val containerBg = Color(0xFF0F0F14)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Grand Victory Banner Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = containerBg),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, Brush.linearGradient(listOf(primaryColor, Color(0xFF1F1F2C))))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large atmospheric visual
                Text(
                    text = if (isMafiaWin) "👺💀" else "🏆😇",
                    fontSize = 56.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Text(
                    text = if (isMafiaWin) "سقوط بلدة الحنيوك! 🩸" else "انتصار أهالي الحنيوك! ⚡",
                    color = primaryColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isMafiaWin) "فازت قوى المافيا والأشرار بالرأس والمكر والتمويه الدقيق!" else "انتصر شرفاء البنية وطهروا البلدة بالعدل من دنس المافيا اللعينة!",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        }

        // Quick Stats row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val aliveCount = players.count { it.isAlive }
            
            listOf(
                "ليالي الحرب 🌙" to "${GameEngine.currentNightCount} ليالي",
                "الأحياء الشامخين 👥" to "$aliveCount لاعبين",
                "محرك النصر 🏆" to if (isMafiaWin) "المافيا" else "الشرفاء"
            ).forEach { (label, value) ->
                Card(
                    modifier = Modifier.weight(1.5f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF1F1F2C))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(label, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        // Full Exposed Roles Card
        Text(
            text = "🔓 كواليس وأدوار كافة اللاعبين مسبقاً:",
            color = Color.LightGray,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121216)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF2C2C35))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                players.forEach { p ->
                    val roleLabel = getRoleLabelArabic(p.role)
                    val roleColor = when(p.role) {
                        "MAFIA" -> Color(0xFFFF3B30)
                        "DOCTOR" -> Color(0xFF30D158)
                        "DETECTIVE" -> Color(0xFF0A84FF)
                        else -> Color(0xFF8E8E93)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (p.isAlive) Color(0xFF1C1C1E) else Color(0xFF140A0A),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Base64Image(
                                base64Str = p.avatar,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, Color.DarkGray, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(p.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(roleLabel, color = roleColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (p.isAlive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF30D158))
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("حي وشامخ", color = Color(0xFF30D158), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text("🥀 ميت ومغدور", color = Color(0xFFFF453A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Full Historic Log Summary
        Text(
            text = "📜 سجل وقائع ملحمة بلدة الحنيوك الكامل:",
            color = Color.LightGray,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0C0F)),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF1E1E24))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                items(GameEngine.voteHistoryList) { logLine ->
                    val isHeader = logLine.startsWith("🗳️") || logLine.startsWith("🏕️")
                    val logColor = if (isHeader) Color(0xFFFEBC2C) else if (logLine.contains("←")) Color(0xFF30D158) else Color.LightGray
                    val logWeight = if (isHeader) FontWeight.Black else FontWeight.Normal
                    Text(
                        text = logLine,
                        color = logColor,
                        fontSize = 11.sp,
                        fontWeight = logWeight,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action controls
        Button(
            onClick = { onPlayAgain() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("إعادة اللعب والتشييد 🔁", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun NewsBanner(logs: List<String>) {
    val keyWords = listOf("مأساة", "معجزة", "هدوء", "قرار البلدة", "تعادل", "تطهير", "الأصوات", "فوز", "أشرقت")
    val latestNews = remember(logs) {
        logs.lastOrNull { log ->
            keyWords.any { keyword -> log.contains(keyword) }
        }
    }

    if (latestNews != null) {
        var title = "آخر أخبار ونقاشات بلدة الحنيوك 📰"
        var icon = "📰"
        var borderColor = Color.Gray
        var bg = Color(0xFF1C1C1E)

        if (latestNews.contains("مأساة")) {
            title = "مأساة الليلة الفائتة 🥀"
            icon = "🥀"
            borderColor = Color(0xFFFF3B30)
            bg = Color(0xFF2C1010)
        } else if (latestNews.contains("معجزة")) {
            title = "معجزة العناية 😇"
            icon = "😇"
            borderColor = Color(0xFF34C759)
            bg = Color(0xFF0F2C10)
        } else if (latestNews.contains("قرار البلدة")) {
            title = "قرار المحكمة والبلدة ⚖️"
            icon = "⚖️"
            borderColor = Color(0xFFFF9500)
            bg = Color(0xFF2C1A10)
        } else if (latestNews.contains("تعادل")) {
            title = "الملف المتعادل للبلدة ⚖️"
            icon = "⚖️"
            borderColor = Color.Yellow
            bg = Color(0xFF2C2C10)
        } else if (latestNews.contains("هدوء")) {
            title = "هدوء الليل الحذر 🤔"
            icon = "🤔"
            borderColor = Color(0xFF0A84FF)
            bg = Color(0xFF101C2C)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = bg)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = latestNews,
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun FullScreenVotingResultOverlay(
    votingResult: VotingResultData,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp)
            .clickable(enabled = false) {}, // Intercept clicks to prevent interaction behind
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            
            Text(
                text = "⚖️ محكمة العدل للبلدة ⚖️",
                color = Color(0xFFFEBC2C),
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            if (votingResult.isTie) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(Color(0xFF2C2C10), CircleShape)
                        .border(2.dp, Color.Yellow, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⚖️", fontSize = 52.sp)
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Text(
                    text = "تعادل الأصوات! 🤔",
                    color = Color.Yellow,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "سادت الريبة والشكوك أروقة المحكمة، ولم يتفق المواطنون على نفي أي مشتبه به اليوم وعاد الجميع لمنازلهم بحذر.",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            } else if (votingResult.victimName != null) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .background(Color(0xFF2C1010), CircleShape)
                        .border(2.dp, Color(0xFFFF3B30), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Base64Image(
                        base64Str = votingResult.victimAvatar ?: "👤",
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                    )
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Text(
                    text = "تم نفي اللاعب: [ ${votingResult.victimName} ] 🥀",
                    color = Color(0xFFFF3B30),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(10.dp))
                
                Text(
                    text = "بأغلبية أصوات المحكمة الحاضرة، تقررت إدانته وعزله نهائياً عن بلدة الحنيوك!",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "وكان في جعبته وأوراقه الرسمية دور:",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = votingResult.victimRoleStr ?: "مواطن صالح 😇",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Text(
                    text = "لم يقرر أحد! 🤷‍♂️",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(40.dp))
            
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFEBC2C)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(54.dp)
            ) {
                Text(
                    text = "متابعة مجريات اللعبة 🛡️",
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

fun getRoleThemeColor(role: String): Color {
    return when(role) {
        "DOCTOR" -> Color(0xFFE91E63)      // Bright Pink (Doctor/Nurse in Picture 1)
        "MAFIA" -> Color(0xFF9E0B0B)       // Dark Crimson Red (Evil)
        "DETECTIVE" -> Color(0xFF1976D2)   // Electric Blue (Investigation)
        else -> Color(0xFF4CAF50)          // Emerald Green (Purity / Citizen)
    }
}

@Composable
fun RoleIconIllustration(role: String) {
    val emoji = when(role) {
        "DOCTOR" -> "👩‍⚕️"
        "MAFIA" -> "👺"
        "DETECTIVE" -> "🔎"
        else -> "😇"
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, fontSize = 65.sp)
    }
}

@Composable
fun FullScreenNightVictimOverlay(
    victim: NightVictimData,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF5E6FCA)) // Beautiful steel blue background matching Picture 1
            .clickable(enabled = false) {}, // Intercept clicks
        contentAlignment = Alignment.Center
    ) {
        // Upper-right close "X" Button
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    .size(40.dp)
            ) {
                Text(
                    text = "✕",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Parent scroll layout
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (victim.hasVictim) {
                // Title
                Text(
                    text = "ضحايا الليلة الماضية:",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(40.dp))

                // Overlapping identity circles
                Box(
                    modifier = Modifier
                        .height(160.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // Left Circle: Role motif with white border
                    Box(
                        modifier = Modifier
                            .offset(x = (-45).dp)
                            .size(136.dp)
                            .border(6.dp, Color.White, CircleShape)
                            .background(getRoleThemeColor(victim.role), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        RoleIconIllustration(role = victim.role)
                    }

                    // Right Circle: Selfie avatar with white border
                    Box(
                        modifier = Modifier
                            .offset(x = 45.dp)
                            .size(136.dp)
                            .border(6.dp, Color.White, CircleShape)
                            .background(Color.DarkGray, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Base64Image(
                            base64Str = victim.avatar,
                            modifier = Modifier
                                .size(124.dp)
                                .clip(CircleShape)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))

                // Name
                Text(
                    text = victim.name,
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Role title Arabic
                val cleanRoleStr = victim.roleLabel.replace(Regex("[\\p{So}\\p{Cn}]"), "").trim()
                Text(
                    text = cleanRoleStr,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Game laws text
                Text(
                    text = "لا يسمح للضحية بالتكلم حتى نهاية اللعبة.",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            } else {
                // Night peace state helper
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape)
                        .border(4.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("😇", fontSize = 72.sp)
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "ليلة هادئة وسلام 💤",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "مرّت الليلة بسلام وأمان ولم تسقط أي ضحية! استيقظ جميع سكان البلدة وهم بصحة وعافية.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Done action button: 👑 تم
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .width(180.dp)
                    .height(50.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("👑", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "تم",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun HostIntegratedActiveScreen(
    hostPlayer: Player,
    players: List<Player>,
    phase: GamePhase,
    logs: List<String>,
    allowSkipVoteState: Boolean
) {
    var isHostRoleRevealed by remember { mutableStateOf(false) }
    var hostLocalVoteTargetId by remember { mutableStateOf<String?>(null) }
    var hostLocalHasSkippedVote by remember { mutableStateOf(false) }
    var hostNightTargetId by remember { mutableStateOf<String?>(null) }

    var hostMathNum1 by remember { mutableStateOf((3..15).random()) }
    var hostMathNum2 by remember { mutableStateOf((3..15).random()) }
    var hostMathInput by remember { mutableStateOf("") }
    var hostMathScore by remember { mutableStateOf(0) }

    // Synchronize state resets when GamePhase transitions
    LaunchedEffect(phase) {
        isHostRoleRevealed = false
        hostLocalVoteTargetId = null
        hostLocalHasSkippedVote = false
        hostNightTargetId = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // SERVER ADMIN QUICK CONTROLS CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1010)),
            border = BorderStroke(1.5.dp, Color(0xFFFF453A)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("👑", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "لوحة تحكم مشرف السيرفر",
                            color = Color(0xFFFF9500),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = getPhaseTitle(phase),
                        color = getPhaseColor(phase),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                when (phase) {
                    GamePhase.ROLE_REVEAL -> {
                        Button(
                            onClick = { GameEngine.enterNight() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("إيقاظ ليل الموت وبدء اللعبة 🏕️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    GamePhase.NIGHT -> {
                        Button(
                            onClick = { GameEngine.resolveNightPhaseAndEnterDay() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("أشرقت الشمس! إنهاء الليل وإيقاظ القرية ☀️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    GamePhase.DAY_DISCUSSION -> {
                        Button(
                            onClick = { GameEngine.enterDayVoting() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("فتح صناديق الاقتراع وبدء التصويت 🗳️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    GamePhase.DAY_VOTING -> {
                        Button(
                            onClick = { GameEngine.endDayVotingManually() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("إغلاق الصندوق وإقامة الإعدام فوراً ❌", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    GamePhase.GAME_OVER -> {
                        Button(
                            onClick = { GameEngine.resetGameToLobby() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("إعادة اللعب والتشييد لمواجهة جديدة 🔁", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    else -> {}
                }
            }
        }

        // NEWS BANNER FOR CONTEXT
        NewsBanner(logs = logs)

        // HOST PLAYER INTERACTIVE SCREEN CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
            border = BorderStroke(1.dp, Color(0xFF1F1F2C)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Base64Image(
                            base64Str = hostPlayer.avatar.ifEmpty { "👤" },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, Color(0xFF1F1F2C), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "شاشتي كلاعب (${hostPlayer.name})",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!hostPlayer.isAlive) {
                        Text(text = "🥀 ميت", color = Color(0xFFFF453A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text(text = "💚 حيّ", color = Color(0xFF30D158), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(modifier = Modifier.height(14.dp))
                
                when (phase) {
                    GamePhase.ROLE_REVEAL -> {
                        if (!isHostRoleRevealed) {
                            Button(
                                onClick = { isHostRoleRevealed = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text("كشف دوري السري 🫣", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            val label = getRoleLabelArabic(hostPlayer.role)
                            val desc = when (hostPlayer.role) {
                                "MAFIA" -> "أنت المافيا الشرير! تصفية الشرفاء بالليل والتضليل بالنهار... 👺"
                                "DOCTOR" -> "أنت الطبيب المنقذ! اعطِ مصل الحماية لمواطن كل ليلة... 🩺"
                                "DETECTIVE" -> "أنت المحقق الخارق! استجوب هوية مشتبه به كل ليلة... 🔎"
                                else -> "أنت مواطن شريف وصالح! تصديق الحدس والتصويت بالنهار... 😇"
                            }
                            val roleCol = when (hostPlayer.role) {
                                "MAFIA" -> Color(0xFFFF453A)
                                "DOCTOR" -> Color(0xFF34C759)
                                "DETECTIVE" -> Color(0xFF0A84FF)
                                else -> Color.White
                            }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF050508)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(12.dp))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = "دورك السري هو:", color = Color.Gray, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(text = label, color = roleCol, fontSize = 22.sp, fontWeight = FontWeight.Black)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(text = desc, color = Color.LightGray, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Button(
                                        onClick = { isHostRoleRevealed = false },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(34.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
                                    ) {
                                        Text("إخفاء دوري 🤫", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    GamePhase.NIGHT -> {
                        if (!hostPlayer.isAlive) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1010)),
                                border = BorderStroke(1.dp, Color(0xFFFF453A).copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("💀 لقد تم تصفيتك في اللعبة! 🥀", color = Color(0xFFFF453A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "منظور البصيرة الإشرافية الكاملة لمضيف الجلسة. يمكنك كشف أدوار ومخططات الأخرين لمراقبة الجيم بصمت ممتع:",
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        } else {
                            when (hostPlayer.role) {
                                "MAFIA" -> {
                                    val candidateVictims = players.filter { it.isAlive && it.role != "MAFIA" }
                                    Text("اختر مواطناً لتصفيته الليلة 👺:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    candidateVictims.forEach { candidate ->
                                        val isSelected = hostNightTargetId == candidate.id
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clickable { hostNightTargetId = candidate.id }
                                                .border(1.dp, if (isSelected) Color(0xFFFF453A) else Color(0xFF1F1F2C), RoundedCornerShape(12.dp)),
                                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0x22FF453A) else Color(0xFF07070A))
                                        ) {
                                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Base64Image(base64Str = candidate.avatar, modifier = Modifier.size(28.dp).clip(CircleShape))
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(candidate.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    val isMafiaVoted = hostPlayer.mafiaTarget != null
                                    Button(
                                        onClick = { GameEngine.submitMafiaVote(hostPlayer.id, hostNightTargetId) },
                                        enabled = hostNightTargetId != null,
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isMafiaVoted) Color.Gray else Color(0xFFFF453A)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text(if (isMafiaVoted) "تم تأكيد طلب التصفية نهاراً ⌛" else "تأكيد التصفية 🩸", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                "DOCTOR" -> {
                                    val candidateHeals = players.filter { it.isAlive && it.id != GameEngine.previousDoctorProtect }
                                    Text("اختر مواطناً لحمايته الليلة 🩺:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    candidateHeals.forEach { candidate ->
                                        val isSelected = hostNightTargetId == candidate.id
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clickable { hostNightTargetId = candidate.id }
                                                .border(1.dp, if (isSelected) Color(0xFF34C759) else Color(0xFF1F1F2C), RoundedCornerShape(12.dp)),
                                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0x1C34C759) else Color(0xFF07070A))
                                        ) {
                                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Base64Image(base64Str = candidate.avatar, modifier = Modifier.size(28.dp).clip(CircleShape))
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(candidate.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    val isDoctorVoted = hostPlayer.doctorTarget != null
                                    Button(
                                        onClick = { GameEngine.submitDoctorVote(hostPlayer.id, hostNightTargetId) },
                                        enabled = hostNightTargetId != null,
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isDoctorVoted) Color.Gray else Color(0xFF34C759)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text(if (isDoctorVoted) "تم إرسال ترياق العلاج ⌛" else "تأكيد ترياق الحماية 🧪", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                "DETECTIVE" -> {
                                    val candidateInquiries = players.filter { it.isAlive && it.id != hostPlayer.id }
                                    Text("اختر مشتبهاً لاستجوابه الليلة 🔎:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    candidateInquiries.forEach { candidate ->
                                        val isSelected = hostNightTargetId == candidate.id
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clickable { hostNightTargetId = candidate.id }
                                                .border(1.dp, if (isSelected) Color(0xFF0A84FF) else Color(0xFF1F1F2C), RoundedCornerShape(12.dp)),
                                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0x1C0A84FF) else Color(0xFF07070A))
                                        ) {
                                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Base64Image(base64Str = candidate.avatar, modifier = Modifier.size(28.dp).clip(CircleShape))
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(candidate.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    val detectiveResult by GameEngine.detectiveResult.collectAsState()
                                    if (detectiveResult != null && detectiveResult?.playerId == hostNightTargetId) {
                                        val isTargetMafia = detectiveResult?.isMafia == true
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Color(0x1A0A84FF)),
                                            border = BorderStroke(1.dp, Color(0xFF0A84FF).copy(alpha = 0.5f)),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "نتيجة الفحص 🔎: هذا اللاعب هو ${if (isTargetMafia) "مافيا شرير! 👺" else "شريف صالح! 😇"}",
                                                color = if (isTargetMafia) Color(0xFFFF453A) else Color(0xFF30D158),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(10.dp),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                    val isDetectiveVoted = hostPlayer.detectiveTarget != null
                                    Button(
                                        onClick = { GameEngine.submitDetectiveVote(hostPlayer.id, hostNightTargetId) },
                                        enabled = hostNightTargetId != null,
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isDetectiveVoted) Color.Gray else Color(0xFF0A84FF)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text(if (isDetectiveVoted) "تم الفحص وسجلت الهوية ⌛" else "استجواب الهوية 🔎", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                else -> {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF040406)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(12.dp))
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text("🧮 مهمة حماية السمع والتركيز لمنع غش الصوت", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text("قم بحل المسألة الرياضية للتسلية ومنع تركيز السمع الجانبي:", color = Color.Gray, fontSize = 10.sp, textAlign = TextAlign.Center)
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = "$hostMathNum1 + $hostMathNum2 = ?",
                                                color = Color(0xFF30D158),
                                                fontSize = 28.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            OutlinedTextField(
                                                value = hostMathInput,
                                                onValueChange = { hostMathInput = it },
                                                placeholder = { Text("اكتب الإجابة هنا...", fontSize = 11.sp, color = Color.DarkGray) },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(0.6f),
                                                textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color(0xFF30D158),
                                                    unfocusedBorderColor = Color(0xFF2C2C2E)
                                                )
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Button(
                                                onClick = {
                                                    val answer = hostMathInput.trim().toIntOrNull()
                                                    if (answer == (hostMathNum1 + hostMathNum2)) {
                                                        hostMathScore += 10
                                                        hostMathNum1 = (3..15).random()
                                                        hostMathNum2 = (3..15).random()
                                                        hostMathInput = ""
                                                    } else {
                                                        hostMathInput = ""
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Text("إرسال الإجابة ✅", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text("النقاط الرياضية الحالية: $hostMathScore 🪙", color = Color(0xFFFFD60A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    GamePhase.DAY_DISCUSSION -> {
                        Text("نقاش في النهار! تحاور وتبادل التهم مع رفاقك لحماية بلدتكم... 🗣️☀️", color = Color.LightGray, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    GamePhase.DAY_VOTING -> {
                        if (!hostPlayer.isAlive) {
                            Text("أنت ميت! لا يحق لك التصويت بالنهار... 🥀", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            val potentialSuspects = players.filter { it.isAlive && it.id != hostPlayer.id }
                            Text("اختر المشتبه به لتصوت لنفيه من بلدتكم ⚖️:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            if (allowSkipVoteState) {
                                val isSkipChosen = hostLocalHasSkippedVote
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { 
                                            hostLocalVoteTargetId = "SKIP"
                                            hostLocalHasSkippedVote = true 
                                        }
                                        .border(1.dp, if (isSkipChosen) Color(0xFF30D158) else Color(0xFF1F1F2C), RoundedCornerShape(12.dp)),
                                    colors = CardDefaults.cardColors(containerColor = if (isSkipChosen) Color(0x1C30D158) else Color(0xFF07070A))
                                ) {
                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("🤷‍♂️", fontSize = 18.sp)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("الامتناع عن التصويت (Skip Vote)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            
                            potentialSuspects.forEach { suspect ->
                                val isChosen = hostLocalVoteTargetId == suspect.id && !hostLocalHasSkippedVote
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { 
                                            hostLocalVoteTargetId = suspect.id
                                            hostLocalHasSkippedVote = false 
                                        }
                                        .border(1.dp, if (isChosen) Color(0xFFFF453A) else Color(0xFF1F1F2C), RoundedCornerShape(12.dp)),
                                    colors = CardDefaults.cardColors(containerColor = if (isChosen) Color(0x22FF453A) else Color(0xFF07070A))
                                ) {
                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Base64Image(base64Str = suspect.avatar, modifier = Modifier.size(28.dp).clip(CircleShape))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(suspect.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(10.dp))
                            val hasMadeDecision = hostLocalVoteTargetId != null || hostLocalHasSkippedVote
                            val isDayVoted = hostPlayer.targetVote != null
                            Button(
                                onClick = { 
                                    GameEngine.submitDayVote("HOST_PLAYER_ID", if (hostLocalHasSkippedVote) "SKIP" else hostLocalVoteTargetId) 
                                },
                                enabled = hasMadeDecision && !isDayVoted,
                                colors = ButtonDefaults.buttonColors(containerColor = if (isDayVoted) Color.Gray else Color(0xFFFF453A)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().height(42.dp)
                              ) {
                                Text(if (isDayVoted) "تم إرسال صوتك بنجاح 🔒" else "إرسال التصويت وتأكيده 🗳️", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    GamePhase.GAME_OVER -> {
                        Text("لقد انتهت اللعبة الكبرى! تفقد النتائج وخلاصة المواجهة بالأسفل 🏁📊", color = Color(0xFF34C759), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    else -> {}
                }
            }
        }

        // ALIVE/DECEASED PLAYER LIST
        Text(
            text = "👥 قائمة اللاعبين والترتيب الحالي لبلدة الحنيوك:",
            color = Color.LightGray,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            players.forEach { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (p.isAlive) Color(0xFF0F0F14) else Color(0x14FF453A),
                            RoundedCornerShape(14.dp)
                        )
                        .border(
                            1.dp,
                            if (p.isAlive) Color(0xFF1F1F2C) else Color(0x33FF453A),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Base64Image(
                            base64Str = p.avatar,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .border(1.dp, if (p.isAlive) Color(0xFF1F1F2C) else Color(0x33FF453A), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = p.name,
                                color = if (p.isAlive) Color.White else Color.Gray,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val displayRole = if (phase == GamePhase.GAME_OVER) {
                                getRoleLabelArabic(p.role)
                            } else if (!p.isAlive && GameEngine.settings.revealRoleOnDeath) {
                                getRoleLabelArabic(p.role)
                            } else {
                                "دور سري 🤫"
                            }
                            Text(
                                text = displayRole,
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    if (!p.isAlive) {
                        Text("🥀 ميت", color = Color(0xFFFF453A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("💚 حيّ", color = Color(0xFF30D158), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // VOTE HISTORIES IN GAME OVER
        if (phase == GamePhase.GAME_OVER) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "📊 محضر وتفاصيل التصويت التاريخي للبلدة:",
                color = Color.LightGray,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0C0F)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GameEngine.voteHistoryList.forEach { logLine ->
                        val isHeader = logLine.startsWith("🗳️") || logLine.startsWith("🏕️")
                        val logColor = if (isHeader) Color(0xFFFEBC2C) else if (logLine.contains("←")) Color(0xFF90FF90) else Color.LightGray
                        Text(
                            text = logLine,
                            color = logColor,
                            fontSize = 12.sp,
                            fontWeight = if (isHeader) FontWeight.Black else FontWeight.Normal,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}


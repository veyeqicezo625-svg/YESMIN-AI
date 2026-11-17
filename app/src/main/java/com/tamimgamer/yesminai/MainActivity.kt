package com.tamimgamer.yesminai

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val FREEMODEL_BASE_URL = "https://freemodel.in/v1"
private val Night = Color(0xFF090B14)
private val Panel = Color(0xFF151A2B)
private val Purple = Color(0xFF8B5CF6)
private val Blue = Color(0xFF38BDF8)
private val White = Color(0xFFF4F3FF)
private val http = OkHttpClient()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("yesmin_settings", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Blue, background = Night, surface = Panel, onBackground = White, onSurface = White)) {
                YesminApp(
                    initialKey = prefs.getString("freemodel_key", "") ?: "",
                    initialModel = prefs.getString("freemodel_model", "deepseek-v3.2") ?: "deepseek-v3.2",
                    initialVoiceGender = prefs.getString("voice_gender", "male") ?: "male",
                    initialVoiceStyle = prefs.getString("voice_style", "natural") ?: "natural",
                    saveSettings = { key, model, gender, style ->
                        prefs.edit().putString("freemodel_key", key).putString("freemodel_model", model)
                            .putString("voice_gender", gender).putString("voice_style", style).apply()
                    }
                )
            }
        }
    }
}

private data class ChatMessage(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YesminApp(initialKey: String, initialModel: String, initialVoiceGender: String, initialVoiceStyle: String, saveSettings: (String, String, String, String) -> Unit) {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(initialKey) }
    var model by remember { mutableStateOf(initialModel) }
    var voiceGender by remember { mutableStateOf(initialVoiceGender) }
    var voiceStyle by remember { mutableStateOf(initialVoiceStyle) }
    var draftVoiceGender by remember { mutableStateOf(initialVoiceGender) }
    var draftVoiceStyle by remember { mutableStateOf(initialVoiceStyle) }
    var showSettings by remember { mutableStateOf(initialKey.isBlank()) }
    var draftKey by remember { mutableStateOf(initialKey) }
    var draftModel by remember { mutableStateOf(initialModel) }
    var input by remember { mutableStateOf("") }
    var voiceSubmission by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var pendingControl by remember { mutableStateOf<String?>(null) }
    var availableModels by remember { mutableStateOf(listOf<String>()) }
    var status by remember { mutableStateOf(if (initialKey.isBlank()) "FreeModel API key সেট করো" else "FreeModel • $initialModel") }
    val messages = remember {
        mutableStateListOf(ChatMessage("হ্যালো! আমি Yesmin AI। 💜\nমাইক্রোফোন চাপো এবং কথা বলো।", false))
    }
    val scope = rememberCoroutineScope()

    val tts = remember(context) {
        TextToSpeech(context) { result ->
            if (result == TextToSpeech.ERROR) status = "Voice unavailable"
        }
    }
    DisposableEffect(tts) {
        onDispose { tts.stop(); tts.shutdown() }
    }

    val recognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    DisposableEffect(recognizer) {
        onDispose { recognizer?.destroy() }
    }
    if (recognizer != null) {
        SideEffect {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true; status = "শুনছি…" }
                override fun onBeginningOfSpeech() { listening = true }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; status = "কথা বুঝছি…" }
                override fun onError(error: Int) { listening = false; status = "Voice input হয়নি, আবার চেষ্টা করো" }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) voiceSubmission = spoken
                    status = "Voice ready"
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) input = partial
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    val startListening: () -> Unit = {
        if (recognizer == null) {
            status = "এই ফোনে Speech Recognition নেই"
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "YESMIN AI-কে বলো…")
            }
            recognizer.startListening(intent)
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else status = "Voice ব্যবহারে Microphone permission দরকার"
    }
    val assistantMicPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val serviceIntent = Intent(context, YesminVoiceService::class.java).setAction(YesminVoiceService.ACTION_START)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
            status = "YESMIN Floating Assistant চালু হয়েছে"
        } else status = "Floating Assistant চালাতে Microphone permission দরকার"
    }

    fun speak(text: String) {
        if (text.isNotBlank()) {
            tts.language = Locale("bn", "BD")
            val marker = if (voiceGender == "male") listOf("male", "man", "masculine") else listOf("female", "woman", "feminine")
            val selectedVoice = tts.voices?.firstOrNull { voice ->
                voice.locale.language == "bn" && marker.any { voice.name.lowercase(Locale.ROOT).contains(it) }
            }
            if (selectedVoice != null) tts.voice = selectedVoice
            tts.setPitch(if (selectedVoice != null) 1.0f else if (voiceGender == "male") 0.86f else 1.08f)
            tts.setSpeechRate(if (voiceStyle == "natural") 1.0f else 1.08f)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yesmin_reply")
        }
    }

    fun sendMessage() {
        val prompt = input.trim()
        if (prompt.isNotEmpty() && !busy) {
            messages.add(ChatMessage(prompt, true))
            input = ""
            val control = parseScreenControlCommand(prompt)
            if (control != null) {
                if (!YesminAccessibilityService.isEnabled(context)) {
                    messages.add(ChatMessage("স্ক্রিন নিয়ন্ত্রণের জন্য Accessibility permission চালু করতে হবে। নিচের Enable button চাপো।", false))
                    status = "Screen control permission required"
                } else {
                    pendingControl = control
                    status = "তোমার অনুমতির অপেক্ষায়"
                }
                return
            }
            val deviceReply = handleSafeAppCommand(context, prompt)
            if (deviceReply != null) {
                messages.add(ChatMessage(deviceReply, false))
                status = "Device command"
                speak(deviceReply)
                return
            }
            if (apiKey.isBlank()) {
                messages.add(ChatMessage("আগে Settings থেকে FreeModel API key সেট করো।", false))
                status = "API key required"
                return
            }
            busy = true
            status = "Thinking • $model"
            scope.launch {
                val answer = askFreeModel(apiKey, model, prompt)
                messages.add(ChatMessage(answer, false))
                busy = false
                status = if (answer.startsWith("সমস্যা:")) "Connection issue" else "FreeModel • $model"
                if (!answer.startsWith("সমস্যা:")) speak(answer)
            }
        }
    }

    LaunchedEffect(voiceSubmission) {
        val spoken = voiceSubmission ?: return@LaunchedEffect
        voiceSubmission = null
        input = spoken
        sendMessage()
    }

    Column(Modifier.fillMaxSize().background(Night).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("YESMIN AI", style = MaterialTheme.typography.headlineSmall, color = White)
                Text(if (listening) "🎙️ শুনছি…" else status, style = MaterialTheme.typography.labelSmall, color = Blue)
            }
            Row {
                IconButton(onClick = { messages.lastOrNull { !it.fromUser }?.let { speak(it.text) } }) {
                    Icon(Icons.Default.VolumeUp, contentDescription = "Speak latest reply", tint = White)
                }
                IconButton(onClick = {
                    draftKey = apiKey; draftModel = model
                    draftVoiceGender = voiceGender; draftVoiceStyle = voiceStyle
                    showSettings = true
                }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = White)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages) { msg ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (msg.fromUser) Color(0xFF30245B) else Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 310.dp)) {
                        Text(msg.text, color = White, modifier = Modifier.padding(14.dp))
                    }
                }
            }
            if (busy) item { Text("Yesmin লিখছে…", color = Blue) }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(
                onClick = {
                    if (listening) {
                        recognizer?.stopListening()
                        listening = false
                    } else if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startListening()
                    } else {
                        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !busy,
                modifier = Modifier.size(76.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Speak to YESMIN", modifier = Modifier.size(34.dp), tint = if (listening) Blue else White)
            }
            Spacer(Modifier.height(6.dp))
            Text(if (listening) "YESMIN শুনছে… কথা শেষ হলে নিজে থেকেই পাঠাবে" else "কথা বলতে মাইক্রোফোন চাপো", color = Blue, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                if (!Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    val serviceIntent = Intent(context, YesminVoiceService::class.java).setAction(YesminVoiceService.ACTION_START)
                    if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
                    status = "YESMIN Floating Assistant চালু হয়েছে"
                } else {
                    assistantMicPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start Floating Voice Assistant") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text(if (YesminAccessibilityService.isEnabled(context)) "Screen control enabled" else "Enable screen control")
            }
        }
        if (apiKey.isBlank()) TextButton(onClick = { showSettings = true }) { Text("FreeModel API key সেটিংস খুলুন") }
    }

    if (pendingControl != null) {
        AlertDialog(
            onDismissRequest = { pendingControl = null },
            title = { Text("YESMIN-এর অনুমতি প্রয়োজন") },
            text = { Text("আমি এই কাজটি করতে যাচ্ছি:\\n\\n${pendingControl}\\n\\nতুমি Allow চাপলে তবেই কাজটি হবে।") },
            confirmButton = { TextButton(onClick = {
                val result = YesminAccessibilityService.executeApproved(pendingControl.orEmpty())
                messages.add(ChatMessage(result, false))
                speak(result)
                status = "Screen action completed"
                pendingControl = null
            }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { pendingControl = null; status = "Action cancelled" }) { Text("Cancel") } },
            containerColor = Panel, titleContentColor = White, textContentColor = White
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("YESMIN Settings") },
            text = {
                Column {
                    Text("Voice Settings", style = MaterialTheme.typography.titleMedium, color = Blue)
                    Text("Voice Gender")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draftVoiceGender == "male", onClick = { draftVoiceGender = "male" }, label = { Text("Male") })
                        FilterChip(selected = draftVoiceGender == "female", onClick = { draftVoiceGender = "female" }, label = { Text("Female") })
                    }
                    Text("Voice Style")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draftVoiceStyle == "natural", onClick = { draftVoiceStyle = "natural" }, label = { Text("Natural") })
                        FilterChip(selected = draftVoiceStyle == "friendly", onClick = { draftVoiceStyle = "friendly" }, label = { Text("Friendly") })
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("FreeModel API Setup", style = MaterialTheme.typography.titleMedium, color = Blue)
                    Text("FreeModel Dashboard → API Keys থেকে fm_ দিয়ে শুরু হওয়া key তৈরি করো। Key কাউকে শেয়ার করবে না।")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = draftKey, onValueChange = { draftKey = it }, label = { Text("FreeModel API key (fm_…)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Spacer(Modifier.height(8.dp))
                    Text("API Base URL: $FREEMODEL_BASE_URL", style = MaterialTheme.typography.bodySmall, color = Blue)
                    Spacer(Modifier.height(8.dp))
                    Box {
                        OutlinedTextField(value = draftModel, onValueChange = { draftModel = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(onClick = { modelMenu = true }) { Text("List") } })
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            availableModels.forEach { id -> DropdownMenuItem(text = { Text(id) }, onClick = { draftModel = id; modelMenu = false }) }
                        }
                    }
                    TextButton(enabled = draftKey.startsWith("fm_") && !loadingModels, onClick = {
                        loadingModels = true
                        scope.launch {
                            val result = fetchFreeModelModels(draftKey.trim())
                            availableModels = result.first
                            status = result.second
                            loadingModels = false
                            if (availableModels.isNotEmpty() && draftModel !in availableModels) draftModel = availableModels.first()
                            modelMenu = availableModels.isNotEmpty()
                        }
                    }) { Text(if (loadingModels) "Loading…" else "Load available models") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apiKey = draftKey.trim()
                    model = draftModel.trim().ifBlank { "deepseek-v3.2" }
                    voiceGender = draftVoiceGender
                    voiceStyle = draftVoiceStyle
                    saveSettings(apiKey, model, voiceGender, voiceStyle)
                    status = if (apiKey.isBlank()) "FreeModel API key সেট করো" else "FreeModel • $model"
                    showSettings = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } },
            containerColor = Panel, titleContentColor = White, textContentColor = White
        )
    }
}


private fun parseScreenControlCommand(raw: String): String? {
    val value = raw.trim()
    val lower = value.lowercase(Locale.ROOT)
    return when {
        lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("চাপো ") || lower.startsWith("ট্যাপ ") ->
            "tap:" + value.substringAfter(' ').trim().takeIf { it.isNotBlank() }.orEmpty()
        lower == "scroll down" || lower == "নিচে স্ক্রল করো" -> "scroll:down"
        lower == "scroll up" || lower == "উপরে স্ক্রল করো" -> "scroll:up"
        lower == "press back" || lower == "go back" || lower == "পেছনে যাও" -> "back"
        lower == "read screen" || lower == "স্ক্রিনে কী আছে বলো" -> "read"
        else -> null
    }
}

internal fun findInstalledApp(context: android.content.Context, raw: String): Pair<String, String>? {
    val command = raw.trim().lowercase(Locale.ROOT)
    val verbs = listOf("open the", "open", "launch", "খুলে দাও", "চালু করো", "খোলো", "ওপেন", "খুলুন", "চালু", "যাও", "khol", "open")
    if (verbs.none { command.contains(it) }) return null
    var target = command
    verbs.sortedByDescending { it.length }.forEach { target = target.replace(it, " ") }
    listOf("আমার ফোনে", "ফোনে", "অ্যাপ", "app", "please", "দয়া করে").forEach { target = target.replace(it, " ") }
    target = target.replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
    if (target.isBlank()) return null

    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = context.packageManager.queryIntentActivities(launcher, 0)
    val match = activities.firstOrNull { info ->
        val label = info.loadLabel(context.packageManager).toString().lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        label.isNotBlank() && (target == label || target.endsWith(" $label") || (target.length >= 3 && label == target.substringAfterLast(" ")))
    } ?: return null
    val packageName = match.activityInfo.packageName
    val label = match.loadLabel(context.packageManager).toString()
    return packageName to label
}

private fun handleSafeAppCommand(context: android.content.Context, raw: String): String? {
    val target = findInstalledApp(context, raw)
    val command = raw.lowercase(Locale.ROOT)
    val hasOpenVerb = listOf("open", "launch", "খোলো", "খুলে দাও", "ওপেন", "চালু", "যাও", "khol").any { command.contains(it) }
    if (!hasOpenVerb) return null
    if (target == null) return "এই নামে কোনো ইনস্টল করা অ্যাপ খুঁজে পেলাম না। অ্যাপের নাম বলে আবার চেষ্টা করো।"
    val launch = context.packageManager.getLaunchIntentForPackage(target.first)
    return if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        "${target.second} খুলছি।"
    } else {
        "${target.second} সরাসরি খোলা যাচ্ছে না।"
    }
}

private suspend fun fetchFreeModelModels(apiKey: String): Pair<List<String>, String> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url("$FREEMODEL_BASE_URL/models").header("Authorization", "Bearer $apiKey").get().build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@withContext Pair(emptyList(), "Model list error: HTTP ${response.code}")
            val data = JSONObject(raw).optJSONArray("data") ?: JSONArray()
            val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
            Pair(ids, if (ids.isEmpty()) "কোনো model পাওয়া যায়নি" else "Models loaded")
        }
    } catch (e: Exception) { Pair(emptyList(), "Model list load failed") }
}

private suspend fun askFreeModel(apiKey: String, model: String, prompt: String): String = withContext(Dispatchers.IO) {
    try {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You are Yesmin, a warm, caring, friendly AI assistant. You are an AI, not a human. Reply in the user's language, especially Bangla when appropriate."))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject().put("model", model).put("messages", messages).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$FREEMODEL_BASE_URL/chat/completions")
            .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json").post(body).build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                return@withContext "সমস্যা: FreeModel API HTTP ${response.code}. ${detail ?: "API key, model বা usage limit পরীক্ষা করো।"}"
            }
            val choices = JSONObject(raw).optJSONArray("choices") ?: JSONArray()
            choices.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.takeIf(String::isNotBlank)
                ?: "সমস্যা: API থেকে খালি উত্তর এসেছে।"
        }
    } catch (e: Exception) { "সমস্যা: ইন্টারনেট বা FreeModel সংযোগে সমস্যা হয়েছে। আবার চেষ্টা করো।" }
}

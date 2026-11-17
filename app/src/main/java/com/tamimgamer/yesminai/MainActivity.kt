package com.tamimgamer.yesminai

import android.content.Intent
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
                    saveSettings = { key, model ->
                        prefs.edit().putString("freemodel_key", key).putString("freemodel_model", model).apply()
                    }
                )
            }
        }
    }
}

private data class ChatMessage(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YesminApp(initialKey: String, initialModel: String, saveSettings: (String, String) -> Unit) {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(initialKey) }
    var model by remember { mutableStateOf(initialModel) }
    var showSettings by remember { mutableStateOf(initialKey.isBlank()) }
    var draftKey by remember { mutableStateOf(initialKey) }
    var draftModel by remember { mutableStateOf(initialModel) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf(listOf<String>()) }
    var status by remember { mutableStateOf(if (initialKey.isBlank()) "FreeModel API key সেট করো" else "FreeModel • $initialModel") }
    val messages = remember {
        mutableStateListOf(ChatMessage("হ্যালো! আমি Yesmin AI। 💜\nমাইক্রোফোনে কথা বলো অথবা লিখে পাঠাও।", false))
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
                    if (!spoken.isNullOrBlank()) input = spoken
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

    fun speak(text: String) {
        if (text.isNotBlank()) {
            tts.language = Locale("bn", "BD")
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yesmin_reply")
        }
    }

    fun sendMessage() {
        val prompt = input.trim()
        if (prompt.isNotEmpty() && !busy && apiKey.isNotBlank()) {
            messages.add(ChatMessage(prompt, true))
            input = ""
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
                IconButton(onClick = { draftKey = apiKey; draftModel = model; showSettings = true }) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("লিখো বা মাইক্রোফোন চাপো…") }, shape = RoundedCornerShape(24.dp), maxLines = 4)
            Spacer(Modifier.width(4.dp))
            IconButton(
                enabled = !busy,
                onClick = {
                    if (listening) {
                        recognizer?.stopListening()
                        listening = false
                    } else if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startListening()
                    } else {
                        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
            ) { Icon(Icons.Default.Mic, contentDescription = "Voice input", tint = if (listening) Blue else White) }
            FilledIconButton(onClick = { sendMessage() }, enabled = input.isNotBlank() && !busy && apiKey.isNotBlank()) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
        if (apiKey.isBlank()) TextButton(onClick = { showSettings = true }) { Text("FreeModel API key সেটিংস খুলুন") }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("FreeModel API সেটআপ") },
            text = {
                Column {
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
                    saveSettings(apiKey, model)
                    status = if (apiKey.isBlank()) "FreeModel API key সেট করো" else "FreeModel • $model"
                    showSettings = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } },
            containerColor = Panel, titleContentColor = White, textContentColor = White
        )
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

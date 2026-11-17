package com.tamimgamer.yesminai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private val Night = Color(0xFF090B14)
private val Panel = Color(0xFF151A2B)
private val Purple = Color(0xFF8B5CF6)
private val Blue = Color(0xFF38BDF8)
private val White = Color(0xFFF4F3FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("yesmin_settings", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Blue, background = Night, surface = Panel, onBackground = White, onSurface = White)) {
                YesminApp(
                    initialKey = prefs.getString("gemini_key", "") ?: "",
                    saveKey = { prefs.edit().putString("gemini_key", it).apply() }
                )
            }
        }
    }
}

private data class ChatMessage(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YesminApp(initialKey: String, saveKey: (String) -> Unit) {
    var apiKey by remember { mutableStateOf(initialKey) }
    var showSettings by remember { mutableStateOf(initialKey.isBlank()) }
    var draftKey by remember { mutableStateOf(initialKey) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (initialKey.isBlank()) "Gemini API key সেট করো" else "Ready") }
    val messages = remember {
        mutableStateListOf(ChatMessage("হ্যালো! আমি Yesmin AI। 💜\nবাংলায় কথা বলতে এবং coding-এ সাহায্য করতে প্রস্তুত।", false))
    }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Night).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("YESMIN AI", style = MaterialTheme.typography.headlineSmall, color = White)
                Text(status, style = MaterialTheme.typography.labelSmall, color = Blue)
            }
            IconButton(onClick = { draftKey = apiKey; showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = White)
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
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Yesmin-কে কিছু বলো…") }, shape = RoundedCornerShape(24.dp), maxLines = 4)
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    val prompt = input.trim()
                    if (prompt.isNotEmpty() && !busy) {
                        messages.add(ChatMessage(prompt, true))
                        input = ""
                        busy = true
                        status = "Thinking…"
                        scope.launch {
                            val answer = askGemini(apiKey, prompt)
                            messages.add(ChatMessage(answer, false))
                            busy = false
                            status = if (answer.startsWith("সমস্যা:")) "Connection issue" else "Ready"
                        }
                    }
                },
                enabled = input.isNotBlank() && !busy && apiKey.isNotBlank()
            ) { Icon(Icons.Default.Send, contentDescription = "Send") }
        }
        if (apiKey.isBlank()) TextButton(onClick = { showSettings = true }) { Text("Gemini API key সেটিংস খুলুন") }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Gemini সেটআপ") },
            text = {
                Column {
                    Text("Google AI Studio থেকে নিজের Gemini API key তৈরি করে এখানে বসাও। Key কাউকে শেয়ার করবে না।")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = draftKey, onValueChange = { draftKey = it }, label = { Text("Gemini API key") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apiKey = draftKey.trim()
                    saveKey(apiKey)
                    status = if (apiKey.isBlank()) "Gemini API key সেট করো" else "Ready"
                    showSettings = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } },
            containerColor = Panel,
            titleContentColor = White,
            textContentColor = White
        )
    }
}

private suspend fun askGemini(apiKey: String, prompt: String): String = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient()
        val contents = JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        val body = JSONObject().put("contents", contents).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@withContext "সমস্যা: Gemini API " + response.code + "। API key এবং quota পরীক্ষা করো।"
            val json = JSONObject(raw)
            json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        }
    } catch (e: Exception) {
        "সমস্যা: ইন্টারনেট বা Gemini সংযোগে সমস্যা হয়েছে। আবার চেষ্টা করো।"
    }
}

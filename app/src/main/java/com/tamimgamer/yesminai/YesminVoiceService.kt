package com.tamimgamer.yesminai

import android.app.Notification
import android.app.NotificationChannel
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale

class YesminVoiceService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var listening = false
    private var stopped = false
    private var statusView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        createNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initVoice()
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAssistant(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification())
        if (!stopped) speak("আমি শুনছি। আমাকে বলো কী করতে হবে।")
        return START_STICKY
    }

    private fun createNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "YESMIN Voice Assistant", android.app.NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("YESMIN AI চালু আছে")
        .setContentText("ভয়েস সহকারী চলছে। বন্ধ করতে Bubble-এর STOP চাপো।")
        .setOngoing(true).build()

    private fun initVoice() {
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale("bn", "BD")
                val male = tts?.voices?.firstOrNull { v -> v.locale.language == "bn" && listOf("male", "man", "masculine").any { m -> v.name.lowercase(Locale.ROOT).contains(m) } }
                if (male != null) tts?.voice = male else tts?.setPitch(0.86f)
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { handler.post { if (!stopped) startListening() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { handler.post { if (!stopped) startListening() } }
                })
                startListening()
            }
        }
    }

    private fun speak(message: String) {
        if (stopped || message.isBlank()) return
        statusView?.text = message.take(50)
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "yesmin_voice_${System.currentTimeMillis()}")
    }

    private fun startListening() {
        if (stopped || listening || !SpeechRecognizer.isRecognitionAvailable(this)) return
        try {
            if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true; statusView?.text = "শুনছি…" }
                override fun onBeginningOfSpeech() { listening = true; statusView?.text = "শুনছি…" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; statusView?.text = "বুঝছি…" }
                override fun onError(error: Int) { listening = false; if (!stopped) handler.postDelayed({ startListening() }, 900) }
                override fun onResults(results: android.os.Bundle?) {
                    listening = false
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (heard.isNotBlank()) handleVoiceCommand(heard) else if (!stopped) handler.postDelayed({ startListening() }, 400)
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) { listening = false; handler.postDelayed({ startListening() }, 1500) }
    }

    private fun handleVoiceCommand(spoken: String) {
        val command = spoken.lowercase(Locale.ROOT).trim()
        if (listOf("yesmin stop", "stop yesmin", "থামো", "বন্ধ করো", "স্টপ", "বন্ধ হও").any { command.contains(it) }) { stopAssistant(); return }
        val apps = listOf(
            Triple(listOf("youtube", "ইউটিউব"), "com.google.android.youtube", "YouTube"),
            Triple(listOf("chrome", "ক্রোম"), "com.android.chrome", "Chrome"),
            Triple(listOf("roblox", "রোবলক্স"), "com.roblox.client", "Roblox"),
            Triple(listOf("whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ"), "com.whatsapp", "WhatsApp"),
            Triple(listOf("facebook", "ফেসবুক"), "com.facebook.katana", "Facebook"),
            Triple(listOf("settings", "সেটিংস"), "com.android.settings", "Settings")
        )
        val match = apps.firstOrNull { item -> item.first.any { command.contains(it) } && listOf("খোলো", "ওপেন", "open", "চালু", "যাও").any(command::contains) }
        if (match != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(match.second)
            if (launchIntent == null) { speak("${match.third} অ্যাপটি এই ফোনে নেই।"); return }
            speak("ঠিক আছে। আগে বর্তমান অ্যাপ থেকে বের হচ্ছি, তারপর ${match.third} খুলছি।")
            handler.postDelayed({
                if (stopped) return@postDelayed
                YesminAccessibilityService.performBackIfConnected()
                handler.postDelayed({
                    if (stopped) return@postDelayed
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    speak("${match.third} খুলেছি।")
                }, 650)
            }, 850)
            return
        }
        if (command.contains("স্ক্রিন") && (command.contains("পড়") || command.contains("কি আছে") || command.contains("দেখ"))) {
            speak(YesminAccessibilityService.executeApproved("read")); return
        }
        speak("তোমার কথা শুনেছি। এই কাজটি এখনো আমার Voice Action-এ যোগ করা হয়নি।")
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this) || bubble != null) return
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12); setBackgroundColor(0xEE17152B.toInt()) }
        val title = TextView(this).apply { text = "YESMIN AI • LIVE"; setTextColor(-1); textSize = 13f }
        statusView = TextView(this).apply { text = "Voice starting…"; setTextColor(0xFFB8A5FF.toInt()); textSize = 12f }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val listen = Button(this).apply { text = "VOICE"; setOnClickListener { if (!listening) startListening() else { recognizer?.cancel(); listening = false; statusView?.text = "Voice paused" } } }
        val stop = Button(this).apply { text = "STOP"; setOnClickListener { stopAssistant() } }
        controls.addView(listen, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(stop, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(title); root.addView(statusView); root.addView(controls)
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 12; y = 180 }
        root.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; return true }
                    MotionEvent.ACTION_MOVE -> { params.x = startX - (event.rawX - downX).toInt(); params.y = startY + (event.rawY - downY).toInt(); windowManager?.updateViewLayout(root, params); return true }
                }
                return false
            }
        })
        try { windowManager?.addView(root, params); bubble = root } catch (_: Exception) {}
    }

    private fun stopAssistant() {
        if (stopped) return
        stopped = true; listening = false; handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null; tts?.stop()
        try { bubble?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        bubble = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() {
        stopped = true; handler.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (_: Exception) {}
        tts?.stop(); tts?.shutdown()
        try { bubble?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        bubble = null; super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.tamimgamer.yesminai.START_VOICE"
        const val ACTION_STOP = "com.tamimgamer.yesminai.STOP_VOICE"
        private const val CHANNEL_ID = "yesmin_voice"
        private const val NOTIFICATION_ID = 7124
    }
}
package com.tan.smartcane

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val sentAction = "com.tan.smartcane.SMS_SENT"
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var contact: EditText
    private lateinit var spinner: Spinner
    private var devices = listOf<BluetoothDevice>()
    private var socket: BluetoothSocket? = null
    private var coords: String? = null
    private var retries = 0
    private var isTest = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mp: MediaPlayer? = null

    // [grupo][20cm, 50cm, 80cm] - kapareho ng sa Arduino
    private val where = arrayOf("kaliwa", "gitna", "kanan", "kaliwa at gitna", "kanan at gitna", "kanan at kaliwa", "lahat ng direksyon")
    private val table = arrayOf(
        intArrayOf(15, 8, 1), intArrayOf(16, 9, 2), intArrayOf(17, 10, 3), intArrayOf(18, 11, 4),
        intArrayOf(19, 12, 5), intArrayOf(20, 13, 6), intArrayOf(21, 14, 7)
    )
    private val dist = intArrayOf(20, 50, 80)

    private val smsResult = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (resultCode == RESULT_OK) {
                retries = 0; send("SMS_OK"); note("SMS naipadala")
            } else {
                send("SMS_FAIL"); note("SMS pumalya")
                if (!isTest && retries++ < 3) ui.postDelayed({ sendSms() }, 1500)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // pansamantala: huwag mag-lock habang nagte-test
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 20f; setOnClickListener { f() } }
        status = TextView(this).apply { text = "Hindi nakakonekta"; textSize = 22f }
        contact = EditText(this).apply {
            hint = "Numero ng emergency contact"; textSize = 20f
            inputType = InputType.TYPE_CLASS_PHONE; setText(prefs.getString("num", ""))
        }
        spinner = Spinner(this)
        log = TextView(this).apply { textSize = 14f }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40)
            addView(status); addView(spinner)
            addView(btn("Kumonekta sa Smart Cane") { connect() })
            addView(contact)
            addView(btn("I-save ang contact") {
                prefs.edit().putString("num", contact.text.toString().trim()).apply(); note("Na-save ang contact")
            })
            
            addView(log)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        val f = IntentFilter(sentAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(smsResult, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(smsResult, f)

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("fil", "PH"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { send("AUDIO_DONE") }
                    override fun onError(id: String?) { send("AUDIO_DONE") }
                })
                ttsReady = true
            }
        }

        val p = mutableListOf(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        requestPermissions(p.toTypedArray(), 1)
        loadDevices()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) = loadDevices()

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        try {
            val ad = getSystemService(BluetoothManager::class.java).adapter
            devices = ad?.bondedDevices?.toList() ?: emptyList()
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                devices.map { it.name ?: it.address })
        } catch (_: SecurityException) {}
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val d = devices.getOrNull(spinner.selectedItemPosition) ?: return note("Walang napiling device (i-pair muna sa Settings)")
        thread {
            try {
                socket?.close()
                val s = d.createRfcommSocketToServiceRecord(spp)
                s.connect(); socket = s
                say("Nakakonekta: ${d.name}")
                send("APP_CONNECTED")
                val r = s.inputStream.bufferedReader()
                while (true) handle((r.readLine() ?: break).trim())
            } catch (e: Exception) { note("Error: ${e.message}") }
            say("Nadiskonekta")
        }
    }

    // Arduino -> app: "SOS", "SOS:lat,lng", "AUDIO:0008"
    private fun handle(l: String) {
        note("Device: $l")
        if (l.startsWith("AUDIO:")) {
            val n = l.substring(6).toIntOrNull()
            if (n != null) ui.post { speak(n) } else send("AUDIO_DONE")
        } else if (l.startsWith("SOS")) {
            coords = l.substringAfter(':', "").ifBlank { null }
            isTest = false; retries = 0
            ui.post { sendSms() }
        }
    }

    // Kung may res/raw/a0008.mp3, iyon ang tutugtog. Kung wala, boses (TTS).
    private fun speak(n: Int) {
        val res = resources.getIdentifier("a%04d".format(n), "raw", packageName)
        if (res != 0) {
            mp?.release()
            mp = MediaPlayer.create(this, res)
            if (mp == null) { send("AUDIO_DONE"); return }
            mp?.setOnCompletionListener { send("AUDIO_DONE") }
            mp?.start()
        } else if (ttsReady) {
            tts?.speak(phrase(n), TextToSpeech.QUEUE_FLUSH, null, "a$n")
        } else {
            send("AUDIO_DONE")
        }
    }

    private fun phrase(n: Int): String {
        for (g in table.indices) for (z in 0..2) {
            if (table[g][z] == n) return "Sagabal sa ${where[g]}, ${dist[z]} sentimetro"
        }
        return when (n) {
            24 -> "Paakyat na daan"
            25 -> "Pababang daan"
            28 -> "Patag na daan"
            29 -> "Hagdan pababa"
            30 -> "Hagdan pataas"
            31 -> "May butas"
            40 -> "Naipadala ang S O S"
            41 -> "Hindi naipadala ang S O S"
            42 -> "Nakakonekta ang Smart Cane"
            else -> "Alerto $n"
        }
    }

    private fun sendSms() {
        val num = prefs.getString("num", "").orEmpty()
        if (num.isBlank() || checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            send("SMS_FAIL"); note("Walang contact o SMS permission"); return
        }
        val loc = coords?.let { " https://maps.google.com/?q=$it" }.orEmpty()
        val msg = (if (isTest) "[TEST] " else "") + "SOS! Kailangan ng tulong." + loc
        val pi = PendingIntent.getBroadcast(this, 0, Intent(sentAction).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            getSystemService(SmsManager::class.java).sendTextMessage(num, null, msg, pi, null)
        } catch (e: Exception) { send("SMS_FAIL"); note("Error: ${e.message}") }
    }

    private fun send(s: String) = thread {
        try { socket?.outputStream?.apply { write((s + "\n").toByteArray()); flush() } } catch (_: Exception) {}
    }

    private fun say(s: String) { ui.post { status.text = s } }
    private fun note(s: String) { ui.post { log.text = "$s\n${log.text}" } }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(smsResult)
        tts?.shutdown(); mp?.release()
        try { socket?.close() } catch (_: Exception) {}
    }
}
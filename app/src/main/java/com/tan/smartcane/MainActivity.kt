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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var target: BluetoothDevice? = null   // device na kokonektahan
    @Volatile private var running = false                   // tumatakbo ang connect/reconnect loop
    private var coords: String? = null
    private var retries = 0
    private var isTest = false
    private var locMsg: String? = null   // location SMS na ipapadala pagkatapos ma-send ang SOS

    // ---------- LOCATION NG PHONE ----------
    private var locStarted = false
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ---------- AUDIO ----------
    // false = DFPlayer sa Arduino ang tutugtog ng audio (phone hindi mag-p-play).
    // true  = phone ang tutugtog (TTS o res/raw).
    private val phoneAudio = true
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null

    // Pareho ng OBSTACLE_AUDIO table sa Arduino: [grupo][20cm, 50cm, 80cm]
    private val obstacleAudio = arrayOf(
        intArrayOf(15, 8, 1), intArrayOf(16, 9, 2), intArrayOf(17, 10, 3), intArrayOf(18, 11, 4),
        intArrayOf(19, 12, 5), intArrayOf(20, 13, 6), intArrayOf(21, 14, 7)
    )
    private val groupText = listOf("left", "front", "right", "left and front", "right and front", "left and right", "all sides")
    private val zoneText = listOf("very close", "close", "ahead")

    // Number ng audio -> sasabihin ng phone kung walang res/raw/aXXXX.mp3. Pwede mong palitan ang mga salita dito.
    private val phrases: Map<Int, String> = buildMap {
        for (g in 0..6) for (z in 0..2) put(obstacleAudio[g][z], "Obstacle on the ${groupText[g]}, ${zoneText[z]}")
        put(24, "Going uphill")
        put(25, "Going downhill")
        put(28, "Flat ground")
        put(29, "Stairs going down")
        put(30, "Stairs going up")
        put(31, "Hole ahead")
        put(40, "Emergency message sent")
        put(41, "Message failed. Trying again")
        put(42, "Smart Cane connected")
        put(43, "Low battery")
    }

    // SmsManager: getSystemService(SmsManager) ay gumagana lang sa Android 12 pataas
    private fun sms(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    // Flowchart: Send successfully? Yes -> audio 0040 (SMS_OK). No -> audio 0041 (SMS_FAIL), delay 1-2 sec, retry.
    private val smsResult = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (resultCode == RESULT_OK) {
                retries = 0; send("SMS_OK"); note("SMS sent succesfully"); sendLocation()
            } else {
                send("SMS_FAIL"); note("SMS not succesfully")
                if (!isTest && retries++ < 10) ui.postDelayed({ sendSms() }, 1500)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // para hindi mag-sleep ang app habang gamit
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 20f; setOnClickListener { f() } }
        status = TextView(this).apply { text = "Hindi nakakonekta"; textSize = 22f }
        contact = EditText(this).apply {
            hint = "Number of emergency contact"; textSize = 20f
            inputType = InputType.TYPE_CLASS_PHONE; setText(prefs.getString("num", ""))
        }
        spinner = Spinner(this)
        log = TextView(this).apply { textSize = 14f }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40)
            addView(status); addView(spinner)
            addView(btn("Connected Smart Cane") { connectSelected() })
            addView(contact)
            addView(btn("Save Contact") {
                prefs.edit().putString("num", contact.text.toString().trim()).apply(); note("Saved Contact")
            })
            //addView(btn("Test SMS") { isTest = true; coords = null; sendSms() })
           // addView(btn("Test alert sa device") { send("TEST") })
            addView(log)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        val f = IntentFilter(sentAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(smsResult, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(smsResult, f)

        val p = mutableListOf(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        p += Manifest.permission.ACCESS_FINE_LOCATION
        p += Manifest.permission.ACCESS_COARSE_LOCATION
        requestPermissions(p.toTypedArray(), 1)
        loadDevices()
        startLocation()

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { audioDone() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { audioDone() }
                })
                ttsReady = true
            } else note("TTS hindi gumana (i-install ang Text-to-speech engine)")
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        loadDevices()
        startLocation()
        autoConnect()
    }

    // Simulan ang location updates ng phone (GPS + network). Ligtas tawagin nang paulit-ulit.
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locStarted) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.allProviders.contains(prov)) {
                    lm.requestLocationUpdates(prov, 5000L, 0f, locListener, Looper.getMainLooper())
                    locStarted = true
                }
            } catch (_: Exception) {}   // walang permission pa, o walang ganitong provider
        }
    }

    // Pinakabagong kilalang location mula sa GPS / network / passive. null kung wala.
    @SuppressLint("MissingPermission")
    private fun bestLocation(): Location? {
        val lm = getSystemService(LocationManager::class.java) ?: return null
        var best: Location? = null
        for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(prov) ?: continue
                val b = best
                if (b == null || l.time > b.time) best = l
            } catch (_: Exception) {}
        }
        return best
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        try {
            val ad = getSystemService(BluetoothManager::class.java).adapter
            devices = ad?.bondedDevices?.toList() ?: emptyList()
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                devices.map { it.name ?: it.address })
        } catch (_: SecurityException) {}
    }

    // ---------- BLUETOOTH: connect + auto-reconnect ----------
    private fun connectSelected() {
        val d = devices.getOrNull(spinner.selectedItemPosition)
            ?: return note("No device (pair in Settings)")
        connectTo(d)
    }

    // Kokonekta sa huling ginamit na device pagbukas ng app
    private fun autoConnect() {
        val a = prefs.getString("dev", null) ?: return
        val i = devices.indexOfFirst { it.address == a }
        if (i < 0) return
        spinner.setSelection(i)
        connectTo(devices[i])
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(d: BluetoothDevice) {
        prefs.edit().putString("dev", d.address).apply()
        val changed = target?.address != d.address
        target = d
        if (running) {                       // may tumatakbo na: huwag gumawa ng pangalawang thread
            if (changed) { try { socket?.close() } catch (_: Exception) {} }
            return
        }
        running = true
        thread {
            while (running) {
                val dev = target ?: break
                try {
                    val s = dev.createRfcommSocketToServiceRecord(spp)
                    socket = s
                    s.connect()
                    say("Connected: ${dev.name}")
                    send("APP_CONNECTED")
                    val r = s.inputStream.bufferedReader()
                    while (true) handle((r.readLine() ?: break).trim())
                } catch (e: Exception) { note("Error: ${e.message}") }
                try { socket?.close() } catch (_: Exception) {}
                if (running) { say("Disconnected, Try again..."); Thread.sleep(3000) }
            }
            running = false
        }
    }

    // ESP32/Arduino -> app: "AUDIO:0016", "SOS" or "SOS:lat,lng", "HELLO"
    private fun handle(l: String) {
        note("ESP32: $l")
        if (l.startsWith("AUDIO:")) {
            val n = l.substringAfter(':').trim().toIntOrNull()
            if (n != null && phoneAudio) ui.post { playAudio(n) }
        } else if (l.startsWith("SOS")) {
            coords = l.substringAfter(':', "").ifBlank { null }
            isTest = false; retries = 0
            ui.post { sendSms() }
        } else if (l == "HELLO") {
            send("APP_CONNECTED")            // nag-reset ang Arduino: i-initialize ulit
        }
    }

    private fun sendSms() {
        val num = prefs.getString("num", "").orEmpty()
        if (num.isBlank() || checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            send("SMS_FAIL"); note("No contact or SMS permission"); return
        }
        // Location: kung may galing sa ESP32 (SOS:lat,lng) gamitin iyon, kung wala, gamitin ang location ng phone.
        val phone = if (coords == null) bestLocation() else null
        val c = coords ?: phone?.let { "%.6f,%.6f".format(Locale.US, it.latitude, it.longitude) }
        val ageMin = phone?.let { (System.currentTimeMillis() - it.time) / 60000 } ?: 0L
        val tag = if (isTest) "" else ""
        // Hiwalay na SMS ang location, plain coordinates (walang link) para hindi ma-block ng telco.
        locMsg = c?.let { tag + "Location: $it" + (if (ageMin >= 2) " (last known, $ageMin min ago)" else "") }
        if (c == null) note("No location (Please on Location and give the permission)") else note("Location: $c")
        val msg = tag + "SOS! I need help."
        val pi = PendingIntent.getBroadcast(this, 0, Intent(sentAction).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            sms().sendTextMessage(num, null, msg, pi, null)
        } catch (e: Exception) { send("SMS_FAIL"); note("Error: ${e.message}") }
    }

    // Ipadala ang location bilang hiwalay na SMS, pagkatapos lang ma-send ang SOS. Hindi nito naaapektuhan ang SOS.
    private fun sendLocation() {
        val m = locMsg ?: return
        locMsg = null
        val num = prefs.getString("num", "").orEmpty()
        if (num.isBlank()) return
        try {
            sms().sendTextMessage(num, null, m, null, null)
            note("Location SMS sent succesfully")
        } catch (e: Exception) { note("Location SMS error: ${e.message}") }
    }

    // Arduino -> "AUDIO:0016". Una subukan ang res/raw/a0016.mp3, kung wala, gagamitin ang TTS.
    private fun playAudio(n: Int) {
        val resId = resources.getIdentifier("a%04d".format(n), "raw", packageName)
        if (resId != 0) {
            val mp = MediaPlayer.create(this, resId)
            if (mp != null) {
                player?.release()
                player = mp
                mp.setOnCompletionListener { audioDone() }
                mp.setOnErrorListener { _, _, _ -> audioDone(); true }
                mp.start()
                return
            }
        }
        val text = phrases[n]
        if (text != null && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "a$n")
        } else {
            note("No audio/TTS para sa $n")
            audioDone()
        }
    }

    // Sabihin sa Arduino na tapos na ang audio para makapag-play ito ng susunod.
    private fun audioDone() { send("AUDIO_DONE") }

    private fun send(s: String) = thread {
        try { socket?.outputStream?.apply { write((s + "\n").toByteArray()); flush() } } catch (_: Exception) {}
    }

    private fun say(s: String) { ui.post { status.text = s } }
    private fun note(s: String) { ui.post { log.text = ("$s\n${log.text}").take(3000) } }   // limitado para hindi lumaki nang lumaki

    override fun onDestroy() {
        super.onDestroy()
        running = false
        unregisterReceiver(smsResult)
        tts?.shutdown(); player?.release()
        try { getSystemService(LocationManager::class.java)?.removeUpdates(locListener) } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}

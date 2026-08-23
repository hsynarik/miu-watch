package com.miu.watch

import android.annotation.SuppressLint
import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.view.View
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.google.android.gms.wearable.Wearable
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity(), SensorEventListener {

    // ── Sensor ────────────────────────────────────────────────────────────────
    // ── Sensor ────────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var sensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    @Volatile private var beta  = 0f   // forward/backward tilt
    @Volatile private var gamma = 0f   // left/right tilt

    // ── State ─────────────────────────────────────────────────────────────────
    private var lastDir = ""           // last direction sent to robot
    private var robotBaseUrl = "http://192.168.4.1"
    private var isConnected = false
    private var lastDirSentTime = 0L
    private var isGyroEnabled = false
    private var isPushupMode = false
    private var isAvatarMode = false
    private var isShowingTempText = false
    
    private var connectedBtNodeId: String? = null
    private var isBtBridgeActive = false

    private lateinit var prefs: SharedPreferences

    // ── Threading ─────────────────────────────────────────────────────────────
    private val mainHandler  = Handler(Looper.getMainLooper())
    private val netExecutor  = Executors.newSingleThreadExecutor()

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var arrowUp:     TextView
    private lateinit var arrowDown:   TextView
    private lateinit var arrowLeft:   TextView
    private lateinit var arrowRight:  TextView
    private lateinit var tvSensor:    TextView
    private lateinit var btnGyroToggle: TextView
    private lateinit var btnAvatarToggle: TextView
    private lateinit var btnPushupToggle: TextView
    private lateinit var tvBtStatusWatch: TextView
    
    private lateinit var layoutConnect: LinearLayout
    private lateinit var layoutTilt:    FrameLayout
    private lateinit var etIpAddress:   EditText
    private lateinit var btnConnect:    Button
    private lateinit var tvConnectError:TextView

    // ── Constants ─────────────────────────────────────────────────────────────
    companion object {
        const val PREFS_NAME   = "MiuPrefs"
        const val PREF_IP      = "RobotIP"
        const val TILT_THRESH  = 14f          // degrees before registering a direction
        const val LOOP_MS      = 100L         // tilt-check interval
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        layoutConnect = findViewById(R.id.layoutConnect)
        layoutTilt    = findViewById(R.id.layoutTilt)
        etIpAddress   = findViewById(R.id.etIpAddress)
        btnConnect    = findViewById(R.id.btnConnect)
        tvConnectError= findViewById(R.id.tvConnectError)

        arrowUp    = findViewById(R.id.arrowUp)
        arrowDown  = findViewById(R.id.arrowDown)
        arrowLeft  = findViewById(R.id.arrowLeft)
        arrowRight = findViewById(R.id.arrowRight)
        tvSensor   = findViewById(R.id.tvSensor)
        btnGyroToggle = findViewById(R.id.btnGyroToggle)
        btnAvatarToggle = findViewById(R.id.btnAvatarToggle)
        btnPushupToggle = findViewById(R.id.btnPushupToggle)
        tvBtStatusWatch = findViewById(R.id.tvBtStatusWatch)

        val savedIp = prefs.getString(PREF_IP, "192.168.4.1")
        etIpAddress.setText(savedIp)

        btnConnect.setOnClickListener {
            connectToRobot()
        }

        layoutTilt.setOnClickListener {
            if (isPushupMode) return@setOnClickListener
            sendCmd("pose=rest")
            showTempText("Poz: Rest (Dinlenme) 💤")
        }

        btnGyroToggle.setOnClickListener {
            isGyroEnabled = !isGyroEnabled
            btnGyroToggle.setBackgroundResource(if (isGyroEnabled) R.drawable.arrow_active else R.drawable.arrow_inactive)
        }

        btnAvatarToggle.setOnClickListener {
            isAvatarMode = !isAvatarMode
            btnAvatarToggle.setBackgroundResource(if (isAvatarMode) R.drawable.arrow_active else R.drawable.arrow_inactive)
            if (isAvatarMode) {
                showTempText("Avatar Modu: Aktif 👽")
                // Reset active states for arrows
                setActive(arrowLeft, false)
                setActive(arrowRight, false)
                setActive(arrowUp, false)
                setActive(arrowDown, false)
            } else {
                showTempText("Avatar Modu: Kapalı")
            }
        }

        btnPushupToggle.setOnClickListener {
            isPushupMode = !isPushupMode
            btnPushupToggle.setBackgroundResource(if (isPushupMode) R.drawable.arrow_active else R.drawable.arrow_inactive)
            if (isPushupMode) {
                mainHandler.post(pushupLoop)
                showTempText("Şınav Modu: Aktif 💪")
            } else {
                mainHandler.removeCallbacks(pushupLoop)
                sendCmd("action=stop")
                showTempText("Şınav Modu: Kapalı")
            }
        }

        setupSensor()
        startBtPoller()
    }

    private val btPollRunnable = object : Runnable {
        override fun run() {
            val nodeClient = Wearable.getNodeClient(this@MainActivity)
            nodeClient.connectedNodes.addOnSuccessListener { nodes ->
                if (nodes.isNotEmpty()) {
                    connectedBtNodeId = nodes.first().id
                    isBtBridgeActive = true
                    tvBtStatusWatch.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10b981"))
                    if (!isConnected) {
                        isConnected = true
                        layoutConnect.visibility = View.GONE
                        layoutTilt.visibility = View.VISIBLE
                        sensor?.let { sensorManager.registerListener(this@MainActivity, it, SensorManager.SENSOR_DELAY_GAME) }
                        gyroSensor?.let { sensorManager.registerListener(this@MainActivity, it, SensorManager.SENSOR_DELAY_GAME) }
                        mainHandler.post(tiltLoop)
                    }
                } else {
                    connectedBtNodeId = null
                    isBtBridgeActive = false
                    tvBtStatusWatch.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#f87171"))
                    if (isConnected && robotBaseUrl == "http://192.168.4.1" && prefs.getString(PREF_IP, "") == "") {
                        isConnected = false
                        layoutTilt.visibility = View.GONE
                        layoutConnect.visibility = View.VISIBLE
                    }
                }
            }
            mainHandler.postDelayed(this, 10000)
        }
    }

    private fun startBtPoller() {
        mainHandler.post(btPollRunnable)
    }

    override fun onResume() {
        super.onResume()
        startBtPoller()
        if (isConnected) {
            sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            mainHandler.post(tiltLoop)
        }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(btPollRunnable)
        if (isConnected) {
            mainHandler.removeCallbacks(tiltLoop)
            mainHandler.removeCallbacks(pushupLoop)
            sensorManager.unregisterListener(this)
            // Safety: stop the robot when app is backgrounded
            sendCmd("stop=1")
            lastDir = ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        netExecutor.shutdownNow()
    }

    private fun showTempText(msg: String) {
        tvSensor.text = msg
        isShowingTempText = true
        mainHandler.removeCallbacks(clearTempTextRunnable)
        mainHandler.postDelayed(clearTempTextRunnable, 3000)
    }

    private val clearTempTextRunnable = Runnable {
        isShowingTempText = false
    }

    private val pushupLoop = object : Runnable {
        override fun run() {
            sendCmd("pose=pushup")
            if (isPushupMode) {
                mainHandler.postDelayed(this, 2500)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Sensor
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupSensor() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_SCROLL &&
            event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL)
            val targetDir = if (delta > 0) "left" else "right"
            
            if (lastDir != targetDir) {
                lastDir = targetDir
                sendCmd("go=$targetDir")
            }
            
            setActive(arrowLeft, targetDir == "left")
            setActive(arrowRight, targetDir == "right")
            setActive(arrowUp, false)
            setActive(arrowDown, false)
            
            // Stop after 300ms of no scrolling
            mainHandler.removeCallbacks(stopRotaryRunnable)
            mainHandler.postDelayed(stopRotaryRunnable, 300)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private val stopRotaryRunnable = Runnable {
        if (lastDir == "left" || lastDir == "right") {
            lastDir = ""
            sendCmd("stop=1")
        }
        setActive(arrowLeft, false)
        setActive(arrowRight, false)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val R = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(R, event.values)
                val O = FloatArray(3)
                SensorManager.getOrientation(R, O)
                beta  = Math.toDegrees(O[1].toDouble()).toFloat()
                gamma = Math.toDegrees(O[2].toDouble()).toFloat()
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!isGyroEnabled) return
                // X-axis gyro represents wrist pitch (flexion/extension), like a motorcycle throttle!
                val twist = event.values[0]
                if (Math.abs(twist) > 16.0f && !isPushupMode) {
                    val now = System.currentTimeMillis()
                    if (now - lastDirSentTime > 3000) { // 3 saniye cooldown
                        lastDirSentTime = now
                        sendCmd("pose=dance")
                        showTempText("Poz: Dans! 🕺")
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ═════════════════════════════════════════════════════════════════════════
    // Tilt Loop — runs on main thread every LOOP_MS
    // ═════════════════════════════════════════════════════════════════════════

    private val tiltLoop = object : Runnable {
        override fun run() {
            processTilt()
            mainHandler.postDelayed(this, LOOP_MS)
        }
    }

    private fun processTilt() {
        if (!isGyroEnabled || isPushupMode) {
            if (!isShowingTempText) {
                tvSensor.text = if (isPushupMode) "Şınav Modu 💪" else "Jiroskop Kapalı 🚫"
            }
            return
        }

        val b = beta
        val g = gamma

        if (isAvatarMode) {
            if (!isShowingTempText) {
                tvSensor.text = "β ${b.toInt()}°  γ ${g.toInt()}°"
            }
            
            // Limit to +/- 45
            val pitch = Math.max(-45.0, Math.min(45.0, b.toDouble())).toInt()
            val roll = Math.max(-45.0, Math.min(45.0, g.toDouble())).toInt()
            
            val cmdUrl = "avatarPitch=$pitch&avatarRoll=$roll"
            if (cmdUrl != lastDir) {
                lastDir = cmdUrl
                sendCmd(cmdUrl)
            }
            return
        }

        val dir = when {
            Math.abs(g) > Math.abs(b) -> when {
                g < -TILT_THRESH -> "left"
                g >  TILT_THRESH -> "right"
                else -> ""
            }
            else -> when {
                b >  TILT_THRESH -> "forward"
                b < -TILT_THRESH -> "backward"
                else -> ""
            }
        }

        // Update arrow UI
        setActive(arrowUp,    dir == "forward")
        setActive(arrowDown,  dir == "backward")
        setActive(arrowLeft,  dir == "left")
        setActive(arrowRight, dir == "right")

        if (!isShowingTempText) {
            tvSensor.text = "β ${b.toInt()}°  γ ${g.toInt()}°"
        }

        // Send command only when direction changes
        if (dir != lastDir) {
            lastDir = dir
            sendCmd(if (dir.isEmpty()) "stop=1" else "go=$dir")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Arrow view state
    // ═════════════════════════════════════════════════════════════════════════

    private fun setActive(tv: TextView, active: Boolean) {
        tv.setBackgroundResource(
            if (active) R.drawable.arrow_active else R.drawable.arrow_inactive
        )
        tv.setTextColor(
            if (active) 0xFFE9D5FF.toInt() else 0xFF4A3A6A.toInt()
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Connection & HTTP
    // ═════════════════════════════════════════════════════════════════════════

    private fun connectToRobot() {
        val ip = etIpAddress.text.toString().trim()
        if (ip.isEmpty()) return

        robotBaseUrl = if (ip.startsWith("http")) ip else "http://$ip"
        tvConnectError.text = "Bağlanıyor..."
        btnConnect.isEnabled = false

        netExecutor.submit {
            try {
                // Sadece bağlantıyı test et
                val conn = URL("$robotBaseUrl/").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout    = 2000
                conn.requestMethod  = "GET"
                val code = conn.responseCode
                conn.disconnect()

                if (code == 200) {
                    // Bağlantı başarılı, IP'yi kaydet
                    prefs.edit().putString(PREF_IP, ip).apply()
                    mainHandler.post {
                        isConnected = true
                        layoutConnect.visibility = View.GONE
                        layoutTilt.visibility = View.VISIBLE
                        tvConnectError.text = ""
                        btnConnect.isEnabled = true
                        // Sensörü başlat
                        sensor?.let { sensorManager.registerListener(this@MainActivity, it, SensorManager.SENSOR_DELAY_GAME) }
                        mainHandler.post(tiltLoop)
                    }
                } else {
                    mainHandler.post {
                        tvConnectError.text = "Hata: HTTP $code"
                        btnConnect.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    tvConnectError.text = "Bağlantı başarısız.\nAynı ağda mısınız?"
                    btnConnect.isEnabled = true
                }
            }
        }
    }

    private fun sendCmd(params: String) {
        if (isBtBridgeActive && connectedBtNodeId != null) {
            Wearable.getMessageClient(this).sendMessage(connectedBtNodeId!!, "/miu_cmd", params.toByteArray())
                .addOnFailureListener { e ->
                    mainHandler.post { showTempText("BT Hatası!") }
                }
            
            // Flash the UI dot yellow
            mainHandler.post {
                tvBtStatusWatch.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#eab308"))
                mainHandler.postDelayed({
                    tvBtStatusWatch.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10b981"))
                }, 150)
            }
        } else {
            // Fallback to Wi-Fi HTTP
            netExecutor.submit {
                try {
                    val conn = URL("$robotBaseUrl/cmd?$params").openConnection() as HttpURLConnection
                    conn.connectTimeout = 400
                    conn.readTimeout    = 400
                    conn.requestMethod  = "GET"
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Exception) {}
            }
        }
    }
}

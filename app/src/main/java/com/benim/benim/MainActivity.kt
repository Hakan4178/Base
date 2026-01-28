package com.benim.benim

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.*
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.benim.benim.net.UdpService
import com.benim.benim.ui.DraggableButton
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.FrameLayout


// ═══════════════════════════════════════════════════════════════
// DATA MODELS
// ═══════════════════════════════════════════════════════════════
data class ButtonSpec(
    val id: String,
    var xPercent: Float,
    var yPercent: Float,
    var sizeDp: Int = 64,
    var label: String = "B",
    var action: String = "BUTTON",
    var haptic: Boolean = true
)

data class LayoutSpec(
    val name: String,
    val buttons: MutableList<ButtonSpec> = mutableListOf()
)

// ═══════════════════════════════════════════════════════════════
// MAIN ACTIVITY
// ═══════════════════════════════════════════════════════════════
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        private const val MOUSE_THROTTLE_MS = 7L  // ~144 FPS
    }

    // --- Değişkenler ---
    private val prefs by lazy {
        applicationContext.getSharedPreferences("settings", MODE_PRIVATE)
    }
    private val PREFS_LAYOUTS = "gamepad_layouts"
    private val KEY_LAYOUTS = "layouts_json"

    // Mod Yönetimi
    private var currentMode: String = "auto"
    private var effectiveMode: String = "classic"

    // UI Elementleri
    private var txtStatus: TextView? = null

    // Classic Mode UI
    private var rightJoystickArea: FrameLayout? = null
    private var rightJoystickThumb: View? = null
    private var joystickArea: FrameLayout? = null      // Sol Joystick Area
    private var joystickThumb: View? = null            // Sol Joystick Thumb

    // Split Mode UI
    private var touchpadArea: FrameLayout? = null
    private var btnMouseLeft: Button? = null
    private var btnMouseRight: Button? = null
    private var btnMouseMiddle: Button? = null

    // ═══════════════════════════════════════════════════════════════
    // GAMEPAD STATE (Yeni 32-bit format)
    // ═══════════════════════════════════════════════════════════════
    private var buttons: Int = 0                    // 32-bit button mask
    private var leftStickX: Int = 0                 // -127 ~ 127
    private var leftStickY: Int = 0
    private var rightStickX: Int = 0
    private var rightStickY: Int = 0
    private var triggerL2: Int = 0                  // 0 ~ 255
    private var triggerR2: Int = 0

    // Touchpad Durumu
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchpadActive = false
    private var lastScrollY = 0f

    // Ayarlar
    private var editMode = false
    private var centerOnTouchEnabled = true
    private var globalHaptic = true
    private var gyroEnabled = false
    private var isServiceBound = false

    // Layout Verileri
    private var layoutsMap: MutableMap<String, LayoutSpec> = mutableMapOf()
    private var currentLayoutKey = "default"
    private val specViewMap = mutableMapOf<String, DraggableButton>()

    // Servis ve Zamanlayıcılar
    private var udpService: UdpService? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var statusTimer: Timer? = null

    private var lastMouseSendTime = 0L





    // ═══════════════════════════════════════════════════════════════
    // SERVICE CONNECTION
    // ═══════════════════════════════════════════════════════════════
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            udpService = (binder as UdpService.LocalBinder).getService()
            Log.d(TAG, "✅ Service bağlandı")

            // ═══════════════════════════════════════════════════════
            // GYRO OTOMATİK BAŞLAT
            // ═══════════════════════════════════════════════════════
            udpService?.let { service ->
                if (service.hasGyro()) {
                    service.enableGyro()
                    gyroEnabled = true
                    Log.d(TAG, "🌀 Gyro otomatik etkinleştirildi")
                } else {
                    Log.w(TAG, "⚠️ Gyro sensör yok")
                }
            }

            updateStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            udpService = null
            updateStatus()
        }
    }

    // --- Broadcast Receiver ---
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UdpService.ACTION_STATUS) {
                runOnUiThread { updateStatus() }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        try { setTheme(R.style.Theme_Benim_NoActionBar) } catch (_: Exception) {}

        super.onCreate(savedInstanceState)

        currentMode = prefs.getString("ui_mode", "auto") ?: "auto"
        effectiveMode = resolveEffectiveMode()

        val layoutId = if (effectiveMode == "classic") R.layout.activity_main_classic else R.layout.activity_main_split
        setContentView(layoutId)

        // Immersive Mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        bindViews()
        setupListeners()
        registerStatusReceiver()
        startUdpService()

        if (effectiveMode == "classic") setupClassicMode()
        else setupSplitMode()

        startStatusTimer()
    }

    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)  // ✅ serviceConnection kullan
            isServiceBound = false
        }
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        // Temizlik
        specViewMap.clear()
        statusTimer?.cancel()
        statusTimer = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (currentMode == "auto") recreate()
    }

    // ═══════════════════════════════════════════════════════════════
    // INIT HELPERS
    // ═══════════════════════════════════════════════════════════════
    private fun resolveEffectiveMode(): String {
        return when (currentMode) {
            "classic" -> "classic"
            "split" -> "split"
            "auto" -> if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "classic" else "split"
            else -> "classic"
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerStatusReceiver() {
        val filter = IntentFilter(UdpService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun startUdpService() {
        val intent = Intent(this, UdpService::class.java)
        startService(intent)

        // Service bağla
        val bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.e(TAG, "Service bağlanamadı!")
            Toast.makeText(this, "Service başlatılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startStatusTimer() {
        statusTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() { runOnUiThread { updateStatus() } }
            }, 0, 1000L)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // VIEW BINDING & LISTENERS
    // ═══════════════════════════════════════════════════════════════
    private fun bindViews() {
        txtStatus = findViewById(R.id.txtStatus)
        rightJoystickArea = findViewById(R.id.rightJoystickArea)
        rightJoystickThumb = findViewById(R.id.rightJoystickThumb)
        touchpadArea = findViewById(R.id.touchpadArea)
        btnMouseLeft = findViewById(R.id.btnMouseLeft)
        btnMouseRight = findViewById(R.id.btnMouseRight)
        btnMouseMiddle = findViewById(R.id.btnMouseMiddle)
        joystickArea = findViewById(R.id.JoystickArea)
        joystickThumb = findViewById(R.id.JoystickThumb)
    }

    private fun setupListeners() {
        txtStatus?.setOnLongClickListener { promptSetServerIp(); true }

        // Debug: Status'a tıklayınca debug bilgisi göster
        txtStatus?.setOnClickListener {
            val debug = udpService?.getDebugState() ?: "Service yok"
            Log.d(TAG, "DEBUG: $debug")
            Toast.makeText(this, debug, Toast.LENGTH_LONG).show()
        }

        findViewById<Button?>(R.id.btnDiscover)?.setOnClickListener {
            udpService?.discoverServer()
            Toast.makeText(this, "Sunucu aranıyor...", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button?>(R.id.btnChangeMode)?.setOnClickListener { showModeSelectionDialog() }

        findViewById<Switch?>(R.id.switchHaptic)?.apply {
            isChecked = globalHaptic
            setOnCheckedChangeListener { _, checked -> globalHaptic = checked }
        }

        // Gyro Switch (varsa layout'ta)
        findViewById<Switch?>(R.id.switchGyro)?.apply {
            isChecked = gyroEnabled
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    gyroEnabled = udpService?.enableGyro() ?: false
                } else {
                    udpService?.disableGyro()
                    gyroEnabled = false
                }
                Log.d(TAG, "Gyro: $gyroEnabled")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CLASSIC MODE (GAMEPAD)
    // ═══════════════════════════════════════════════════════════════
    private fun setupClassicMode() {
        layoutsMap = loadLayoutsMap()
        if (!layoutsMap.containsKey(currentLayoutKey)) {
            layoutsMap[currentLayoutKey] = LayoutSpec("Default")
        }

        // Joystick
        joystickArea?.setOnTouchListener { v, ev -> handleJoystickTouch(v, ev, true); true }
        joystickArea?.setOnLongClickListener { pressButton(UdpService.BTN_L3); true }

        rightJoystickArea?.setOnTouchListener { v, ev -> handleJoystickTouch(v, ev, false); true }
        rightJoystickArea?.setOnLongClickListener { pressButton(UdpService.BTN_R3); true }

        // Ana Butonlar
        setupGamepadButton(R.id.btnA, UdpService.BTN_A)
        setupGamepadButton(R.id.btnB, UdpService.BTN_B)
        setupGamepadButton(R.id.btnX, UdpService.BTN_X)
        setupGamepadButton(R.id.btnY, UdpService.BTN_Y)
        setupGamepadButton(R.id.btnL1, UdpService.BTN_L1)
        setupGamepadButton(R.id.btnR1, UdpService.BTN_R1)
        setupGamepadButton(R.id.btnStart, UdpService.BTN_START)
        setupGamepadButton(R.id.btnSelect, UdpService.BTN_SELECT)
        setupGamepadButton(R.id.btnHome, UdpService.BTN_HOME)

        // Tetikler (Analog olarak)
        setupTriggerButton(R.id.btnL2, true)
        setupTriggerButton(R.id.btnR2, false)

        // D-Pad (Bit olarak)
        setupDpadButton(R.id.btnDpadUp, UdpService.BTN_DPAD_UP)
        setupDpadButton(R.id.btnDpadDown, UdpService.BTN_DPAD_DOWN)
        setupDpadButton(R.id.btnDpadLeft, UdpService.BTN_DPAD_LEFT)
        setupDpadButton(R.id.btnDpadRight, UdpService.BTN_DPAD_RIGHT)

        // Edit Mode
        findViewById<Button?>(R.id.btnEditMode)?.setOnClickListener { toggleEditMode() }
        findViewById<Button?>(R.id.btnAdd)?.setOnClickListener { showAddButtonDialog() }
        findViewById<Button?>(R.id.btnSavePreset)?.setOnClickListener { showSavePresetDialog() }

        val spinner = findViewById<Spinner?>(R.id.spinnerPresets)
        if (spinner != null) updatePresetSpinner(spinner)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGamepadButton(viewId: Int, mask: Int) {
        findViewById<View?>(viewId)?.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    buttons = buttons or mask
                    v.isPressed = true
                    if (globalHaptic) doHaptic()
                    sendGamepad()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    buttons = buttons and mask.inv()
                    v.isPressed = false
                    sendGamepad()
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTriggerButton(viewId: Int, isL2: Boolean) {
        findViewById<View?>(viewId)?.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isL2) {
                        triggerL2 = 255
                        buttons = buttons or UdpService.BTN_L2
                    } else {
                        triggerR2 = 255
                        buttons = buttons or UdpService.BTN_R2
                    }
                    v.isPressed = true
                    if (globalHaptic) doHaptic()
                    sendGamepad()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isL2) {
                        triggerL2 = 0
                        buttons = buttons and UdpService.BTN_L2.inv()
                    } else {
                        triggerR2 = 0
                        buttons = buttons and UdpService.BTN_R2.inv()
                    }
                    v.isPressed = false
                    sendGamepad()
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDpadButton(viewId: Int, mask: Int) {
        findViewById<View?>(viewId)?.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    buttons = buttons or mask
                    v.isPressed = true
                    if (globalHaptic) doHaptic()
                    sendGamepad()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    buttons = buttons and mask.inv()
                    v.isPressed = false
                    sendGamepad()
                }
            }
            true
        }
    }

    private fun pressButton(mask: Int) {
        buttons = buttons or mask
        sendGamepad()
        if (globalHaptic) doHaptic()
        uiHandler.postDelayed({
            buttons = buttons and mask.inv()
            sendGamepad()
        }, 100L)
    }

    // ═══════════════════════════════════════════════════════════════
    // JOYSTICK
    // ═══════════════════════════════════════════════════════════════
    private fun handleJoystickTouch(view: View, event: MotionEvent, isLeft: Boolean) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val thumb = if (isLeft) joystickThumb else rightJoystickThumb


        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val cx = if (isLeft && centerOnTouchEnabled && event.action == MotionEvent.ACTION_DOWN)
                    event.x else w / 2f
                val cy = if (isLeft && centerOnTouchEnabled && event.action == MotionEvent.ACTION_DOWN)
                    event.y else h / 2f

                val dx = (event.x - cx) / (w / 2f)
                val dy = (event.y - cy) / (h / 2f)

                val clampedX = dx.coerceIn(-1f, 1f)
                val clampedY = dy.coerceIn(-1f, 1f)

                val valX = (clampedX * 127f).roundToInt().coerceIn(-127, 127)
                val valY = (clampedY * 127f).roundToInt().coerceIn(-127, 127)

                if (isLeft) {
                    leftStickX = valX
                    leftStickY = valY
                } else {
                    rightStickX = valX
                    rightStickY = valY
                }

                thumb?.let {
                    val maxOffset = (w / 2f) - (it.width / 2f)
                    it.translationX = clampedX * maxOffset
                    it.translationY = clampedY * maxOffset
                }
                sendGamepad()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isLeft) {
                    leftStickX = 0
                    leftStickY = 0
                } else {
                    rightStickX = 0
                    rightStickY = 0
                }
                thumb?.animate()?.translationX(0f)?.translationY(0f)?.setDuration(100)?.start()
                sendGamepad()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GAMEPAD PAKET GÖNDERİMİ (12 BYTE + XOR)
    // ═══════════════════════════════════════════════════════════════
    private fun sendGamepad() {
        val service = udpService ?: return

        // UdpService API'sini kullan (Daha temiz)
        service.updateRaw(
            btns = buttons,
            lx = leftStickX,
            ly = leftStickY,
            rx = rightStickX,
            ry = rightStickY,
            l2 = triggerL2,
            r2 = triggerR2
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // MOUSE
    // ═══════════════════════════════════════════════════════════════
    private fun sendMouseMove(dx: Int, dy: Int) {
        udpService?.sendMouseMove(dx, dy)
    }

    private fun sendMouseButton(btn: Int, pressed: Boolean) {
        udpService?.sendMouseButton(btn.toByte(), pressed)
    }

    private fun sendMouseWheel(delta: Int) {
        udpService?.sendMouseWheel(delta)
    }

    // ═══════════════════════════════════════════════════════════════
    // SPLIT MODE
    // ═══════════════════════════════════════════════════════════════
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSplitMode() {
        touchpadArea?.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = ev.x
                    lastTouchY = ev.y
                    touchpadActive = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchpadActive = false
                }
            }
            true
        }

        fun sendThrottledMouseMove(dx: Int, dy: Int) {
            val now = System.currentTimeMillis()
            if (now - lastMouseSendTime < MOUSE_THROTTLE_MS) {
                return
            }
            lastMouseSendTime = now
            udpService?.sendMouseMove(dx, dy)
        }

        val mouseBtns = listOf(btnMouseLeft to 0, btnMouseRight to 1, btnMouseMiddle to 2)
        mouseBtns.forEach { (view, id) ->
            view?.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sendMouseButton(id, true)
                        if (globalHaptic) doHaptic()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        sendMouseButton(id, false)
                    }
                }
                true
            }
        }

        findViewById<View?>(R.id.scrollIndicator)?.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> lastScrollY = ev.y
                MotionEvent.ACTION_MOVE -> {
                    val dy = (lastScrollY - ev.y) / 20f
                    if (abs(dy) > 1) {
                        sendMouseWheel(dy.toInt())
                        lastScrollY = ev.y
                    }
                }
            }
            true
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STATUS UPDATE
    // ═══════════════════════════════════════════════════════════════
    private fun updateStatus() {
        val svc = udpService
        runOnUiThread {
            try {
                val modeEmoji = if (effectiveMode == "classic") "🎮" else "🖱️"

                if (svc == null) {
                    txtStatus?.text = "$modeEmoji Servis başlatılıyor..."
                    return@runOnUiThread
                }

                val ip = svc.getServerIp()
                val alive = svc.isServerAlive
                val ping = svc.lastPingMs
                val gyroStatus = if (svc.hasGyro()) {
                    if (svc.gyroEnabled) "🌀${svc.gyroPacketsSent}" else "⏸️"
                } else "❌"

                val statusIcon = when {
                    ip == null -> "⚪"
                    alive -> "🟢"
                    else -> "🟡"
                }

                val pingText = when {
                    ping >= 0 -> "${ping}ms"
                    ip != null && !alive -> "⏳"
                    else -> "--"
                }

                txtStatus?.text = buildString {
                    append("$modeEmoji $statusIcon ${ip ?: "?"} | $pingText")
                    append(" | G:$gyroStatus")
                    if (editMode) append(" ✏️")
                }
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // EDIT MODE & PRESETS
    // ═══════════════════════════════════════════════════════════════
    private fun placeButtonsFromLayout(layout: LayoutSpec) {
        val area = joystickArea ?: return
        specViewMap.values.forEach { area.removeView(it) }
        specViewMap.clear()

        layout.buttons.forEach { spec ->
            val btn = createDraggableButton(spec)
            area.addView(btn)

            area.post {
                val areaWidth = area.width
                val areaHeight = area.height

                // Güvenlik kontrolü: area henüz ölçülmediyse atla
                if (areaWidth <= 0 || areaHeight <= 0) {
                    return@post
                }

                val size = dpToPx(spec.sizeDp)
                btn.layoutParams = FrameLayout.LayoutParams(size, size)

                // Maksimum değerlerin negatif olmamasını sağla
                val maxX = (areaWidth - size).coerceAtLeast(0)
                val maxY = (areaHeight - size).coerceAtLeast(0)

                btn.x = (spec.xPercent * areaWidth - size / 2f).coerceIn(0f, maxX.toFloat())
                btn.y = (spec.yPercent * areaHeight - size / 2f).coerceIn(0f, maxY.toFloat())
            }

            specViewMap[spec.id] = btn
        }
    }

    private fun createDraggableButton(spec: ButtonSpec): DraggableButton {
        return DraggableButton(this).apply {
            text = spec.label
            editMode = this@MainActivity.editMode
            clickAction = { if (editMode) showEditDialog(spec) else performCustomButtonPress(spec) }
            onLongSettings = { showEditDialog(spec) }
            onDragEnd = { _, _ ->
                joystickArea?.let { area ->
                    spec.xPercent = (x + width / 2f) / area.width
                    spec.yPercent = (y + height / 2f) / area.height
                    saveLayouts()
                }
            }
        }
    }

    private fun performCustomButtonPress(spec: ButtonSpec) {
        val mask = when (spec.label.uppercase()) {
            "A" -> UdpService.BTN_A
            "B" -> UdpService.BTN_B
            "X" -> UdpService.BTN_X
            "Y" -> UdpService.BTN_Y
            "START" -> UdpService.BTN_START
            "SELECT" -> UdpService.BTN_SELECT
            "L1" -> UdpService.BTN_L1
            "R1" -> UdpService.BTN_R1
            else -> UdpService.BTN_A
        }

        specViewMap[spec.id]?.isEnabled = false
        buttons = buttons or mask
        sendGamepad()
        if (globalHaptic && spec.haptic) doHaptic()

        uiHandler.postDelayed({
            buttons = buttons and mask.inv()
            sendGamepad()
            specViewMap[spec.id]?.isEnabled = true
        }, 100L)
    }

    private fun toggleEditMode() {
        editMode = !editMode
        specViewMap.values.forEach { it.editMode = editMode }
        findViewById<Button?>(R.id.btnEditMode)?.text = if (editMode) "🔒" else "✏️"
        updateStatus()
    }

    // ═══════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════
    private fun showAddButtonDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
        }
        val lbl = EditText(this).apply { hint = "Etiket" }
        val sz = EditText(this).apply { hint = "Boyut (64)"; setText("64") }
        layout.addView(lbl)
        layout.addView(sz)

        AlertDialog.Builder(this)
            .setTitle("Buton Ekle")
            .setView(layout)
            .setPositiveButton("Ekle") { _, _ ->
                val spec = ButtonSpec(
                    "btn_${System.currentTimeMillis()}",
                    0.5f, 0.5f,
                    sz.text.toString().toIntOrNull() ?: 64,
                    lbl.text.toString().ifBlank { "B" }
                )
                layoutsMap[currentLayoutKey]!!.buttons.add(spec)
                saveLayouts()
                placeButtonsFromLayout(layoutsMap[currentLayoutKey]!!)
            }
            .show()
    }

    private fun showEditDialog(spec: ButtonSpec) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
        }
        val lbl = EditText(this).apply { setText(spec.label) }
        val sz = EditText(this).apply { setText(spec.sizeDp.toString()) }
        layout.addView(lbl)
        layout.addView(sz)

        AlertDialog.Builder(this)
            .setTitle("Düzenle")
            .setView(layout)
            .setPositiveButton("Kaydet") { _, _ ->
                spec.label = lbl.text.toString()
                spec.sizeDp = sz.text.toString().toIntOrNull() ?: 64
                saveLayouts()
                placeButtonsFromLayout(layoutsMap[currentLayoutKey]!!)
            }
            .setNegativeButton("Sil") { _, _ ->
                layoutsMap[currentLayoutKey]!!.buttons.remove(spec)
                saveLayouts()
                placeButtonsFromLayout(layoutsMap[currentLayoutKey]!!)
            }
            .show()
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply { hint = "İsim" }
        AlertDialog.Builder(this)
            .setTitle("Preset Kaydet")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().ifBlank { "Preset" }
                layoutsMap[name] = LayoutSpec(
                    name,
                    layoutsMap[currentLayoutKey]!!.buttons.map { it.copy() }.toMutableList()
                )
                saveLayouts()
                updatePresetSpinner(findViewById(R.id.spinnerPresets))
            }
            .show()
    }

    private fun showModeSelectionDialog() {
        val modes = arrayOf("🔄 Auto", "🎮 Classic", "🖱️ Split")
        val vals = arrayOf("auto", "classic", "split")
        AlertDialog.Builder(this)
            .setTitle("Mod Seç")
            .setItems(modes) { _, i ->
                prefs.edit().putString("ui_mode", vals[i]).apply()
                recreate()
            }
            .show()
    }

    private fun promptSetServerIp() {
        val input = EditText(this).apply { hint = "192.168.1.x" }
        AlertDialog.Builder(this)
            .setTitle("Sunucu IP")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                udpService?.setServerIp(input.text.toString())
                updateStatus()
            }
            .show()
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON PERSISTENCE
    // ═══════════════════════════════════════════════════════════════
    private fun saveLayouts() {
        val json = JSONObject()
        layoutsMap.forEach { (k, v) ->
            val arr = JSONArray()
            v.buttons.forEach { b ->
                arr.put(JSONObject().apply {
                    put("id", b.id)
                    put("x", b.xPercent.toDouble())
                    put("y", b.yPercent.toDouble())
                    put("label", b.label)
                    put("size", b.sizeDp)
                    put("haptic", b.haptic)
                })
            }
            json.put(k, JSONObject().apply { put("buttons", arr) })
        }
        prefs.edit().putString(KEY_LAYOUTS, json.toString()).apply()
    }

    private fun loadLayoutsMap(): MutableMap<String, LayoutSpec> {
        val map = mutableMapOf<String, LayoutSpec>()
        val raw = prefs.getString(KEY_LAYOUTS, null) ?: return map
        try {
            val top = JSONObject(raw)
            for (key in top.keys()) {
                val l = LayoutSpec(key)
                val arr = top.getJSONObject(key).getJSONArray("buttons")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    l.buttons.add(
                        ButtonSpec(
                            o.getString("id"),
                            o.getDouble("x").toFloat(),
                            o.getDouble("y").toFloat(),
                            o.optInt("size", 64),
                            o.optString("label", "B"),
                            "BUTTON",
                            o.optBoolean("haptic", true)
                        )
                    )
                }
                map[key] = l
            }
        } catch (_: Exception) {}
        return map
    }

    private fun updatePresetSpinner(spinner: Spinner?) {
        if (spinner == null) return
        val names = layoutsMap.keys.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val index = names.indexOf(currentLayoutKey)
        if (index >= 0) spinner.setSelection(index)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentLayoutKey = names[position]
                if (effectiveMode == "classic") {
                    joystickArea?.post { placeButtonsFromLayout(layoutsMap[currentLayoutKey]!!) }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILS
    // ═══════════════════════════════════════════════════════════════
    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun doHaptic() {
        if (!globalHaptic) return
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v?.vibrate(15)
        }
    }
}
package com.benim.benim

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.*
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

    // --- Sabitler ---
    companion object {
        private const val TAG = "MainActivity"

        // Gamepad Buton Maskları (16-bit)
        const val BTN_A = 0x0001
        const val BTN_B = 0x0002
        const val BTN_X = 0x0004
        const val BTN_Y = 0x0008
        const val BTN_L1 = 0x0010
        const val BTN_R1 = 0x0020
        const val BTN_SELECT = 0x0040
        const val BTN_START = 0x0080

        const val BTN_L2 = 0x0100
        const val BTN_R2 = 0x0200
        const val BTN_L3 = 0x0400
        const val BTN_R3 = 0x0800
        const val BTN_HOME = 0x1000
        const val BTN_DPAD_UP = 0x2000
        const val BTN_DPAD_DOWN = 0x4000
        const val BTN_DPAD_LEFT = 0x8000
    }

    // --- Değişkenler ---
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val PREFS_LAYOUTS = "gamepad_layouts"
    private val KEY_LAYOUTS = "layouts_json"

    // Mod Yönetimi
    private var currentMode: String = "auto"
    private var effectiveMode: String = "classic"

    // UI Elementleri (Nullable)
    private var txtStatus: TextView? = null

    // Classic Mode UI
    private var joystickArea: FrameLayout? = null
    private var rightJoystickArea: FrameLayout? = null
    private var joystickThumb: View? = null
    private var rightJoystickThumb: View? = null

    // Split Mode UI
    private var touchpadArea: FrameLayout? = null
    private var btnMouseLeft: Button? = null
    private var btnMouseRight: Button? = null
    private var btnMouseMiddle: Button? = null

    // Controller Durumu
    private var currentButtons = 0
    private var lastJoystickX: Byte = 0
    private var lastJoystickY: Byte = 0
    private var rightJoystickX: Byte = 0
    private var rightJoystickY: Byte = 0
    private var dpadX: Byte = 0
    private var dpadY: Byte = 0

    // Touchpad Durumu
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchpadActive = false
    private var lastScrollY = 0f

    // Ayarlar
    private var editMode = false
    private var centerOnTouchEnabled = true
    private var globalHaptic = true

    // Layout Verileri
    private var layoutsMap: MutableMap<String, LayoutSpec> = mutableMapOf()
    private var currentLayoutKey = "default"
    private val specViewMap = mutableMapOf<String, DraggableButton>()

    // Servis ve Zamanlayıcılar
    private var udpService: UdpService? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var statusTimer: Timer? = null

    // --- Service Connection ---
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            udpService = (binder as UdpService.LocalBinder).getService()
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
    // LIFECYCLE METODLARI
    // ═══════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        // Tam Ekran Ayarları (Layout No Limits)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        try { setTheme(R.style.Theme_Benim_NoActionBar) } catch (_: Exception) {}

        super.onCreate(savedInstanceState)

        // Modu Belirle ve Layout'u Yükle
        currentMode = prefs.getString("ui_mode", "auto") ?: "auto"
        effectiveMode = resolveEffectiveMode()

        val layoutId = if (effectiveMode == "classic") R.layout.activity_main_classic else R.layout.activity_main_split
        setContentView(layoutId)

        // --- YENİ MODERN IMMERSIVE MODE BAŞLANGIÇ ---

        // 1. İçeriğin sistem barlarının arkasına (tam ekran) yayılmasını sağlar
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 2. Controller'ı alıp gizleme ve davranış ayarlarını yapıyoruz
        WindowCompat.getInsetsController(window, window.decorView).let { controller ->
            // Bildirim ve Navigasyon çubuklarını gizle
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // Ekran kaydırılınca geçici olarak görünsünler (Sticky Behavior)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // --- YENİ MODERN IMMERSIVE MODE BİTİŞ ---

        // Başlatma İşlemleri
        bindViews()
        setupListeners()
        registerStatusReceiver()
        startUdpService()

        // Mod Özel Ayarları
        if (effectiveMode == "classic") setupClassicMode()
        else setupSplitMode()

        // UI Güncelleme Zamanlayıcısı
        startStatusTimer()
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        try { unbindService(serviceConnection) } catch (_: Exception) {}
        statusTimer?.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (currentMode == "auto") recreate()
    }

    // ═══════════════════════════════════════════════════════════════
    // YARDIMCI BAŞLATMA METODLARI
    // ═══════════════════════════════════════════════════════════════
    private fun resolveEffectiveMode(): String {
        return when (currentMode) {
            "classic" -> "classic"
            "split" -> "split"
            "auto" -> if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "classic" else "split"
            else -> "classic"
        }
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(UdpService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun startUdpService() {
        val intent = Intent(this, UdpService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
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

        // Classic
        joystickArea = findViewById(R.id.joystickArea)
        rightJoystickArea = findViewById(R.id.rightJoystickArea)
        joystickThumb = findViewById(R.id.joystickThumb)
        rightJoystickThumb = findViewById(R.id.rightJoystickThumb)

        // Split
        touchpadArea = findViewById(R.id.touchpadArea)
        btnMouseLeft = findViewById(R.id.btnMouseLeft)
        btnMouseRight = findViewById(R.id.btnMouseRight)
        btnMouseMiddle = findViewById(R.id.btnMouseMiddle)
    }

    private fun setupListeners() {
        txtStatus?.setOnLongClickListener { promptSetServerIp(); true }

        findViewById<Button?>(R.id.btnDiscover)?.setOnClickListener {
            udpService?.discoverServer()
            Toast.makeText(this, "Sunucu aranıyor...", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button?>(R.id.btnChangeMode)?.setOnClickListener { showModeSelectionDialog() }

        findViewById<Switch?>(R.id.switchHaptic)?.apply {
            isChecked = globalHaptic
            setOnCheckedChangeListener { _, checked -> globalHaptic = checked }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CLASSIC MODE (GAMEPAD)
    // ═══════════════════════════════════════════════════════════════
    private fun setupClassicMode() {
        // Layout Yükle
        layoutsMap = loadLayoutsMap()
        if (!layoutsMap.containsKey(currentLayoutKey)) {
            layoutsMap[currentLayoutKey] = LayoutSpec("Default")
        }

        // Joystick Listeners
        joystickArea?.setOnTouchListener { v, ev -> handleJoystickTouch(v, ev, true); true }
        joystickArea?.setOnLongClickListener { pressButton(BTN_L3); true }

        rightJoystickArea?.setOnTouchListener { v, ev -> handleJoystickTouch(v, ev, false); true }
        rightJoystickArea?.setOnLongClickListener { pressButton(BTN_R3); true }

        // Buton Tanımları
        val buttons = mapOf(
            R.id.btnA to BTN_A, R.id.btnB to BTN_B, R.id.btnX to BTN_X, R.id.btnY to BTN_Y,
            R.id.btnL1 to BTN_L1, R.id.btnR1 to BTN_R1, R.id.btnL2 to BTN_L2, R.id.btnR2 to BTN_R2,
            R.id.btnStart to BTN_START, R.id.btnSelect to BTN_SELECT, R.id.btnHome to BTN_HOME
        )

        buttons.forEach { (id, mask) -> setupGamepadButton(id, mask) }

        // D-Pad Tanımları
        setupDpadButton(R.id.btnDpadUp, 0, -1)
        setupDpadButton(R.id.btnDpadDown, 0, 1)
        setupDpadButton(R.id.btnDpadLeft, -1, 0)
        setupDpadButton(R.id.btnDpadRight, 1, 0)

        // Edit Mode Butonları
        findViewById<Button?>(R.id.btnEditMode)?.setOnClickListener { toggleEditMode() }
        findViewById<Button?>(R.id.btnAdd)?.setOnClickListener { showAddButtonDialog() }
        findViewById<Button?>(R.id.btnSavePreset)?.setOnClickListener { showSavePresetDialog() }

        // Spinner ve Switch Ayarları (Düzeltilmiş)
        val spinner = findViewById<Spinner?>(R.id.spinnerPresets)
        if (spinner != null) updatePresetSpinner(spinner)

        findViewById<Switch?>(R.id.switchCenterTouch)?.apply {
            isChecked = centerOnTouchEnabled
            setOnCheckedChangeListener { _, isChecked: Boolean ->
                centerOnTouchEnabled = isChecked
            }
        }

        // Custom Butonları Yerleştir
        joystickArea?.post { placeButtonsFromLayout(layoutsMap[currentLayoutKey]!!) }
    }

    private fun setupGamepadButton(viewId: Int, mask: Int) {
        findViewById<View?>(viewId)?.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentButtons = currentButtons or mask
                    v.isPressed = true
                    if (globalHaptic) doHaptic()
                    sendFullGamepad()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    currentButtons = currentButtons and mask.inv()
                    v.isPressed = false
                    sendFullGamepad()
                }
            }
            true
        }
    }

    private fun setupDpadButton(viewId: Int, dx: Int, dy: Int) {
        findViewById<View?>(viewId)?.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dpadX = (dx * 127).toByte()
                    dpadY = (dy * 127).toByte()
                    v.isPressed = true
                    if (globalHaptic) doHaptic()
                    sendFullGamepad()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dpadX = 0; dpadY = 0
                    v.isPressed = false
                    sendFullGamepad()
                }
            }
            true
        }
    }

    private fun pressButton(mask: Int) {
        currentButtons = currentButtons or mask
        sendFullGamepad()
        if (globalHaptic) doHaptic()
        uiHandler.postDelayed({
            currentButtons = currentButtons and mask.inv()
            sendFullGamepad()
        }, 100L)
    }

    // ═══════════════════════════════════════════════════════════════
    // JOYSTICK MANTIĞI
    // ═══════════════════════════════════════════════════════════════
    private fun handleJoystickTouch(view: View, event: MotionEvent, isLeft: Boolean) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val thumb = if (isLeft) joystickThumb else rightJoystickThumb

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Center-on-touch (sadece sol stick için ve ayar açıksa)
                val cx = if (isLeft && centerOnTouchEnabled && event.action == MotionEvent.ACTION_DOWN) event.x else w / 2f
                val cy = if (isLeft && centerOnTouchEnabled && event.action == MotionEvent.ACTION_DOWN) event.y else h / 2f

                val dx = (event.x - cx) / (w / 2f)
                val dy = (event.y - cy) / (h / 2f)

                val clampedX = dx.coerceIn(-1f, 1f)
                val clampedY = dy.coerceIn(-1f, 1f)

                val byteX = (clampedX * 127f).roundToInt().toByte()
                val byteY = (clampedY * 127f).roundToInt().toByte()

                if (isLeft) { lastJoystickX = byteX; lastJoystickY = byteY }
                else { rightJoystickX = byteX; rightJoystickY = byteY }

                thumb?.let {
                    val maxOffset = (w/2f) - (it.width/2f)
                    it.translationX = clampedX * maxOffset
                    it.translationY = clampedY * maxOffset
                }
                sendFullGamepad()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isLeft) { lastJoystickX = 0; lastJoystickY = 0 }
                else { rightJoystickX = 0; rightJoystickY = 0 }

                thumb?.animate()?.translationX(0f)?.translationY(0f)?.setDuration(100)?.start()
                sendFullGamepad()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AĞ GÖNDERİMİ
    // ═══════════════════════════════════════════════════════════════
    private fun sendFullGamepad() {
        // Paket: [TYPE, BTN_LOW, BTN_HIGH, LX, LY, RX, RY, DX, DY]
        udpService?.sendRaw(byteArrayOf(
            0x01,
            (currentButtons and 0xFF).toByte(),
            ((currentButtons shr 8) and 0xFF).toByte(),
            lastJoystickX, lastJoystickY,
            rightJoystickX, rightJoystickY,
            dpadX, dpadY
        ))
    }

    private fun sendMouseMove(dx: Int, dy: Int) {
        udpService?.sendRaw(byteArrayOf(0x02, dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte(), 0, 0))
    }

    private fun sendMouseButton(btn: Int, pressed: Boolean) {
        udpService?.sendRaw(byteArrayOf(0x03, btn.toByte(), if (pressed) 1 else 0, 0, 0))
    }

    private fun sendMouseWheel(delta: Int) {
        udpService?.sendRaw(byteArrayOf(0x04, delta.coerceIn(-127, 127).toByte(), 0, 0, 0))
    }

    // ═══════════════════════════════════════════════════════════════
    // SPLIT MODE (MOUSE)
    // ═══════════════════════════════════════════════════════════════
    private fun setupSplitMode() {
        touchpadArea?.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastTouchX = ev.x; lastTouchY = ev.y; touchpadActive = true }
                MotionEvent.ACTION_MOVE -> {
                    if (touchpadActive) {
                        val dx = (ev.x - lastTouchX).toInt()
                        val dy = (ev.y - lastTouchY).toInt()
                        if (abs(dx) > 1 || abs(dy) > 1) {
                            sendMouseMove(dx, dy)
                            lastTouchX = ev.x; lastTouchY = ev.y
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchpadActive = false
            }
            true
        }

        val mouseBtns = listOf(btnMouseLeft to 0, btnMouseRight to 1, btnMouseMiddle to 2)
        mouseBtns.forEach { (view, id) ->
            view?.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { sendMouseButton(id, true); if (globalHaptic) doHaptic() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sendMouseButton(id, false)
                }
                true
            }
        }

        findViewById<View?>(R.id.scrollIndicator)?.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> lastScrollY = ev.y
                MotionEvent.ACTION_MOVE -> {
                    val dy = (lastScrollY - ev.y) / 20f
                    if (abs(dy) > 1) { sendMouseWheel(dy.toInt()); lastScrollY = ev.y }
                }
            }
            true
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // EDIT & PRESETS
    // ═══════════════════════════════════════════════════════════════
    private fun placeButtonsFromLayout(layout: LayoutSpec) {
        val area = joystickArea ?: return

        // Sadece dinamik butonları temizle (isteğe bağlı)
        // area.removeAllViews() // Dikkat: Bu sabit view'ları da silebilir!

        // Daha güvenli yöntem: SpecViewMap'teki butonları sil
        specViewMap.values.forEach { area.removeView(it) }
        specViewMap.clear()

        layout.buttons.forEach { spec ->
            val btn = createDraggableButton(spec)
            area.addView(btn)
            area.post {
                val size = dpToPx(spec.sizeDp)
                btn.layoutParams = FrameLayout.LayoutParams(size, size)
                btn.x = (spec.xPercent * area.width - size/2f).coerceIn(0f, (area.width-size).toFloat())
                btn.y = (spec.yPercent * area.height - size/2f).coerceIn(0f, (area.height-size).toFloat())
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
                // HATA BURADAYDI. Düzeltme:
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
            "A" -> BTN_A; "B" -> BTN_B; "X" -> BTN_X; "Y" -> BTN_Y
            "START" -> BTN_START; "SELECT" -> BTN_SELECT; "L1" -> BTN_L1; "R1" -> BTN_R1
            else -> BTN_A
        }
        specViewMap[spec.id]?.isEnabled = false
        currentButtons = currentButtons or mask
        sendFullGamepad()
        if (globalHaptic && spec.haptic) doHaptic()
        uiHandler.postDelayed({
            currentButtons = currentButtons and mask.inv()
            sendFullGamepad()
            specViewMap[spec.id]?.isEnabled = true
        }, 100L)
    }

    // ═══════════════════════════════════════════════════════════════
    // UI HELPERS & DIALOGS
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

                val statusIcon = if (ip == null) "⚪" else if (alive) "🟢" else "🟡"
                val pingText = if (ping >= 0) "${ping}ms" else if (ip!=null && !alive) "⏳" else "--"

                txtStatus?.text = "$modeEmoji $statusIcon ${ip ?: "IP Yok"} | $pingText" +
                        (if(editMode) " ✏️" else "")
            } catch (_: Exception) {}
        }
    }

    private fun toggleEditMode() {
        editMode = !editMode
        specViewMap.values.forEach { it.editMode = editMode }
        findViewById<Button?>(R.id.btnEditMode)?.text = if (editMode) "🔒" else "✏️"
        updateStatus()
    }

    private fun showAddButtonDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50,20,50,0) }
        val lbl = EditText(this).apply { hint = "Etiket" }
        val sz = EditText(this).apply { hint = "Boyut (64)"; setText("64") }
        layout.addView(lbl); layout.addView(sz)

        AlertDialog.Builder(this).setTitle("Ekle").setView(layout).setPositiveButton("Ekle") { _, _ ->
            val spec = ButtonSpec("btn_${System.currentTimeMillis()}", 0.5f, 0.5f,
                sz.text.toString().toIntOrNull()?:64, lbl.text.toString().ifBlank{"B"})
            layoutsMap[currentLayoutKey]!!.buttons.add(spec)
            saveLayouts()
            placeButtonsFromLayout(layoutsMap[currentLayoutKey]!!)
        }.show()
    }

    private fun showEditDialog(spec: ButtonSpec) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50,20,50,0) }
        val lbl = EditText(this).apply { setText(spec.label) }
        val sz = EditText(this).apply { setText(spec.sizeDp.toString()) }
        layout.addView(lbl); layout.addView(sz)

        AlertDialog.Builder(this).setTitle("Düzenle").setView(layout)
            .setPositiveButton("Kaydet") { _, _ ->
                spec.label = lbl.text.toString()
                spec.sizeDp = sz.text.toString().toIntOrNull()?:64
                saveLayouts()
                placeButtonsFromLayout(layoutsMap[currentLayoutKey]!!)
            }
            .setNegativeButton("Sil") { _, _ ->
                layoutsMap[currentLayoutKey]!!.buttons.remove(spec)
                saveLayouts()
                placeButtonsFromLayout(layoutsMap[currentLayoutKey]!!)
            }.show()
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply { hint = "İsim" }
        AlertDialog.Builder(this).setTitle("Kaydet").setView(input).setPositiveButton("OK") { _, _ ->
            val name = input.text.toString().ifBlank { "Preset" }
            layoutsMap[name] = LayoutSpec(name, layoutsMap[currentLayoutKey]!!.buttons.map { it.copy() }.toMutableList())
            saveLayouts()
            updatePresetSpinner(findViewById(R.id.spinnerPresets))
        }.show()
    }

    private fun showModeSelectionDialog() {
        val modes = arrayOf("🔄 Auto", "🎮 Classic", "🖱️ Split")
        val vals = arrayOf("auto", "classic", "split")
        AlertDialog.Builder(this).setTitle("Mod").setItems(modes) { _, i ->
            prefs.edit().putString("ui_mode", vals[i]).apply()
            recreate()
        }.show()
    }

    private fun promptSetServerIp() {
        val input = EditText(this).apply { hint = "192.168.1.x" }
        AlertDialog.Builder(this).setTitle("IP").setView(input).setPositiveButton("OK") { _, _ ->
            udpService?.setServerIp(input.text.toString())
            updateStatus()
        }.show()
    }

    // --- JSON & Utils ---
    private fun saveLayouts() {
        val json = JSONObject()
        layoutsMap.forEach { (k, v) ->
            val arr = JSONArray()
            v.buttons.forEach { b ->
                arr.put(JSONObject().apply {
                    put("id", b.id); put("x", b.xPercent.toDouble()); put("y", b.yPercent.toDouble())
                    put("label", b.label); put("size", b.sizeDp); put("haptic", b.haptic)
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
                    l.buttons.add(ButtonSpec(o.getString("id"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                        o.optInt("size", 64), o.optString("label", "B"), "BUTTON", o.optBoolean("haptic", true)))
                }
                map[key] = l
            }
        } catch (_: Exception) {}
        return map
    }

    private fun updatePresetSpinner(spinner: Spinner) {
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

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun doHaptic() {
        if (!globalHaptic) return
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            v?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        else v?.vibrate(15)
    }
}
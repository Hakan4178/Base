package com.benim.benim

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
import com.benim.benim.net.UdpService
import com.benim.benim.ui.DraggableButton
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat

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

    // Preferences
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    // Mode: "classic" (gamepad), "split" (mouse), "auto" (orientation-based)
    private var currentMode: String = "auto"
    private var effectiveMode: String = "classic"

    // Views (nullable - not all exist in every layout)
    private var txtStatus: TextView? = null
    private var joystickArea: FrameLayout? = null
    private var touchpadArea: FrameLayout? = null
    private var btnMouseLeft: Button? = null
    private var btnMouseRight: Button? = null
    private var btnMouseMiddle: Button? = null

    // Runtime state
    private var currentButtons = 0
    private var lastJoystickX: Byte = 0
    private var lastJoystickY: Byte = 0
    private var editMode = false
    private var centerOnTouchEnabled = true
    private var globalHaptic = true

    // Joystick state
    private var joystickCenterX = 0f
    private var joystickCenterY = 0f

    // Touchpad state
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchpadActive = false
    private var lastScrollY = 0f

    // Service
    private var udpService: UdpService? = null
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

    // Presets (only for classic mode)
    private val PREFS_LAYOUTS = "gamepad_layouts"
    private val KEY_LAYOUTS = "layouts_json"
    private var layoutsMap: MutableMap<String, LayoutSpec> = mutableMapOf()
    private var currentLayoutKey = "default"
    private val specViewMap = mutableMapOf<String, DraggableButton>()

    // Handlers
    private val uiHandler = Handler(Looper.getMainLooper())
    private var statusTimer: Timer? = null

    // Button masks
    companion object {
        const val BTN_A = 0b00000001
        const val BTN_B = 0b00000010
        const val BTN_X = 0b00000100
        const val BTN_Y = 0b00001000
        const val BTN_START = 0b00010000
        const val BTN_SELECT = 0b00100000
        const val BTN_L1 = 0b01000000
        const val BTN_R1 = 0b10000000
        private const val TAG = "MainActivity"
    }

    // Ping receiver
    private val pingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.benim.ACTION_PING") {
                updateStatus()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        // Fullscreen flags
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        setTheme(R.style.Theme_Benim_NoActionBar)

        super.onCreate(savedInstanceState)

        // Determine mode
        currentMode = prefs.getString("ui_mode", "auto") ?: "auto"
        effectiveMode = resolveEffectiveMode()

        // Set layout based on mode
        when (effectiveMode) {
            "classic" -> setContentView(R.layout.activity_main_classic)
            "split" -> setContentView(R.layout.activity_main_split)
            else -> setContentView(R.layout.activity_main_classic)
        }

        // Immersive mode
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        // Bind views
        bindViews()

        // Setup listeners
        setupCommonListeners()

        // Register receiver & load data
        // ✅ DÜZELTME: Android 14 için RECEIVER_EXPORTED veya RECEIVER_NOT_EXPORTED bayrağı ekleyin
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33 (Android 13) ve üzeri için ContextCompat kullanın
            ContextCompat.registerReceiver(
                this,
                pingReceiver,
                IntentFilter("com.benim.ACTION_PING"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            // API 33'ten önceki versiyonlar için eski yöntem
            @Suppress("DEPRECATION")
            registerReceiver(pingReceiver, IntentFilter("com.benim.ACTION_PING"))
        }

        // Start service
        Intent(this, UdpService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }

        // Mode-specific setup
        when (effectiveMode) {
            "classic" -> setupClassicMode()
            "split" -> setupSplitMode()
        }

        // Periodic status update
        statusTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    runOnUiThread { updateStatus() }
                }
            }, 0, 1000L)
        }

        updateStatus()
    }

    override fun onDestroy() {
        try { unregisterReceiver(pingReceiver) } catch (_: Exception) {}
        try { unbindService(serviceConnection) } catch (_: Exception) {}
        statusTimer?.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Only react to orientation change in auto mode
        if (currentMode == "auto") {
            val newEffective = resolveEffectiveMode()
            if (newEffective != effectiveMode) {
                recreate()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MODE RESOLUTION
    // ═══════════════════════════════════════════════════════════════
    private fun resolveEffectiveMode(): String {
        return when (currentMode) {
            "classic" -> "classic"
            "split" -> "split"
            "auto" -> {
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    "classic" else "split"
            }
            else -> "classic"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // VIEW BINDING
    // ═══════════════════════════════════════════════════════════════
    private fun bindViews() {
        txtStatus = findViewById(R.id.txtStatus)

        // Mode-specific views
        joystickArea = findViewById(R.id.joystickArea)
        touchpadArea = findViewById(R.id.touchpadArea)
        btnMouseLeft = findViewById(R.id.btnMouseLeft)
        btnMouseRight = findViewById(R.id.btnMouseRight)
        btnMouseMiddle = findViewById(R.id.btnMouseMiddle)
    }

    private fun setupCommonListeners() {
        // Status long press → manual IP
        txtStatus?.setOnLongClickListener {
            promptSetServerIp()
            true
        }

        // Discover button
        findViewById<Button?>(R.id.btnDiscover)?.setOnClickListener {
            udpService?.discoverServer()
            Toast.makeText(this, "Sunucu aranıyor...", Toast.LENGTH_SHORT).show()
        }

        // Mode change button
        findViewById<Button?>(R.id.btnChangeMode)?.setOnClickListener {
            showModeSelectionDialog()
        }

        // Haptic switch
        findViewById<Switch?>(R.id.switchHaptic)?.apply {
            isChecked = globalHaptic
            setOnCheckedChangeListener { _, checked -> globalHaptic = checked }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CLASSIC MODE (GAMEPAD)
    // ═══════════════════════════════════════════════════════════════
    private fun setupClassicMode() {
        // Load presets
        layoutsMap = loadLayoutsMap()
        if (!layoutsMap.containsKey(currentLayoutKey)) {
            layoutsMap[currentLayoutKey] = LayoutSpec("Default")
        }

        // Center touch switch
        findViewById<Switch?>(R.id.switchCenterTouch)?.apply {
            isChecked = centerOnTouchEnabled
            setOnCheckedChangeListener { _, checked -> centerOnTouchEnabled = checked }
        }

        // Edit mode buttons
        findViewById<Button?>(R.id.btnEditMode)?.setOnClickListener { toggleEditMode() }
        findViewById<Button?>(R.id.btnAdd)?.setOnClickListener { showAddButtonDialog() }
        findViewById<Button?>(R.id.btnSavePreset)?.setOnClickListener { showSavePresetDialog() }

        // Preset spinner
        findViewById<Spinner?>(R.id.spinnerPresets)?.let { updatePresetSpinner(it) }

        // Joystick touch
        joystickArea?.setOnTouchListener { v, ev ->
            handleJoystickTouch(v, ev)
            true
        }

        // Gamepad buttons
        setupGamepadButtons()

        // Place custom buttons
        joystickArea?.post {
            placeButtonsFromLayout(layoutsMap[currentLayoutKey]!!)
        }
    }

    private fun setupGamepadButtons() {
        val buttonMappings = listOf(
            R.id.btnA to BTN_A,
            R.id.btnB to BTN_B,
            R.id.btnX to BTN_X,
            R.id.btnY to BTN_Y,
            R.id.btnStart to BTN_START,
            R.id.btnSelect to BTN_SELECT,
            R.id.btnL1 to BTN_L1,
            R.id.btnR1 to BTN_R1
        )

        for ((viewId, mask) in buttonMappings) {
            findViewById<Button?>(viewId)?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        currentButtons = currentButtons or mask
                        if (globalHaptic) doHaptic()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        currentButtons = currentButtons and mask.inv()
                    }
                }
                sendJoystick()
                true
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SPLIT MODE (MOUSE)
    // ═══════════════════════════════════════════════════════════════
    private fun setupSplitMode() {
        // Touchpad
        touchpadArea?.setOnTouchListener { _, event ->
            handleTouchpadTouch(event)
            true
        }

        // Mouse buttons
        btnMouseLeft?.setOnTouchListener { _, event ->
            handleMouseButton(0, event)
            true
        }

        btnMouseRight?.setOnTouchListener { _, event ->
            handleMouseButton(1, event)
            true
        }

        btnMouseMiddle?.setOnTouchListener { _, event ->
            handleMouseButton(2, event)
            true
        }

        // Scroll indicator
        findViewById<View?>(R.id.scrollIndicator)?.setOnTouchListener { _, event ->
            handleScrollTouch(event)
            true
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // JOYSTICK HANDLING
    // ═══════════════════════════════════════════════════════════════
    private fun handleJoystickTouch(view: View, event: MotionEvent) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                joystickCenterX = if (centerOnTouchEnabled) event.x.coerceIn(0f, w) else w / 2f
                joystickCenterY = if (centerOnTouchEnabled) event.y.coerceIn(0f, h) else h / 2f
                updateJoystickFromCoords(event.x, event.y, w, h)
            }
            MotionEvent.ACTION_MOVE -> {
                updateJoystickFromCoords(event.x, event.y, w, h)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastJoystickX = 0
                lastJoystickY = 0
                sendJoystick()
            }
        }
        updateStatus()
    }

    private fun updateJoystickFromCoords(x: Float, y: Float, w: Float, h: Float) {
        val dx = (x - joystickCenterX) / (w / 2f)
        val dy = (y - joystickCenterY) / (h / 2f)
        lastJoystickX = (dx.coerceIn(-1f, 1f) * 127f).roundToInt().toByte()
        lastJoystickY = (dy.coerceIn(-1f, 1f) * 127f).roundToInt().toByte()
        sendJoystick()
    }

    private fun sendJoystick() {
        udpService?.sendJoystick(currentButtons.toByte(), lastJoystickX, lastJoystickY, 0)
    }

    // ═══════════════════════════════════════════════════════════════
    // TOUCHPAD HANDLING
    // ═══════════════════════════════════════════════════════════════
    private fun handleTouchpadTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchpadActive = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchpadActive) return true
                val dx = (event.x - lastTouchX).toInt()
                val dy = (event.y - lastTouchY).toInt()
                if (abs(dx) > 1 || abs(dy) > 1) {
                    sendMouseMove(dx, dy)
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchpadActive = false
            }
        }
        return true
    }

    private fun handleMouseButton(button: Int, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                sendMouseButton(button, true)
                if (globalHaptic) doHaptic()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                sendMouseButton(button, false)
            }
        }
        return true
    }

    private fun handleScrollTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> lastScrollY = event.y
            MotionEvent.ACTION_MOVE -> {
                val dy = (lastScrollY - event.y) / 20f
                if (abs(dy) > 1) {
                    sendMouseWheel(dy.toInt())
                    lastScrollY = event.y
                }
            }
        }
        return true
    }

    // ═══════════════════════════════════════════════════════════════
    // MOUSE COMMANDS
    // ═══════════════════════════════════════════════════════════════
    private fun sendMouseMove(dx: Int, dy: Int) {
        val buf = byteArrayOf(
            0x02.toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            0, 0
        )
        udpService?.sendRaw(buf)
    }

    private fun sendMouseButton(button: Int, pressed: Boolean) {
        val buf = byteArrayOf(
            0x03.toByte(),
            button.toByte(),
            if (pressed) 1 else 0,
            0, 0
        )
        udpService?.sendRaw(buf)
    }

    private fun sendMouseWheel(delta: Int) {
        val buf = byteArrayOf(
            0x04.toByte(),
            delta.coerceIn(-127, 127).toByte(),
            0, 0, 0
        )
        udpService?.sendRaw(buf)
    }

    // ═══════════════════════════════════════════════════════════════
    // MODE SELECTION DIALOG
    // ═══════════════════════════════════════════════════════════════
    private fun showModeSelectionDialog() {
        val modes = arrayOf(
            "🔄 Auto (Oryantasyona göre)",
            "🎮 Classic (Gamepad)",
            "🖱️ Split (Mouse)"
        )
        val modeValues = arrayOf("auto", "classic", "split")

        AlertDialog.Builder(this)
            .setTitle("Kontrol Modu Seç")
            .setItems(modes) { _, which ->
                prefs.edit().putString("ui_mode", modeValues[which]).apply()
                recreate()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════
    // UI STATUS
    // ═══════════════════════════════════════════════════════════════
    private fun updateStatus() {
        val svc = udpService
        runOnUiThread {
            val modeEmoji = if (effectiveMode == "classic") "🎮" else "🖱️"
            val modeName = if (effectiveMode == "classic") "Gamepad" else "Mouse"

            if (svc == null) {
                txtStatus?.text = "$modeEmoji $modeName | Bağlanıyor..."
            } else {
                val target = try { svc.getServerIp() ?: "?" } catch (_: Exception) { "?" }
                val ping = try {
                    if (svc.lastPingMs >= 0) "${svc.lastPingMs}ms" else "--"
                } catch (_: Exception) { "--" }

                val extra = if (effectiveMode == "classic" && editMode) " | ✏️ Edit" else ""
                txtStatus?.text = "$modeEmoji $modeName | $target | $ping$extra"
            }

            // Update ping text if exists (split mode)
            findViewById<TextView?>(R.id.txtPing)?.text = "Ping: ${svc?.lastPingMs ?: "--"}ms"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // EDIT MODE (Classic only)
    // ═══════════════════════════════════════════════════════════════
    private fun toggleEditMode() {
        editMode = !editMode
        for ((_, v) in specViewMap) v.editMode = editMode
        findViewById<Button?>(R.id.btnEditMode)?.text = if (editMode) "🔒 Lock" else "✏️ Edit"
        updateStatus()
    }

    // ═══════════════════════════════════════════════════════════════
    // PRESET SYSTEM (Classic only)
    // ═══════════════════════════════════════════════════════════════
    private fun saveLayoutsMap(map: Map<String, LayoutSpec>) {
        val json = JSONObject()
        for ((k, layout) in map) {
            val jbuttons = JSONArray()
            for (b in layout.buttons) {
                jbuttons.put(JSONObject().apply {
                    put("id", b.id)
                    put("x", b.xPercent.toDouble())
                    put("y", b.yPercent.toDouble())
                    put("size", b.sizeDp)
                    put("label", b.label)
                    put("action", b.action)
                    put("haptic", b.haptic)
                })
            }
            json.put(k, JSONObject().apply {
                put("name", layout.name)
                put("buttons", jbuttons)
            })
        }
        getSharedPreferences(PREFS_LAYOUTS, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAYOUTS, json.toString())
            .apply()
    }

    private fun loadLayoutsMap(): MutableMap<String, LayoutSpec> {
        val raw = getSharedPreferences(PREFS_LAYOUTS, MODE_PRIVATE)
            .getString(KEY_LAYOUTS, null) ?: return mutableMapOf()

        val out = mutableMapOf<String, LayoutSpec>()
        val top = JSONObject(raw)

        for (key in top.keys()) {
            val jl = top.getJSONObject(key)
            val layout = LayoutSpec(jl.optString("name", key))
            val arr = jl.getJSONArray("buttons")

            for (i in 0 until arr.length()) {
                val jb = arr.getJSONObject(i)
                layout.buttons.add(ButtonSpec(
                    id = jb.getString("id"),
                    xPercent = jb.getDouble("x").toFloat(),
                    yPercent = jb.getDouble("y").toFloat(),
                    sizeDp = jb.optInt("size", 64),
                    label = jb.optString("label", "B"),
                    action = jb.optString("action", "BUTTON"),
                    haptic = jb.optBoolean("haptic", true)
                ))
            }
            out[key] = layout
        }
        return out
    }

    private fun updatePresetSpinner(spinner: Spinner) {
        val names = layoutsMap.keys.toList()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(names.indexOf(currentLayoutKey).coerceAtLeast(0))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentLayoutKey = names[pos]
                joystickArea?.removeAllViews()
                specViewMap.clear()
                placeButtonsFromLayout(layoutsMap[currentLayoutKey]!!)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun placeButtonsFromLayout(layout: LayoutSpec) {
        val area = joystickArea ?: return
        area.removeAllViews()
        specViewMap.clear()

        for (spec in layout.buttons) {
            val btn = createDraggableButtonForSpec(spec)
            area.addView(btn)
            area.post {
                val sizePx = dpToPx(spec.sizeDp)
                btn.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                btn.x = (spec.xPercent * area.width - sizePx / 2f).coerceIn(0f, (area.width - sizePx).toFloat())
                btn.y = (spec.yPercent * area.height - sizePx / 2f).coerceIn(0f, (area.height - sizePx).toFloat())
            }
            specViewMap[spec.id] = btn
        }
    }

    private fun createDraggableButtonForSpec(spec: ButtonSpec): DraggableButton {
        val btn = DraggableButton(this)

        btn.text = spec.label
        btn.editMode = this.editMode

        btn.clickAction = {
            if (!this.editMode) {
                performButtonPress(spec)
            } else {
                showEditButtonDialog(spec)
            }
        }

        btn.onLongSettings = {
            showEditButtonDialog(spec)
        }

        btn.onDragEnd = { _, _ ->
            val area = joystickArea
            if (area != null) {
                val cx = btn.x + btn.width / 2f
                val cy = btn.y + btn.height / 2f
                spec.xPercent = (cx / area.width.toFloat()).coerceIn(0f, 1f)
                spec.yPercent = (cy / area.height.toFloat()).coerceIn(0f, 1f)
                saveLayoutsMap(layoutsMap)
            }
        }

        return btn
    }
private fun performButtonPress(spec: ButtonSpec) {
    // ✅ DÜZELTME: this@MainActivity kullanarak scope belirt
    val svc = this@MainActivity.udpService

    if (svc == null) {
        Toast.makeText(this, "Servis bağlı değil", Toast.LENGTH_SHORT).show()
        return
    }

    val mask = when (spec.label.uppercase()) {
        "A" -> BTN_A
        "B" -> BTN_B
        "X" -> BTN_X
        "Y" -> BTN_Y
        "START" -> BTN_START
        "SELECT" -> BTN_SELECT
        "L1" -> BTN_L1
        "R1" -> BTN_R1
        else -> spec.label.toIntOrNull() ?: BTN_A
    }

    // Disable button temporarily
    specViewMap[spec.id]?.isEnabled = false

    // Press
    currentButtons = currentButtons or mask
    sendJoystick()
    if (globalHaptic && spec.haptic) doHaptic()

    // Release after delay
    uiHandler.postDelayed({
        currentButtons = currentButtons and mask.inv()
        sendJoystick()
        specViewMap[spec.id]?.isEnabled = true
    }, 80L)
}
    // ═══════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════
    private fun showAddButtonDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val labelInput = EditText(this).apply { hint = "Label (A, B, X, Y...)" }
        val sizeInput = EditText(this).apply { hint = "Size dp (64)" }
        layout.addView(labelInput)
        layout.addView(sizeInput)

        AlertDialog.Builder(this)
            .setTitle("Buton Ekle")
            .setView(layout)
            .setPositiveButton("Ekle") { _, _ ->
                val spec = ButtonSpec(
                    id = "btn_${System.currentTimeMillis()}",
                    xPercent = 0.5f,
                    yPercent = 0.5f,
                    sizeDp = sizeInput.text.toString().toIntOrNull() ?: 64,
                    label = labelInput.text.toString().ifBlank { "B" }
                )
                layoutsMap[currentLayoutKey]?.buttons?.add(spec)
                saveLayoutsMap(layoutsMap)

                val btn = createDraggableButtonForSpec(spec)
                specViewMap[spec.id] = btn
                joystickArea?.addView(btn)
                joystickArea?.post {
                    val sizePx = dpToPx(spec.sizeDp)
                    btn.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                    btn.x = ((joystickArea?.width ?: 0) / 2f - sizePx / 2f)
                    btn.y = ((joystickArea?.height ?: 0) / 2f - sizePx / 2f)
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showEditButtonDialog(spec: ButtonSpec) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val labelInput = EditText(this).apply { setText(spec.label) }
        val sizeInput = EditText(this).apply { setText(spec.sizeDp.toString()) }
        val hapticCb = CheckBox(this).apply { text = "Titreşim"; isChecked = spec.haptic }
        layout.addView(labelInput)
        layout.addView(sizeInput)
        layout.addView(hapticCb)

        AlertDialog.Builder(this)
            .setTitle("Buton Düzenle")
            .setView(layout)
            .setPositiveButton("Kaydet") { _, _ ->
                spec.label = labelInput.text.toString().ifEmpty { spec.label }
                spec.sizeDp = sizeInput.text.toString().toIntOrNull() ?: spec.sizeDp
                spec.haptic = hapticCb.isChecked

                specViewMap[spec.id]?.let { btn ->
                    btn.text = spec.label
                    val sizePx = dpToPx(spec.sizeDp)
                    val centerX = btn.x + btn.width / 2f
                    val centerY = btn.y + btn.height / 2f
                    btn.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                    btn.x = (centerX - sizePx / 2f).coerceIn(0f, ((joystickArea?.width ?: 0) - sizePx).toFloat())
                    btn.y = (centerY - sizePx / 2f).coerceIn(0f, ((joystickArea?.height ?: 0) - sizePx).toFloat())
                }
                saveLayoutsMap(layoutsMap)
            }
            .setNegativeButton("Sil") { _, _ ->
                layoutsMap[currentLayoutKey]?.buttons?.removeAll { it.id == spec.id }
                specViewMap.remove(spec.id)?.let { joystickArea?.removeView(it) }
                saveLayoutsMap(layoutsMap)
            }
            .show()
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply { hint = "Preset adı" }

        AlertDialog.Builder(this)
            .setTitle("Preset Kaydet")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val name = input.text.toString().ifEmpty { "preset_${System.currentTimeMillis()}" }
                val currentLayout = layoutsMap[currentLayoutKey]
                if (currentLayout != null) {
                    layoutsMap[name] = LayoutSpec(name, currentLayout.buttons.map { it.copy() }.toMutableList())
                    saveLayoutsMap(layoutsMap)
                    findViewById<Spinner?>(R.id.spinnerPresets)?.let { updatePresetSpinner(it) }
                    Toast.makeText(this, "Kaydedildi: $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun promptSetServerIp() {
        val et = EditText(this).apply { hint = "192.168.1.x" }

        AlertDialog.Builder(this)
            .setTitle("Sunucu IP")
            .setView(et)
            .setPositiveButton("Ayarla") { _, _ ->
                val ip = et.text.toString().trim().ifEmpty { null }
                udpService?.setServerIp(ip)
                Toast.makeText(this, "IP: $ip", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun doHaptic(ms: Long = 15L) {
        if (!globalHaptic) return
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(ms)
        }
    }
}
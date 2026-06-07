package com.example.skyplug

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.skyplug.databinding.ActivityMainBinding
import java.security.MessageDigest
import java.util.UUID

/**
 * Управление умной розеткой Redmond SkyPlug RSP-100S по BLE.
 *
 * Протокол восстановлен из приложения ReadyForSky (Nordic UART Service):
 *   Кадр:  0x55 | счётчик | команда | данные... | 0xAA
 *   Авторизация: команда 0xFF + 8 байт ключа
 *   Включить: 0x03 ; Выключить: 0x04 ; Статус: 0x06
 *   Ответ "успех" если первый байт данных != 0.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")   // RX
        val NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")  // TX
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CMD_AUTH = 0xFF.toByte()
        const val CMD_ON = 0x03.toByte()
        const val CMD_OFF = 0x04.toByte()
        const val CMD_STATE = 0x06.toByte()

        // Интервал опроса состояния, мс
        const val STATE_POLL_MS = 2000L
        // Индекс байта состояния в кадре: 0x55|counter|cmd|data... ; data[8] -> кадр[11]
        const val STATE_BYTE_INDEX = 11
        // Значение байта состояния, означающее "включено" (из кода приложения: state == 2)
        const val STATE_ON_VALUE = 2

        // Цвета круглой кнопки (Emerald-палитра)
        const val COLOR_ON = 0xFF10B981.toInt()       // изумруд
        const val COLOR_OFF = 0xFFF43F5E.toInt()      // розовый
        const val COLOR_UNKNOWN = 0xFF94A3B8.toInt()  // серо-голубой

        // 8-байтный ключ сопряжения. Можно поставить любой — главное, чтобы он
        // совпадал с тем, что розетка запомнила при сопряжении (режим мигания).
        val PAIR_TOKEN = byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1)
    }

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var counter: Int = 0
    private var scanning = false

    /** Ключ авторизации, считывается из поля ввода перед подключением. */
    private var pairToken: ByteArray = PAIR_TOKEN

    /** Текущее известное состояние розетки: true=вкл, false=выкл, null=неизвестно. */
    private var isOn: Boolean? = null

    /** Текущий цвет кнопки (для плавной анимации перехода). */
    private var currentColor: Int = COLOR_UNKNOWN
    private var colorAnimator: ValueAnimator? = null

    private val prefs by lazy { getSharedPreferences("skyplug", MODE_PRIVATE) }

    private val statePoller = object : Runnable {
        override fun run() {
            sendCommand(CMD_STATE)
            handler.postDelayed(this, STATE_POLL_MS)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startConnect()
        else log("Нет нужных разрешений Bluetooth/Location")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Восстанавливаем сохранённые MAC и пароль-ключ
        binding.macInput.setText(prefs.getString("mac", ""))
        binding.keyInput.setText(prefs.getString("pass", ""))

        binding.connectButton.setOnClickListener { ensurePermsThenConnect() }
        binding.toggleButton.setOnClickListener { onToggleClicked() }
        binding.copyLogButton.setOnClickListener { copyLog() }

        // Пробуем подключиться сразу при запуске (кнопка "Подключиться" остаётся
        // для повторного подключения / нового сопряжения)
        ensurePermsThenConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeGatt()
    }

    // ---------- разрешения ----------

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermsThenConnect() {
        val missing = requiredPerms().any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) permLauncher.launch(requiredPerms()) else startConnect()
    }

    // ---------- подключение ----------

    @SuppressLint("MissingPermission")
    private fun startConnect() {
        val adapter = btAdapter
        if (adapter == null || !adapter.isEnabled) {
            log("Включите Bluetooth")
            return
        }
        // Превращаем пароль-строку в 8-байтный ключ (пусто -> ключ по умолчанию)
        val passphrase = binding.keyInput.text.toString().trim()
        pairToken = deriveKey(passphrase)
        log("Ключ авторизации: ${pairToken.toHex()}" + if (passphrase.isEmpty()) " (по умолчанию)" else "")

        closeGatt()
        setStatus("Поиск устройства...")

        val mac = binding.macInput.text.toString().trim().uppercase()
        // Сохраняем настройки между запусками
        prefs.edit()
            .putString("mac", binding.macInput.text.toString().trim())
            .putString("pass", passphrase)
            .apply()
        if (mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) {
            // Прямое подключение по известному MAC — быстрее и надёжнее
            log("Подключение по MAC $mac")
            connectTo(adapter.getRemoteDevice(mac))
        } else {
            scanForDevice()
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanForDevice() {
        val scanner = btAdapter?.bluetoothLeScanner ?: return
        scanning = true
        log("Сканирование по сервису NUS...")

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids
                val match = uuids?.any { it.uuid == SERVICE_UUID } == true
                if (match || (result.device.name?.contains("RSP", true) == true)) {
                    scanner.stopScan(this)
                    scanning = false
                    log("Найдено: ${result.device.name ?: "?"} / ${result.device.address}")
                    connectTo(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                log("Ошибка сканирования: $errorCode")
            }
        }

        scanner.startScan(cb)
        handler.postDelayed({
            if (scanning) {
                scanning = false
                scanner.stopScan(cb)
                setStatus("Устройство не найдено")
                log("Таймаут поиска. Попробуйте указать MAC вручную.")
            }
        }, 15_000)
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        setStatus("Подключение...")
        gatt = device.connectGatt(this, false, gattCallback)
    }

    // ---------- GATT ----------

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                log("Соединение установлено, ищем сервисы...")
                g.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                log("Отключено (status=$status)")
                stopStatePolling()
                runOnUiThread {
                    setButtonsEnabled(false)
                    setStatus("Отключено")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                log("Сервис NUS не найден на устройстве")
                return
            }
            writeChar = service.getCharacteristic(WRITE_UUID)
            val notifyChar = service.getCharacteristic(NOTIFY_UUID)
            if (writeChar == null || notifyChar == null) {
                log("Характеристики не найдены")
                return
            }
            // Включаем уведомления (notify) на 6e400003
            g.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD_UUID)
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // CCCD записан -> теперь авторизуемся
            log("Уведомления включены, авторизация...")
            val authData = ByteArray(8)
            System.arraycopy(pairToken, 0, authData, 0, 8)
            writeFrame(CMD_AUTH, authData)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) = handleResponse(value)

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleResponse(ch.value)
        }
    }

    private fun handleResponse(value: ByteArray?) {
        if (value == null || value.size < 4) return
        // Кадр: 0x55 | счётчик | команда | данные... | 0xAA
        val cmd = value[2]
        val ok = value.size > 3 && value[3].toInt() != 0
        log("<- ${value.toHex()}")
        when (cmd) {
            CMD_AUTH -> runOnUiThread {
                if (ok) {
                    setStatus("Подключено и авторизовано ✓")
                    setButtonsEnabled(true)
                    log("Авторизация успешна")
                    startStatePolling()
                } else {
                    setStatus("Отказ авторизации")
                    log("Розетка отвергла ключ. Переведите её в режим сопряжения (зажать кнопку).")
                }
            }
            CMD_ON, CMD_OFF -> log(if (ok) "Команда принята" else "Команда отклонена")
            CMD_STATE -> if (value.size > STATE_BYTE_INDEX) {
                val on = value[STATE_BYTE_INDEX].toInt() == STATE_ON_VALUE
                setStateIndicator(on)
            }
        }
    }

    /** Нажатие на круглую кнопку — переключить состояние на противоположное. */
    private fun onToggleClicked() {
        when (isOn) {
            true -> { log("-> ВЫКЛ"); sendCommand(CMD_OFF) }
            false -> { log("-> ВКЛ"); sendCommand(CMD_ON) }
            null -> { log("Состояние неизвестно, запрашиваю..."); sendCommand(CMD_STATE) }
        }
        requestStateSoon()
    }

    // ---------- опрос состояния ----------

    private fun startStatePolling() {
        handler.removeCallbacks(statePoller)
        handler.post(statePoller)
    }

    private fun stopStatePolling() = handler.removeCallbacks(statePoller)

    /** Внеплановый запрос состояния вскоре после команды вкл/выкл. */
    private fun requestStateSoon() = handler.postDelayed({ sendCommand(CMD_STATE) }, 400L)

    // ---------- отправка ----------

    @SuppressLint("MissingPermission")
    private fun sendCommand(cmd: Byte) = writeFrame(cmd, null)

    @SuppressLint("MissingPermission")
    private fun writeFrame(cmd: Byte, data: ByteArray?) {
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null) {
            log("Нет соединения")
            return
        }
        val frame = buildFrame(cmd, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.value = frame
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
        log("-> ${frame.toHex()}")
    }

    private fun buildFrame(cmd: Byte, data: ByteArray?): ByteArray {
        val payload = data ?: ByteArray(0)
        val frame = ByteArray(4 + payload.size)
        frame[0] = 0x55.toByte()
        frame[1] = counter.toByte()
        frame[2] = cmd
        System.arraycopy(payload, 0, frame, 3, payload.size)
        frame[frame.size - 1] = 0xAA.toByte()
        counter = (counter + 1) % 101   // как в оригинале: 0..100
        return frame
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        stopStatePolling()
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
    }

    // ---------- UI ----------

    private fun setStatus(s: String) = runOnUiThread { binding.statusText.text = s }

    private fun setButtonsEnabled(enabled: Boolean) = runOnUiThread {
        binding.toggleButton.isEnabled = enabled
        if (!enabled) {
            isOn = null
            binding.toggleButton.text = "—"
            animateButtonColor(COLOR_UNKNOWN)
        }
    }

    private fun setStateIndicator(on: Boolean) = runOnUiThread {
        isOn = on
        binding.toggleButton.text = if (on) "Включена" else "Выключена"
        animateButtonColor(if (on) COLOR_ON else COLOR_OFF)
    }

    /** Плавный переход цвета кнопки от текущего к целевому. */
    private fun animateButtonColor(target: Int) {
        if (currentColor == target) return
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentColor, target).apply {
            duration = 300
            addUpdateListener {
                binding.toggleButton.backgroundTintList =
                    ColorStateList.valueOf(it.animatedValue as Int)
            }
            start()
        }
        currentColor = target
    }

    private fun log(msg: String) = runOnUiThread {
        val sv = binding.logScroll
        // Был ли пользователь уже внизу (с небольшим допуском) ДО добавления строки
        val child = sv.getChildAt(0)
        val tolerancePx = (24 * resources.displayMetrics.density).toInt()
        val atBottom = child == null ||
            (child.bottom - (sv.height + sv.scrollY)) <= tolerancePx

        binding.logText.append("$msg\n")

        // Автопрокрутка только если пользователь не листал вверх.
        // Используем scrollTo (не fullScroll!), чтобы НЕ забирать фокус у полей ввода.
        if (atBottom) sv.post { sv.smoothScrollTo(0, binding.logText.bottom) }
    }

    private fun copyLog() {
        val text = binding.logText.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "Лог пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SkyPlug log", text))
        Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }

    /**
     * Превращает строку-пароль в детерминированный 8-байтный ключ (SHA-256, первые 8 байт).
     * Пустая строка -> ключ по умолчанию (PAIR_TOKEN), чтобы текущее сопряжение продолжало работать.
     */
    private fun deriveKey(passphrase: String): ByteArray {
        if (passphrase.isEmpty()) return PAIR_TOKEN
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(passphrase.toByteArray(Charsets.UTF_8))
        return digest.copyOf(8)
    }
}

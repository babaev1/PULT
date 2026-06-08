// app/src/main/java/com/example/roki_pult/MainActivity.kt
package com.example.roki_pult

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.BluetoothLeScanner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.util.Log
import android.view.LayoutInflater
import android.media.AudioManager
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Method
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("ClickableViewAccessibility")
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val ROKI_TAG = "ROKI"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val SCAN_PERIOD: Long = 10000
    private val PRUNE_INTERVAL: Long = 2000
    private val DEVICE_TIMEOUT: Long = 12000
    private val JOYSTICK_SEND_INTERVAL: Long = 20
    private val RECONNECT_DELAY: Long = 2000

    private val COLOR_RED = Color.parseColor("#F44336")
    private val COLOR_GREEN = Color.parseColor("#4CAF50")
    private val COLOR_BLUE = Color.parseColor("#2196F3")
    private val COLOR_GRAY = Color.parseColor("#9E9E9E")

    private enum class UiState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        RECONNECTING,
        CONNECTED
    }

    private var currentState: UiState = UiState.DISCONNECTED
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var lastConnectedDevice: BluetoothDevice? = null
    private var vibrator: Vibrator? = null

    private lateinit var devicesRecyclerView: RecyclerView
    private lateinit var btnDisconnect: Button
    private lateinit var btnPairForget: Button
    private lateinit var statusTextView: TextView
    private lateinit var statusTextViewControl: TextView
    private lateinit var joystickViewLeft: JoystickView
    private lateinit var joystickViewRight: JoystickView
    private lateinit var scanProgressBar: ProgressBar

    private lateinit var pairingPanel: View
    private lateinit var controlPanel: View
    private lateinit var btnToControl: Button
    private lateinit var btnToPairing: Button
    private lateinit var btnL1: Button
    private lateinit var btnL2: Button
    private lateinit var btnR1: Button
    private lateinit var btnR2: Button
    private lateinit var btnF1: Button
    private lateinit var btnF2: Button
    private lateinit var btnF3: Button
    private lateinit var btnF4: Button
    private lateinit var btnF5: Button
    private lateinit var btnF6: Button
    private lateinit var btnF7: Button
    private lateinit var btnF8: Button
    private lateinit var btnF9: Button
    private lateinit var btnF10: Button

    private val foundDevices = ArrayList<BluetoothDevice>()
    private val lastSeenMap = ConcurrentHashMap<String, Long>()
    private lateinit var deviceAdapter: DeviceAdapter
    private var selectedPosition: Int = -1

    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var dataSendTimer: Timer? = null

    private var isLeftAtEdge = false
    private var isRightAtEdge = false

    @Volatile private var axisLeftX: Byte = 0
    @Volatile private var axisLeftY: Byte = 0
    @Volatile private var axisRightX: Byte = 0
    @Volatile private var axisRightY: Byte = 0

    private val messageOut = CsMessageOut()

    private val neededPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        Log.d(TAG, "Bond state changed for ${device?.name}: $bondState")
                    }
                    deviceAdapter.notifyDataSetChanged()
                    updatePairForgetButton()
                }
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        try {
                            val pin = "1234".toByteArray()
                            device?.setPin(pin)
                            // Прерываем дальнейшее распространение события, чтобы скрыть системное окно
                            abortBroadcast()
                            Log.d(TAG, "Auto-setting PIN 1234 and aborting broadcast for ${device?.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting PIN", e)
                        }
                    }
                }
            }
        }
    }

    private val pruneRunnable = object : Runnable {
        override fun run() {
            if (isScanning) {
                val now = System.currentTimeMillis()
                var changed = false
                val iterator = foundDevices.iterator()
                while (iterator.hasNext()) {
                    val device = iterator.next()
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        if (device.bondState != BluetoothDevice.BOND_BONDED) {
                            val lastSeen = lastSeenMap[device.address] ?: 0L
                            if (now - lastSeen > DEVICE_TIMEOUT) {
                                iterator.remove()
                                changed = true
                            }
                        }
                    }
                }
                if (changed) {
                    runOnUiThread { deviceAdapter.notifyDataSetChanged() }
                }
            }
            handler.postDelayed(this, PRUNE_INTERVAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Принудительное включение звука клавиш для приложения
        volumeControlStream = AudioManager.STREAM_MUSIC

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        devicesRecyclerView = findViewById(R.id.devicesRecyclerView)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnPairForget = findViewById(R.id.btnPairForget)
        statusTextView = findViewById(R.id.statusTextView)
        statusTextViewControl = findViewById(R.id.statusTextViewControl)
        joystickViewLeft = findViewById(R.id.joystickViewLeft)
        joystickViewRight = findViewById(R.id.joystickViewRight)
        scanProgressBar = findViewById(R.id.scanProgressBar)

        pairingPanel = findViewById(R.id.pairingPanel)
        controlPanel = findViewById(R.id.controlPanel)
        btnToControl = findViewById(R.id.btnToControl)
        btnToPairing = findViewById(R.id.btnToPairing)
        btnL1 = findViewById(R.id.btnL1)
        btnL2 = findViewById(R.id.btnL2)
        btnR1 = findViewById(R.id.btnR1)
        btnR2 = findViewById(R.id.btnR2)
        btnF1 = findViewById(R.id.btnF1)
        btnF2 = findViewById(R.id.btnF2)
        btnF3 = findViewById(R.id.btnF3)
        btnF4 = findViewById(R.id.btnF4)
        btnF5 = findViewById(R.id.btnF5)
        btnF6 = findViewById(R.id.btnF6)
        btnF7 = findViewById(R.id.btnF7)
        btnF8 = findViewById(R.id.btnF8)
        btnF9 = findViewById(R.id.btnF9)
        btnF10 = findViewById(R.id.btnF10)

        deviceAdapter = DeviceAdapter(foundDevices) { position ->
            onDeviceSelected(position)
        }
        devicesRecyclerView.layoutManager = LinearLayoutManager(this)
        devicesRecyclerView.adapter = deviceAdapter

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show()
        } else {
            // Добавляем уже сопряженные устройства в список при запуске
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                val pairedDevices = bluetoothAdapter?.bondedDevices
                pairedDevices?.forEach { device ->
                    if (device.name != null && device.name.contains(ROKI_TAG)) {
                        if (!foundDevices.any { it.address == device.address }) {
                            foundDevices.add(device)
                        }
                    }
                }
                deviceAdapter.notifyDataSetChanged()
            }
        }
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        setupUIListeners()
        setUiState(UiState.DISCONNECTED, statusText = "Initializing...")
        checkAndRequestPermissions()
        
        // Установка начальных цветов для кнопок переключения панелей
        setButtonFillColor(btnToControl, COLOR_GREEN)
        setButtonFillColor(btnToPairing, COLOR_BLUE)
        setButtonFillColor(btnL1, COLOR_GREEN)
        setButtonFillColor(btnL2, COLOR_GREEN)
        setButtonFillColor(btnR1, COLOR_GREEN)
        setButtonFillColor(btnR2, COLOR_GREEN)
        setButtonFillColor(btnF1, COLOR_GREEN)
        setButtonFillColor(btnF2, COLOR_GREEN)
        setButtonFillColor(btnF3, COLOR_GREEN)
        setButtonFillColor(btnF4, COLOR_GREEN)
        setButtonFillColor(btnF5, COLOR_GREEN)
        setButtonFillColor(btnF6, COLOR_GREEN)
        setButtonFillColor(btnF7, COLOR_GREEN)
        setButtonFillColor(btnF8, COLOR_GREEN)
        setButtonFillColor(btnF9, COLOR_GREEN)
        setButtonFillColor(btnF10, COLOR_GREEN)

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        registerReceiver(bluetoothReceiver, filter)
        handler.post(pruneRunnable)

        pairingPanel.visibility = View.VISIBLE
        controlPanel.visibility = View.GONE
        
        updatePairForgetButton()
        updateDisconnectButton()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun setUiState(state: UiState, deviceName: String? = null, statusText: String? = null) {
        currentState = state
        runOnUiThread {
            updateDisconnectButton()
            updateControlButtonsState()
            deviceAdapter.notifyDataSetChanged()
            when (state) {
                UiState.DISCONNECTED -> {
                    statusTextView.text = statusText ?: "Status: Disconnected"
                    statusTextViewControl.text = statusText ?: "Status: Disconnected"
                    scanProgressBar.visibility = View.GONE
                }
                UiState.SCANNING -> {
                    statusTextView.text = "Scanning..."
                    statusTextViewControl.text = "Scanning..."
                    scanProgressBar.visibility = View.VISIBLE
                }
                UiState.CONNECTING -> {
                    statusTextView.text = "Connecting to ${deviceName ?: "device"}..."
                    statusTextViewControl.text = "Connecting to ${deviceName ?: "device"}..."
                    scanProgressBar.visibility = View.VISIBLE
                }
                UiState.RECONNECTING -> {
                    statusTextView.text = "Reconnecting..."
                    statusTextViewControl.text = "Reconnecting..."
                    scanProgressBar.visibility = View.VISIBLE
                }
                UiState.CONNECTED -> {
                    statusTextView.text = "Connected: ${deviceName ?: "device"}"
                    statusTextViewControl.text = "Connected: ${deviceName ?: "device"}"
                    scanProgressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun updateDisconnectButton() {
        val layerDrawable = btnDisconnect.background as? LayerDrawable
        val fillDrawable = layerDrawable?.findDrawableByLayerId(R.id.button_fill) as? GradientDrawable
        if (currentState == UiState.CONNECTED) {
            fillDrawable?.setColor(COLOR_RED)
            btnDisconnect.isEnabled = true
        } else {
            fillDrawable?.setColor(COLOR_GRAY)
            btnDisconnect.isEnabled = false
        }
    }

    private fun updateControlButtonsState() {
        val isConnected = currentState == UiState.CONNECTED
        val color = if (isConnected) COLOR_GREEN else COLOR_GRAY
        val buttons = listOf(
            btnL1, btnL2, btnR1, btnR2,
            btnF1, btnF2, btnF3, btnF4, btnF5,
            btnF6, btnF7, btnF8, btnF9, btnF10
        )
        buttons.forEach { button ->
            button.isEnabled = isConnected
            setButtonFillColor(button, color)
        }
        // Также деактивируем джойстики для наглядности
        joystickViewLeft.isEnabled = isConnected
        joystickViewRight.isEnabled = isConnected
    }

    private fun updatePairForgetButton() {
        val layerDrawable = btnPairForget.background as? LayerDrawable
        val fillDrawable = layerDrawable?.findDrawableByLayerId(R.id.button_fill) as? GradientDrawable
        
        if (selectedPosition == -1 || selectedPosition >= foundDevices.size) {
            btnPairForget.text = "PAIR"
            fillDrawable?.setColor(COLOR_GRAY)
        } else {
            val device = foundDevices[selectedPosition]
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    btnPairForget.text = "FORGET"
                    fillDrawable?.setColor(COLOR_RED)
                } else {
                    btnPairForget.text = "PAIR"
                    fillDrawable?.setColor(COLOR_GREEN)
                }
            }
        }
    }

    private fun setButtonFillColor(button: Button, color: Int) {
        val layerDrawable = button.background as? LayerDrawable
        val fillDrawable = layerDrawable?.findDrawableByLayerId(R.id.button_fill) as? GradientDrawable
        fillDrawable?.setColor(color)
    }

    private fun onDeviceSelected(position: Int) {
        selectedPosition = position
        deviceAdapter.notifyDataSetChanged()
        updatePairForgetButton()

        if (position >= 0 && position < foundDevices.size) {
            val device = foundDevices[position]
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    if (currentState == UiState.CONNECTED && lastConnectedDevice?.address != device.address) {
                        disconnect()
                    }
                    if (currentState != UiState.CONNECTED && currentState != UiState.CONNECTING) {
                        connectToDevice(device)
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUIListeners() {
        btnToControl.setOnClickListener {
            it.isSoundEffectsEnabled = true
            it.playSoundEffect(SoundEffectConstants.CLICK)
            pairingPanel.visibility = View.GONE
            controlPanel.visibility = View.VISIBLE
            stopScanning()
        }

        btnToPairing.setOnClickListener {
            it.isSoundEffectsEnabled = true
            it.playSoundEffect(SoundEffectConstants.CLICK)
            controlPanel.visibility = View.GONE
            pairingPanel.visibility = View.VISIBLE
            deviceAdapter.notifyDataSetChanged()
            if (foundDevices.isEmpty()) {
                scanLeDevice()
            }
        }

        btnDisconnect.setOnClickListener {
            it.isSoundEffectsEnabled = true
            it.playSoundEffect(SoundEffectConstants.CLICK)
            disconnect()
        }

        btnPairForget.setOnClickListener {
            it.isSoundEffectsEnabled = true
            it.playSoundEffect(SoundEffectConstants.CLICK)
            if (selectedPosition != -1 && selectedPosition < foundDevices.size) {
                val device = foundDevices[selectedPosition]
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    if (device.bondState == BluetoothDevice.BOND_BONDED) {
                        unpairDevice(device)
                    } else {
                        device.createBond()
                    }
                }
            }
        }

        joystickViewLeft.setOnMoveListener(object : JoystickView.OnMoveListener {
            override fun onMove(x: Int, y: Int) {
                axisLeftX = x.toByte()
                axisLeftY = y.toByte()
                // Срабатывает, если джойстик прижат к любому краю
                val currentlyAtEdge = Math.abs(x) >= 126 || Math.abs(y) >= 126
                if (currentlyAtEdge != isLeftAtEdge) {
                    isLeftAtEdge = currentlyAtEdge
                    updateVibration()
                }
            }
        })

        joystickViewRight.setOnMoveListener(object : JoystickView.OnMoveListener {
            override fun onMove(x: Int, y: Int) {
                axisRightX = x.toByte()
                axisRightY = y.toByte()
                val currentlyAtEdge = Math.abs(x) >= 126 || Math.abs(y) >= 126
                if (currentlyAtEdge != isRightAtEdge) {
                    isRightAtEdge = currentlyAtEdge
                    updateVibration()
                }
            }
        })
    }

    private fun updateVibration() {
        val atEdge = isLeftAtEdge || isRightAtEdge
        if (atEdge) {
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(longArrayOf(0, 100), 0)
                    vibrator?.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 100), 0)
                }
            }
        } else {
            vibrator?.cancel()
        }
    }

    private fun unpairDevice(device: BluetoothDevice) {
        try {
            val method: Method = device.javaClass.getMethod("removeBond")
            method.invoke(device)
        } catch (e: Exception) {
            Log.e(TAG, "Error unpairing device", e)
        }
    }

    private fun startDataSendTimer() {
        stopDataSendTimer()
        dataSendTimer = Timer()
        dataSendTimer?.schedule(object : TimerTask() {
            override fun run() {
                sendJoystickData()
            }
        }, 0, JOYSTICK_SEND_INTERVAL)
    }

    private fun stopDataSendTimer() {
        dataSendTimer?.cancel()
        dataSendTimer = null
    }

    private fun sendJoystickData() {
        if (outputStream != null) {
            try {
                messageOut.hostBeginQuery('R')
                messageOut.addInt32(0)
                messageOut.addInt8(axisLeftX.toInt())
                messageOut.addInt8(axisLeftY.toInt())
                messageOut.addInt8(axisRightX.toInt())
                messageOut.addInt8(axisRightY.toInt())
                messageOut.hostEnd()
                val buffer = messageOut.getRawBuffer()
                val size = messageOut.getPayloadSize()
                outputStream?.write(buffer, 0, size)
            } catch (e: IOException) {
                Log.e(TAG, "Data send error", e)
                attemptReconnect()
            }
        }
    }

    private fun attemptReconnect() {
        stopDataSendTimer()
        lastConnectedDevice?.let { device ->
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            runOnUiThread {
                setUiState(UiState.RECONNECTING, device.name)
            }
            handler.postDelayed({
                connectToDevice(device)
            }, RECONNECT_DELAY)
        } ?: run {
            disconnect(statusOverride = "Connection lost")
        }
    }

    private fun isLocationServiceEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun scanLeDevice() {
        if (bluetoothAdapter == null) return
        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        }
        if (!checkPermissions() || !isLocationServiceEnabled()) return
        if (isScanning || currentState == UiState.CONNECTED || currentState == UiState.CONNECTING) return

        setUiState(UiState.SCANNING)
        isScanning = true
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothLeScanner?.startScan(leScanCallback)
        }

        handler.postDelayed({
            if (isScanning) {
                stopScanning()
                if (pairingPanel.visibility == View.VISIBLE && currentState != UiState.CONNECTED && currentState != UiState.CONNECTING) {
                    scanLeDevice()
                }
            }
        }, SCAN_PERIOD)
    }

    private fun stopScanning() {
        isScanning = false
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan", e)
            }
        }
        if (currentState == UiState.SCANNING) {
            setUiState(UiState.DISCONNECTED)
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device ->
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                val deviceName = device.name
                if (deviceName != null && deviceName.contains(ROKI_TAG)) {
                    lastSeenMap[device.address] = System.currentTimeMillis()
                    if (!foundDevices.any { it.address == device.address }) {
                        foundDevices.add(device)
                        runOnUiThread {
                            deviceAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            setUiState(UiState.DISCONNECTED, statusText = "Scan failed ($errorCode)")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        stopScanning()
        setUiState(UiState.CONNECTING, device.name)
        Thread {
            try {
                bluetoothSocket?.close()
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                runOnUiThread {
                    lastConnectedDevice = device
                    setUiState(UiState.CONNECTED, device.name)
                    startDataSendTimer()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed", e)
                disconnect(statusOverride = "Connection failed")
            }
        }.start()
    }

    private fun disconnect(statusOverride: String? = null) {
        stopDataSendTimer()
        stopScanning()
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing socket", e)
        }
        outputStream = null
        bluetoothSocket = null
        // Мы НЕ обнуляем lastConnectedDevice здесь, чтобы в списке (onBindViewHolder)
        // значок оставался зеленым сразу после нажатия Disconnect.
        // Он обнулится только при подключении к ДРУГОМУ устройству.

        runOnUiThread {
            setUiState(UiState.DISCONNECTED, statusText = statusOverride ?: "Disconnected")
            if (pairingPanel.visibility == View.VISIBLE) {
                scanLeDevice()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pruneRunnable)
        try { unregisterReceiver(bluetoothReceiver) } catch (e: Exception) {}
        disconnect()
    }

    private fun onPermissionsGranted() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetooth.launch(enableBtIntent)
        } else {
            // Обновляем список сопряженных устройств при получении разрешений или включении Bluetooth
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                val pairedDevices = adapter.bondedDevices
                pairedDevices?.forEach { device ->
                    if (device.name != null && device.name.contains(ROKI_TAG)) {
                        if (!foundDevices.any { it.address == device.address }) {
                            foundDevices.add(device)
                        }
                    }
                }
                deviceAdapter.notifyDataSetChanged()
            }
            if (pairingPanel.visibility == View.VISIBLE) {
                scanLeDevice()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = ArrayList<String>()
        for (permission in neededPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1)
        } else {
            onPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                onPermissionsGranted()
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return neededPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private val requestBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            if (pairingPanel.visibility == View.VISIBLE) {
                scanLeDevice()
            }
        }
    }

    inner class DeviceAdapter(
        private val devices: List<BluetoothDevice>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val wifiIcon: ImageView = view.findViewById(R.id.wifiIcon)
            val indicator: View = view.findViewById(R.id.statusIndicator)
            val name: TextView = view.findViewById(R.id.deviceName)
            init {
                view.setOnClickListener { 
                    it.isSoundEffectsEnabled = true
                    it.playSoundEffect(SoundEffectConstants.CLICK)
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onClick(pos) 
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.device_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            // Установка высоты строки 16% от высоты всей панели (Pairing Screen)
            val parentPanelHeight = pairingPanel.height
            if (parentPanelHeight > 0) {
                val params = holder.itemView.layoutParams
                params.height = (parentPanelHeight * 0.16).toInt()
                holder.itemView.layoutParams = params
            }

            val device = devices[position]
            if (ActivityCompat.checkSelfPermission(holder.itemView.context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                holder.name.text = device.name ?: "Unknown"
                
                // Обновление значка WiFi в зависимости от того, видно ли устройство в эфире
                val lastSeen = lastSeenMap[device.address] ?: 0L
                // Устройство считается видимым, если оно было в эфире недавно ИЛИ если оно было последним подключенным
                // Мы НЕ сбрасываем lastConnectedDevice сразу при дисконнекте для этого условия
                val isVisible = (System.currentTimeMillis() - lastSeen) < DEVICE_TIMEOUT || 
                                (lastConnectedDevice?.address == device.address)

                if (isVisible) {
                    holder.wifiIcon.setImageResource(R.drawable.android_wifi_3_bar_24)
                } else {
                    holder.wifiIcon.setImageResource(R.drawable.android_wifi_3_bar_off_24)
                }
                
                val indicatorDrawable = holder.indicator.background as? GradientDrawable
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    if (currentState == UiState.CONNECTED && lastConnectedDevice?.address == device.address) {
                        indicatorDrawable?.setColor(COLOR_GREEN)
                    } else {
                        indicatorDrawable?.setColor(COLOR_RED)
                    }
                } else {
                    indicatorDrawable?.setColor(Color.TRANSPARENT)
                }
            }

            val background = ContextCompat.getDrawable(holder.itemView.context, R.drawable.item_background_selector) as? LayerDrawable
            val selectionLayer = background?.findDrawableByLayerId(R.id.selection_layer) as? GradientDrawable
            
            if (selectedPosition == position) {
                selectionLayer?.setColor(Color.GRAY)
            } else {
                selectionLayer?.setColor(Color.TRANSPARENT)
            }
            holder.itemView.background = background
        }

        override fun getItemCount() = devices.size
    }
}
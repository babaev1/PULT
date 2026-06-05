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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.OutputStream
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

@SuppressLint("ClickableViewAccessibility")
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val ROKI_TAG = "ROKI"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val SCAN_PERIOD: Long = 10000
    private val JOYSTICK_SEND_INTERVAL: Long = 20
    private val RECONNECT_DELAY: Long = 2000

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

    private lateinit var devicesSpinner: Spinner
    private lateinit var scanButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var joystickViewLeft: JoystickView
    private lateinit var joystickViewRight: JoystickView
    private lateinit var scanProgressBar: ProgressBar

    private val foundDevices = ArrayList<BluetoothDevice>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var dataSendTimer: Timer? = null

    @Volatile private var axisLeftX: Byte = 0
    @Volatile private var axisLeftY: Byte = 0
    @Volatile private var axisRightX: Byte = 0
    @Volatile private var axisRightY: Byte = 0

    private val messageOut = CsMessageOut()
    private var isUserSpinnerInteraction = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        devicesSpinner = findViewById(R.id.devicesSpinner)
        scanButton = findViewById(R.id.scanButton)
        statusTextView = findViewById(R.id.statusTextView)
        joystickViewLeft = findViewById(R.id.joystickViewLeft)
        joystickViewRight = findViewById(R.id.joystickViewRight)
        scanProgressBar = findViewById(R.id.scanProgressBar)

        spinnerAdapter = ArrayAdapter(this, R.layout.custom_spinner_item, ArrayList<String>())
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        devicesSpinner.adapter = spinnerAdapter

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_LONG).show()
        }
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        setupUIListeners()
        checkAndRequestPermissions()
        setUiState(UiState.DISCONNECTED, statusText = "Press Scan to start")
    }

    private fun setUiState(state: UiState, deviceName: String? = null, statusText: String? = null) {
        currentState = state
        when (state) {
            UiState.DISCONNECTED -> {
                this.statusTextView.text = statusText ?: "Status: Disconnected"
                this.statusTextView.visibility = if (statusText != null) View.VISIBLE else View.INVISIBLE
                devicesSpinner.visibility = if (spinnerAdapter.count > 0 && spinnerAdapter.getItem(0) != "No devices found") View.VISIBLE else View.GONE
                devicesSpinner.isEnabled = spinnerAdapter.count > 0
                scanProgressBar.visibility = View.GONE
                scanButton.visibility = View.VISIBLE
                scanButton.text = "Scan"
                scanButton.isEnabled = true
                joystickViewLeft.alpha = 0.3f
                joystickViewRight.alpha = 0.3f
                joystickViewLeft.isEnabled = false
                joystickViewRight.isEnabled = false
            }
            UiState.SCANNING -> {
                statusTextView.text = "Scanning for devices..."
                statusTextView.visibility = View.VISIBLE
                scanProgressBar.visibility = View.VISIBLE
                scanButton.visibility = View.INVISIBLE
                devicesSpinner.visibility = View.GONE
                joystickViewLeft.alpha = 0.3f
                joystickViewRight.alpha = 0.3f
                joystickViewLeft.isEnabled = false
                joystickViewRight.isEnabled = false
            }
            UiState.CONNECTING -> {
                statusTextView.text = "Connecting to ${deviceName ?: "device"}..."
                statusTextView.visibility = View.VISIBLE
                scanProgressBar.visibility = View.VISIBLE
                scanButton.visibility = View.INVISIBLE
                devicesSpinner.visibility = View.VISIBLE
                devicesSpinner.isEnabled = false
            }
            UiState.RECONNECTING -> {
                statusTextView.text = "Reconnecting to ${deviceName ?: "device"}..."
                statusTextView.visibility = View.VISIBLE
                scanProgressBar.visibility = View.VISIBLE
                scanButton.visibility = View.INVISIBLE
                devicesSpinner.visibility = View.VISIBLE
                devicesSpinner.isEnabled = false
            }
            UiState.CONNECTED -> {
                statusTextView.text = "Connected to ${deviceName ?: "device"}"
                statusTextView.visibility = View.VISIBLE
                scanProgressBar.visibility = View.GONE
                scanButton.visibility = View.VISIBLE
                scanButton.text = "Disconnect"
                scanButton.isEnabled = true
                devicesSpinner.visibility = View.VISIBLE
                devicesSpinner.isEnabled = false
                joystickViewLeft.alpha = 1.0f
                joystickViewRight.alpha = 1.0f
                joystickViewLeft.isEnabled = true
                joystickViewRight.isEnabled = true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUIListeners() {
        scanButton.setOnClickListener {
            if (currentState == UiState.CONNECTED) {
                disconnect()
            } else {
                scanLeDevice()
            }
        }

        devicesSpinner.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                isUserSpinnerInteraction = true
            }
            false
        }
        devicesSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!isUserSpinnerInteraction) return
                if (foundDevices.isNotEmpty() && position > 0 && position <= foundDevices.size) {
                    connectToDevice(foundDevices[position - 1])
                }
                isUserSpinnerInteraction = false
            }
            override fun onNothingSelected(parent: AdapterView<*>) {
                isUserSpinnerInteraction = false
            }
        }

        joystickViewLeft.setOnMoveListener(object : JoystickView.OnMoveListener {
            override fun onMove(x: Int, y: Int) {
                axisLeftX = x.toByte()
                axisLeftY = y.toByte()
            }
        })

        joystickViewRight.setOnMoveListener(object : JoystickView.OnMoveListener {
            override fun onMove(x: Int, y: Int) {
                axisRightX = x.toByte()
                axisRightY = y.toByte()
            }
        })
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
                Log.e(TAG, "Ошибка отправки данных: соединение потеряно", e)
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
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        }

        if (!checkPermissions() || !isLocationServiceEnabled()) {
            Toast.makeText(this, "Проверьте разрешения и включите геолокацию", Toast.LENGTH_LONG).show()
            checkAndRequestPermissions()
            if (!isLocationServiceEnabled()) {
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            return
        }

        if (!isScanning) {
            setUiState(UiState.SCANNING)
            handler.postDelayed({
                isScanning = false
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeScanner?.stopScan(leScanCallback)
                }
                if (foundDevices.isEmpty()) {
                    spinnerAdapter.clear()
                    setUiState(UiState.DISCONNECTED, statusText = "No devices found")
                } else {
                    setUiState(UiState.DISCONNECTED, statusText = "Scan finished. Select a device.")
                }
            }, SCAN_PERIOD)

            isScanning = true
            foundDevices.clear()
            spinnerAdapter.clear()
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothLeScanner?.startScan(leScanCallback)
            }
        } else {
            isScanning = false
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothLeScanner?.stopScan(leScanCallback)
            }
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
                    Log.d(TAG, "Found ROKI device: $deviceName, address: ${device.address}")
                    if (!foundDevices.contains(device)) {
                        if (foundDevices.isEmpty()) {
                            spinnerAdapter.clear()
                            spinnerAdapter.add("Select device...")
                        }
                        foundDevices.add(device)
                        runOnUiThread {
                            spinnerAdapter.add("$deviceName - ${device.address}")
                            spinnerAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Ошибка сканирования Bluetooth LE. Код ошибки: $errorCode")
            setUiState(UiState.DISCONNECTED, statusText = "Scan failed (Code: $errorCode)")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        setUiState(UiState.CONNECTING, device.name)
        Thread {
            if (isScanning) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeScanner?.stopScan(leScanCallback)
                }
            }
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
                Log.e(TAG, "Не удалось подключиться к устройству", e)
                disconnect(statusOverride = "Connection to ${device.name} failed.")
            }
        }.start()
    }

    private fun disconnect(statusOverride: String? = null) {
        stopDataSendTimer()
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Не удалось закрыть сокет", e)
        }
        outputStream = null
        bluetoothSocket = null
        lastConnectedDevice = null
        runOnUiThread {
            setUiState(UiState.DISCONNECTED, statusText = statusOverride ?: "Disconnected.")
            spinnerAdapter.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            if (isScanning) {
                bluetoothLeScanner?.stopScan(leScanCallback)
            }
        }
        disconnect()
    }

    private fun onPermissionsGranted() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetooth.launch(enableBtIntent)
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
                Toast.makeText(this, "Необходимы разрешения для работы с Bluetooth", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return neededPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private val requestBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(this, "Для работы приложения требуется включенный Bluetooth", Toast.LENGTH_LONG).show()
        }
    }
}
package com.rkqnt.android_simple_ota.ota

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.rkqnt.android_simple_ota.MainActivity
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import timber.log.Timber
import java.io.File
import java.io.IOException

class OtaService : Service(), DataListener {

    companion object{
        var bleManager: BleManager<LeManagerCallbacks>? = null
        var deviceName = ""

        var connected = false
        var serviceRunning = false

        var parts = 0
        var fastMode = false
    }

    private lateinit var context: Context
    private var isReconnect = false
    private var startID = 0


    override fun onCreate() {
        super.onCreate()

        isReconnect = false
        context = this

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val leDevice = bluetoothManager.adapter.getRemoteDevice(MainActivity.deviceAddress)

        bleManager = LEManager(this)
        (bleManager as LEManager).setGattCallbacks(bleManagerCallback)
        (bleManager as LEManager).connect(leDevice).enqueue()

        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        DataReceiver.bindListener(this)
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.w("onStartCommand {intent=${intent == null},flags=$flags,startId=$startId}")

        if (intent == null || this.startID != 0) {
            //service restarted
            Timber.w("onStartCommand - already running")
        } else {
            //started by intent or pending intent
            this.startID = startId
            //val notification = notify(getString(R.string.scan), false,  SERVICE_ID)
            //startForeground(SERVICE_ID, notification)

            if (intent.hasExtra("origin")){
                Timber.w("Service started on device boot")
            } else {
                connected = false
                ConnectionReceiver().notifyStatus(false)
            }
        }
        serviceRunning = true

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private val bleManagerCallback: LeManagerCallbacks = object : LeManagerCallbacks() {

        @RequiresApi(Build.VERSION_CODES.P)
        override fun onDeviceConnected(device: BluetoothDevice) {
            super.onDeviceConnected(device)
            Timber.d("onDeviceConnected ${device.name}")
            deviceName = device.name
            ConnectionReceiver().notifyStatus(true)

        }

        override fun onDeviceReady(device: BluetoothDevice) {
            super.onDeviceReady(device)
            Timber.d("FG - Device ready ${device.name}")
            connected = true

            deviceName = device.name
        }

        override fun onDeviceConnecting(device: BluetoothDevice) {
            super.onDeviceConnecting(device)
            connected = false
            Timber.d("Connecting to ${if (device.name.isNullOrEmpty()) "device" else device.name}")
        }

        override fun onDeviceDisconnecting(device: BluetoothDevice) {
            super.onDeviceDisconnecting(device)
            connected = false
            Timber.d("Disconnecting from ${device.name}")
            ConnectionReceiver().notifyStatus(false)
        }

        @RequiresApi(Build.VERSION_CODES.P)
        override fun onDeviceDisconnected(device: BluetoothDevice) {
            super.onDeviceDisconnected(device)
            connected = false
            Timber.d("Disconnected from ${device.name}")
            ConnectionReceiver().notifyStatus(false)
        }

        @RequiresApi(Build.VERSION_CODES.P)
        override fun onLinkLossOccurred(device: BluetoothDevice) {
            super.onLinkLossOccurred(device)
            connected = false
            Timber.d("Lost link to ${device.name}")
            ConnectionReceiver().notifyStatus(false)
        }

        override fun onError(device: BluetoothDevice, message: String, errorCode: Int) {
            super.onError(device, message, errorCode)
            Timber.e("Error: $errorCode, Device:${device.name}, Message: $message")
            connected = false

            stopSelf(startID)
        }
    }

    fun sendData(data: ByteArray): Boolean{
        return if (bleManager != null) {
            (bleManager as LEManager).writeBytes(fastMode, data)
        } else {
            false
        }
    }

    private fun transmitData(data: ByteArray, progress: Int, context: Context): Boolean{
        return if (bleManager != null) {
            (bleManager as LEManager).transmitData(fastMode, data, progress, context, this@OtaService::onProgress)
        } else {
            false
        }
    }

    override fun onDestroy() {
        serviceRunning = false
        connected = false
        isReconnect = false
        startID = 0
        bleManager?.close()


        unregisterReceiver(bluetoothReceiver)
        super.onDestroy()
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action?.equals(BluetoothAdapter.ACTION_STATE_CHANGED) == true) {
                Timber.d("Bluetooth adapter changed in receiver")
                Timber.d("BT adapter state: ${intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)}")
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        val remoteMacAddress = MainActivity.deviceAddress
                        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                        val leDevice = bluetoothManager.adapter.getRemoteDevice(remoteMacAddress)

                        bleManager = LEManager(context)
                        bleManager?.setGattCallbacks(bleManagerCallback)
                        bleManager?.connect(leDevice)?.enqueue()

                        Timber.d("Bluetooth STATE ON")
                    }
                    BluetoothAdapter.STATE_TURNING_OFF -> {
                        bleManager?.disconnect()
                        bleManager?.close()
                        Timber.d("Bluetooth TURNING OFF")
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onDataReceived(data: Data) {

        if (data.getByte(0) == (0xF1).toByte()) {
            val next = ((data.getByte(1)!!.toPInt()) * 256) + (data.getByte(2)!!.toPInt())
            sendData(context, next)
        }

        if (data.getByte(0) == (0xAA).toByte()){
            fastMode = data.getByte(1) == (0x01).toByte()
            Toast.makeText(context, "Fastmode: $fastMode", Toast.LENGTH_SHORT).show()
            if (fastMode) {
                uploadData(context)
            } else {
                sendData(context, 0)
            }
        }

        if (data.getByte(0) == (0xF2).toByte()) {
            Timber.w("Transfer complete")
            Toast.makeText(context, "Transfer Complete", Toast.LENGTH_SHORT).show()
            //notifyProgress("Finishing up", 100, context)
            ProgressReceiver().getProgress(100, "Transfer Complete")
            Handler().postDelayed({
                //sendData(byteArrayOfInts(0xFE)) // send restart command
                //cancelNotification(SERVICE_ID2, context)
            }, 2000) // TODO - new fix
        }

        if (data.getByte(0) == (0x0F).toByte()){
            val textArray = ByteArray(data.size()-1)
            for (x in 1 until data.size()){
                textArray[x-1] = data.getByte(x)!!
            }
            Timber.e(String(textArray))
            ProgressReceiver().getProgress(101, String(textArray))
        }

        //MainActivity().onDataReceived(data)
    }

    @Throws(IOException::class)
    fun sendData(context: Context, pos: Int) {
        val dir = File(context.cacheDir, "data")
        val data = File(dir, "data$pos.bin").readBytes()
        val s = MainActivity.mtu
        val total = data.size / s

        for (x in 0 until total) {
            val arr = ByteArray(s + 2)
            arr[0] = (0xFB).toByte()
            arr[1] = x.toByte()
            for (y in 0 until s) {
                arr[y + 2] = data[(x * s) + y]
            }
            sendData(arr)
        }

        if (data.size % s != 0) {
            val arr = ByteArray((data.size % s) + 2)
            arr[0] = (0xFB).toByte()
            arr[1] = total.toByte()
            for (y in 0 until data.size % s) {
                arr[y + 2] = data[(total * s) + y]
            }
            sendData(arr)
        }

        val update = byteArrayOfInts(0xFC, data.size / 256, data.size % 256, pos / 256, pos % 256)
        val cur = ((pos.toFloat() / parts) * 100).toInt()
        transmitData(update, cur, context)
    }

    private fun onProgress(progress: Int, context: Context) {
        ProgressReceiver().getProgress(progress, "Sending Data")
    }

    private fun uploadData(context: Context){
        var current = 0
        val thread = Thread {
            while (current < parts){
                sendData(context, current)
                current++
            }
        }
        thread.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
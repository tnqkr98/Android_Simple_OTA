package com.rkqnt.android_simple_ota

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.rkqnt.android_simple_ota.btsearch.BtDevice
import com.rkqnt.android_simple_ota.btsearch.BtListAdapter
import com.rkqnt.android_simple_ota.databinding.ActivityMainBinding
import com.rkqnt.android_simple_ota.ota.ConnectionListener
import com.rkqnt.android_simple_ota.ota.ConnectionReceiver
import timber.log.Timber

class MainActivity : AppCompatActivity() , ConnectionListener {
    lateinit var binding: ActivityMainBinding

    private var deviceList = ArrayList<BtDevice>()
    private var deviceAdapter = BtListAdapter(deviceList, deviceAddress,this@MainActivity::selectedDevice)
    private var mScanning: Boolean = false
    private lateinit var alertDialog: AlertDialog

    private val bluetoothAdapter: BluetoothAdapter by lazy(LazyThreadSafetyMode.NONE) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    companion object {
        private const val FINE_LOCATION_PERMISSION_REQUEST= 1001
        const val BACKGROUND_LOCATION = 67

        lateinit var btAdapter: BluetoothAdapter

        var showNotif = false

        var mtu = 500
        var deviceAddress = ""
    }

    // UI 뷰 초기화
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ConnectionReceiver.bindListener(this)
        btAdapter = BluetoothAdapter.getDefaultAdapter()

        binding.recyclerview.layoutManager = LinearLayoutManager(this)
        var div = DividerItemDecoration(binding.recyclerview.context,LinearLayoutManager.VERTICAL)
        binding.recyclerview.apply {
            addItemDecoration(div)
            isNestedScrollingEnabled = false
            itemAnimator?.changeDuration = 0
            adapter = deviceAdapter
        }

        binding.btnBleSearch.setOnClickListener {
            //stopService(Intent(this, FG::class.java))

            if (!checkLocation()){
                requestLocation()
            } else {
                if (!checkFINE()){
                    requestBackground()
                } else {
                    if (bluetoothAdapter.isEnabled) {
                        scanLeDevice(true) //make sure scan function won't be called several times
                    }
                }
            }
        }
    }

    // 위치 권한 확인
    private fun checkLocation(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return true
    }

    // 위치 권한 요청
    private fun requestLocation(){
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            FINE_LOCATION_PERMISSION_REQUEST
        )
    }

    // Fine 위치 권한 확인
    private fun checkFINE(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ActivityCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return true
    }

    // Fine 위치 권한 요청
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestBackground(){
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            BACKGROUND_LOCATION
        )
    }

    override fun onResume() {
        super.onResume()
        showNotif = false
    }

    override fun onPause() {
        super.onPause()
        showNotif = true
    }

    // 어댑터로부터 데이터 받을 콜백 함수
    private fun selectedDevice(device: BtDevice){
        scanLeDevice(false)
        deviceAddress = device.address

        alertDialog.dismiss()
        if (!isBonded(device)){
            createBond(device)
        } else {
            Timber.e("Already bonded")
        }
        Handler().postDelayed({ startService(Intent(this, FG::class.java)) }, 5000)

    }

    // bt 디바이스 스캔
    @SuppressLint("MissingPermission")
    private fun scanLeDevice(enable: Boolean) {
        when (enable) {
            true -> {
                // Stops scanning after a pre-defined scan period.
                Handler().postDelayed({
                    mScanning = false
                    //progress.isIndeterminate = false
                    //button.visibility = View.VISIBLE
                    bluetoothAdapter.bluetoothLeScanner?.stopScan(mLeScanCallback)
                }, 10000)
                mScanning = true
                //progress.isIndeterminate = true
                //button.visibility = View.GONE
                val filter =
                    ScanFilter.Builder().setServiceUuid(ParcelUuid(LEManager.OTA_SERVICE_UUID))
                        .build()
                val filters = mutableListOf<ScanFilter>(filter)
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                    bluetoothAdapter.bluetoothLeScanner?.startScan(
                        mLeScanCallback
                    )
                } else {
                    bluetoothAdapter.bluetoothLeScanner?.startScan(
                        filters,
                        settings,
                        mLeScanCallback
                    )
                }
            }
            else -> {
                mScanning = false
                //progress.isIndeterminate = false
                //button.visibility = View.VISIBLE
                bluetoothAdapter.bluetoothLeScanner?.stopScan(mLeScanCallback)
            }
        }

    }

    // 디바이스 발견 시, 콜백
    private var mLeScanCallback = object : ScanCallback(){
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            Timber.e("Callback: $callbackType, Result ${result?.device?.name}")
            val me: BtDevice? = deviceList.singleOrNull {
                it.address == result?.device?.address
            }
            if (me == null && result?.device?.name != null){
                deviceList.add(BtDevice(result.device.name, result.device.address, false))
            }
            deviceAdapter.update(deviceList, deviceAddress)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult?>?) {
            super.onBatchScanResults(results)
            Timber.e("Scan results: $results")
            for (result in results!!){
                val me: BtDevice? = deviceList.singleOrNull {
                    it.address == result?.device?.address
                }
                if (me == null && result?.device?.name != null){
                    deviceList.add(BtDevice(result.device?.name!!, result.device.address, false))
                }
            }
            deviceAdapter.update(deviceList, deviceAddress)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Timber.e("Scan Fail: error $errorCode")
        }

    }


    override fun onConnectionChanged(state: Boolean) {
        TODO("Not yet implemented")
    }
}
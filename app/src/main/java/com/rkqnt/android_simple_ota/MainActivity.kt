package com.rkqnt.android_simple_ota

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.rkqnt.android_simple_ota.btsearch.BtDevice
import com.rkqnt.android_simple_ota.btsearch.BtListAdapter
import com.rkqnt.android_simple_ota.databinding.ActivityMainBinding
import com.rkqnt.android_simple_ota.ota.*
import timber.log.Timber


class MainActivity : AppCompatActivity() , ConnectionListener, ProgressListener {
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
        const val STORAGE = 20
        const val BACKGROUND_LOCATION = 67

        lateinit var btAdapter: BluetoothAdapter

        var mtu = 500
        var deviceAddress = ""

        var start = 0L
        var startOta = 0L
        var timeTr = 0L
        var timeOta = 0L
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
            stopService(Intent(this, OtaService::class.java))

            //if (!checkLocation()){
            //    Log.d("ddddddd","시작?1")
            //    requestLocation()
            //} else {
                //if (!checkFINE()){
                //    Log.d("ddddddd","시작?2")
                 //   requestBackground()
                //} else {
                    if (bluetoothAdapter.isEnabled) {
                        Log.d("ddddddd","시작?3")
                        scanLeDevice(true) //make sure scan function won't be called several times
                    }
                //}
            //}
        }

        requestBlePermissions(this,0)
    }

    private val BLE_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    @RequiresApi(api = Build.VERSION_CODES.S)
    private val ANDROID_12_BLE_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private fun requestBlePermissions(activity: Activity?, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ActivityCompat.requestPermissions(
            activity!!,
            ANDROID_12_BLE_PERMISSIONS,
            requestCode
        ) else ActivityCompat.requestPermissions(
            activity!!, BLE_PERMISSIONS, requestCode
        )
    }

    override fun onProgress(progress: Int, text: String) {
        runOnUiThread {

            binding.progressUpload.isIndeterminate = false
            binding.textProgress.text = text
            binding.progressUpload.progress = progress
            binding.percentProgress.text = "$progress%"
            if (progress == 100) {
                binding.btnOta.visibility = View.VISIBLE
                binding.progressUpload.isIndeterminate= true
                timeTr = System.currentTimeMillis() - start
                binding.textProgress.text = "Transfer complete in ${timeString(timeTr)}\nInstalling..."
            }
            if (progress == 101){
                timeOta = System.currentTimeMillis() - startOta
                binding.btnOta.visibility = View.VISIBLE
                binding.percentProgress.text = ""
                binding.textProgress.text = text + "\nFile transfer time: ${timeString(timeTr)}\nTotal OTA time: ${timeString(timeOta)}"
            }
        }
    }

    /**  이하 스캔 및 연결 **/

    @SuppressLint("MissingPermission")
    private fun createBond(btDev: BtDevice){
        bluetoothAdapter.getRemoteDevice(btDev.address).createBond()
    }

    @SuppressLint("MissingPermission")
    private fun isBonded(btDev: BtDevice): Boolean{
        for (dev in bluetoothAdapter.bondedDevices){
            if (dev.address == btDev.address){
                return true
            }
        }
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            FINE_LOCATION_PERMISSION_REQUEST -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    scanLeDevice(true)
                } else {
                    //tvTestNote.text= getString(R.string.allow_location_detection)
                }
                return
            }
            STORAGE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    var chooseFile = Intent(Intent.ACTION_GET_CONTENT)
                    chooseFile.type = "*/*"
                    chooseFile = Intent.createChooser(chooseFile, "Choose a file")
                    startActivityForResult(chooseFile, 20)
                } else {
                    //tvTestNote.text= getString(R.string.allow_location_detection)
                }
                return
            }
        }
    }

    // 위치 권한 확인
    /*private fun checkLocation(): Boolean {
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
        Log.d("dddd","위치요청")
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
    }*/

    // 어댑터로부터 데이터 받을 콜백 함수 (사용자가 선택한 디바이스 정보 콜백)
    private fun selectedDevice(device: BtDevice){
        scanLeDevice(false)
        deviceAddress = device.address

        //alertDialog.dismiss()
        if (!isBonded(device)){
            createBond(device)
        } else {
            Timber.e("Already bonded")
        }
        binding.progressBar.visibility = View.VISIBLE
        Handler().postDelayed({ startService(Intent(this, OtaService::class.java)) }, 5000)

    }

    // bt 디바이스 스캔
    @SuppressLint("MissingPermission")
    private fun scanLeDevice(enable: Boolean) {
        when (enable) {
            true -> {
                Handler().postDelayed({
                    mScanning = false
                    binding.progressBar.visibility = View.INVISIBLE
                    bluetoothAdapter.bluetoothLeScanner?.stopScan(mLeScanCallback)
                }, 10000)
                mScanning = true
                binding.progressBar.visibility = View.VISIBLE

                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(LEManager.OTA_SERVICE_UUID)).build()
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
                binding.progressBar.visibility = View.INVISIBLE
                bluetoothAdapter.bluetoothLeScanner?.stopScan(mLeScanCallback)
            }
        }

    }

    // 디바이스 발견 시, 콜백
    private var mLeScanCallback = object : ScanCallback(){
        @SuppressLint("MissingPermission")
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

        @SuppressLint("MissingPermission")
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
        runOnUiThread {
            OtaService.connected = state
            //setIcon(FG.connected)
            if (OtaService.connected) {
                binding.progressBar.visibility = View.INVISIBLE
                binding.txtBtname.text = "연결된 BT : "+OtaService.deviceName
                binding.textProgress.text = ""
                binding.progressUpload.progress = 0
                binding.percentProgress.text = ""
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, OtaService::class.java))
    }
}
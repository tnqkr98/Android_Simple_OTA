package com.rkqnt.android_simple_ota

import RealPathUtil
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.rkqnt.android_simple_ota.btsearch.BtDevice
import com.rkqnt.android_simple_ota.btsearch.BtListAdapter
import com.rkqnt.android_simple_ota.databinding.ActivityMainBinding
import com.rkqnt.android_simple_ota.ota.*
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException


class MainActivity : AppCompatActivity() , ConnectionListener, ProgressListener {
    lateinit var binding: ActivityMainBinding

    private var deviceList = ArrayList<BtDevice>()
    private var deviceAdapter = BtListAdapter(deviceList, deviceAddress,this@MainActivity::selectedDevice)
    private var mScanning: Boolean = false

    private val bluetoothAdapter: BluetoothAdapter by lazy(LazyThreadSafetyMode.NONE) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    companion object {
        private const val FINE_LOCATION_PERMISSION_REQUEST= 1001
        const val STORAGE = 20
        const val FILE_PICK = 56
        var PART = 16384

        const val UPDATE_FILE = "update.bin"
        const val FIRMWARE_FILE = "firmware.bin"

        lateinit var btAdapter: BluetoothAdapter

        var mtu = 500
        var deviceAddress = ""

        var start = 0L
        var startOta = 0L
        var timeTr = 0L
        var timeOta = 0L
    }

    private var downloadId: Long = -1L
    private lateinit var downloadPath: String
    private lateinit var downloadManager: DownloadManager

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.action)) {
                if (downloadId == id) {
                    val query: DownloadManager.Query = DownloadManager.Query()
                    query.setFilterById(id)
                    var cursor = downloadManager.query(query)
                    if (!cursor.moveToFirst()) {
                        return
                    }

                    var columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    var status = cursor.getInt(columnIndex)
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        saveFile(File(downloadPath), null)      // 파일 저장
                        binding.txtBin2.text = "파일 다운로드 상태 : 완료"
                        Toast.makeText(context, "Download succeeded", Toast.LENGTH_SHORT).show()
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (DownloadManager.ACTION_NOTIFICATION_CLICKED.equals(intent.action)) {
                Toast.makeText(context, "Notification clicked", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // UI 뷰 초기화
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val intentFilter = IntentFilter()
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        intentFilter.addAction(DownloadManager.ACTION_NOTIFICATION_CLICKED)
        registerReceiver(onDownloadComplete, intentFilter)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        ProgressReceiver.bindListener(this)
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

        // BLE 탐색버튼
        binding.btnBleSearch.setOnClickListener {
            stopService(Intent(this, OtaService::class.java))

            if (bluetoothAdapter.isEnabled) {
                scanLeDevice(true)
            }
        }

        // 파일 탐색 버튼
        binding.btnFileSearch.setOnClickListener {
            if (checkExternal()) {
                var chooseFile = Intent(Intent.ACTION_GET_CONTENT)
                chooseFile.type = "*/*"
                chooseFile = Intent.createChooser(chooseFile, "Choose a file")
                startActivityForResult(chooseFile, FILE_PICK)
            } else {
                requestExternal()
            }
        }

        binding.btnFileDown.setOnClickListener {
            downloadFile()
        }

        // 파일 전송 버튼
        binding.btnOta.setOnClickListener {
            startOta = System.currentTimeMillis()
            clearData()

            if(binding.editMtu.text.isNotEmpty()){
                mtu = binding.editMtu.text.toString().toInt()
            }else Toast.makeText(applicationContext,"MTU 값을 입력하시오",Toast.LENGTH_SHORT)

            if(binding.editPart.text.isNotEmpty()){
                PART = binding.editPart.text.toString().toInt()
            }else Toast.makeText(applicationContext,"PART 값을 입력하시오",Toast.LENGTH_SHORT)


            val parts = generate()          // 몇개의 bin 파일로 분할 되었는지
            OtaService.parts = parts
            if (OtaService().sendData(byteArrayOfInts(0xFD))) {
                Toast.makeText(this, "Uploading file", Toast.LENGTH_SHORT).show()

                // TODO - new fix (이하 두줄)
                val len = File(this.cacheDir, UPDATE_FILE).length()//
                OtaService().sendData(byteArrayOfInts(
                    0xFE,
                    ((len shr 24) and 0xFF).toInt(),
                    ((len shr 16) and 0xFF).toInt(),
                    ((len shr 8) and 0xFF).toInt(),
                    ((len) and 0xFF).toInt()))

                OtaService().sendData(
                    byteArrayOfInts(
                        0xFF,
                        parts / 256,
                        parts % 256,
                        mtu / 256,
                        mtu % 256
                    )
                )
                start = System.currentTimeMillis()
                Toast.makeText(this, "Start With MTU=$mtu, PART=$PART", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "디바이스와 연결되지않음", Toast.LENGTH_SHORT).show()
            }
        }

        requestBlePermissions(this,0)
        checkVerify()
    }

    /** -----------------------------------------------다운 관련 ------------------------------------------------------- **/
    @RequiresApi(Build.VERSION_CODES.N)
    private fun downloadFile(){
        val dstUri = File(getExternalFilesDir(null), "gosleepFirmware")

        if(!dstUri.exists()){
            dstUri.mkdir()
        }

        val dstFileUri = File(dstUri,FIRMWARE_FILE)
        if(dstFileUri.exists()){
            dstFileUri.delete()
        }

        val srcUrl = Config.RES_URL

        val request = DownloadManager.Request(Uri.parse(srcUrl))
            .setTitle("고슬립 펌웨어 업데이트")
            .setDescription("고슬립 펌웨어 다운로드중..")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(dstFileUri))
            .setRequiresCharging(false)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)


        downloadId = downloadManager.enqueue(request)
        downloadPath = dstFileUri.path
        Log.d("ddddd tag", "path : " + dstFileUri.path)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun checkVerify() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            }
            requestPermissions(
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ), 1
            )
        }
    }

    /** -----------------------------------------------파일 관련 ------------------------------------------------------- **/

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Timber.e("RequestCode= $requestCode, ResultCode= $resultCode, Data= ${data != null}")
        if (resultCode == Activity.RESULT_OK) {
            if (data != null && requestCode == FILE_PICK) {
                val selectedFile = data.data
                if (selectedFile != null) {
                    val filePath = RealPathUtil.getRealPath(this, selectedFile)     // 실제 외부 저장소의 경로 파악
                    if (filePath != null) {
                        Log.d("dddd","selected File : $selectedFile realPath : $filePath")
                        saveFile(File(filePath), null)  // 리얼저장소 주소에 있는 파일(선택한 파일)의 참조 객체(File) 전달
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        val directory = this.cacheDir
        val img = File(directory, UPDATE_FILE)
        val info = File(directory, "info.txt")
        val name = if (img.exists()){
            if (info.exists() && info.canRead()){
                info.readText()
            } else {
                "no file"
            }
        } else {
            "no file"
        }
        binding.txtBin.text = "파일명 : $name"
    }

    @Throws(IOException::class)
    fun saveFile(src: File?, uri: Uri?) {

        val directory = this.cacheDir           // 앱별(내부) 저장소 (캐시) 루트 경로
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val dst = File(directory, UPDATE_FILE)              // 공유 저장소에 펌웨어 파일을 옮겨받을 업데이트 파일(앱별 저장소) 생성
        if (dst.exists()){
            dst.delete()
        }

        if (src != null) {
            val info = File(directory, "info.txt")          // 내부 저장소에 선택한 '펌웨어이름'에 대한 정보 저장용 .txt 파일 생성(info)
            info.writeText(src.name)                             // 파일 이름을 info.txt에 적어놓음 (onResume 용, 쓸데없음)

            FileInputStream(src).use { `in` ->                  // src(외부) -> dst(내부)  로 복사 (update.bin 에)
                FileOutputStream(dst).use { out ->
                    // Transfer bytes from in to out
                    val buf = ByteArray(1024)
                    var len: Int
                    while (`in`.read(buf).also { len = it } > 0) {
                        out.write(buf, 0, len)
                    }
                }
            }
            val fos = FileOutputStream(dst, true)
            fos.flush()
            fos.close()
        }
    }

    @Throws(IOException::class)
    fun clearData() {
        val directory = this.cacheDir
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val upload = File(directory, "data")
        if (upload.exists()) {
            upload.deleteRecursively()
        }
    }

    @Throws(IOException::class)
    fun generate(): Int {
        val bytes = File(this.cacheDir, "update.bin").readBytes()           // 아까 앱별 저장소에 옮겨둔 update.bin 읽음
        val s = bytes.size / PART                                                // PART 만큼씩 분할함

        for (x in 0 until s) {
            val data = ByteArray(PART)
            for (y in 0 until PART) {
                data[y] = bytes[(x * PART) + y]
            }
            saveData(data, x)           // PART 만큼 분할된 바이트 어레이 저장   (분할된 데이터 파일은 data0.bin, data1.bin 이런식으로 분할됨)

        }
        if (bytes.size % PART != 0) {       // PART 크기로 나누어 떨어지지 않는 파일 크기(분할하고 남은 부분)
            val data = ByteArray(bytes.size % PART)
            for (y in 0 until bytes.size % PART) {
                data[y] = bytes[(s * PART) + y]
            }
            saveData(data, s)           // s번째 bin 파일로
        }
        return if (bytes.size % PART == 0) {
            (bytes.size / PART)
        } else {
            (bytes.size / PART) + 1
        }
    }


    @Throws(IOException::class)
    fun saveData(byteArray: ByteArray, pos: Int) {
        val directory = this.cacheDir
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val upload = File(directory, "data")        // data 폴더 생성, 폴더 내에 분할된 bin 파일 쓰기
        if (!upload.exists()) {
            upload.mkdirs()
        }
        val data = File(upload, "data$pos.bin")
        val fos = FileOutputStream(data, true)
        fos.write(byteArray)
        fos.flush()
        fos.close()
    }


    private fun checkExternal(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ActivityCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return true
    }

    private fun requestExternal(){
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            STORAGE
        )
    }


    /*******************************************  이하 스캔 및 연결 **************************************************/

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
                }
                return
            }
            STORAGE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    var chooseFile = Intent(Intent.ACTION_GET_CONTENT)
                    chooseFile.type = "*/*"
                    chooseFile = Intent.createChooser(chooseFile, "Choose a file")
                    startActivityForResult(chooseFile, 20)
                }
                return
            }
        }
    }

    // 어댑터로부터 데이터 받을 콜백 함수 (사용자가 선택한 디바이스 정보 콜백)
    private fun selectedDevice(device: BtDevice){
        scanLeDevice(false)
        deviceAddress = device.address

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
        unregisterReceiver(onDownloadComplete)
    }
}
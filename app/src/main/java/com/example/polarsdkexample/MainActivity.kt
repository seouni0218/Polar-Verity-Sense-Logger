package com.example.polarsdkexample

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.snackbar.Snackbar
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.LedConfig
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarEcgData
import com.polar.sdk.api.model.PolarExerciseEntry
import com.polar.sdk.api.model.PolarGyroData
import com.polar.sdk.api.model.PolarHrBroadcastData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarPpgData
import com.polar.sdk.api.model.PolarPpiData
import com.polar.sdk.api.model.PolarRecordingSecret
import com.polar.sdk.api.model.PolarSensorSetting
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.Volley;
import org.json.JSONException;
import org.json.JSONObject;
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object{
        private const val TAG="MainActivity"
        private const val API_LOGGER_TAG="API LOGGER"
        private const val PERMISSION_REQUEST_CODE=1
    }

    // Replace with the device ID from your device.
    //private var deviceId="BC15022D"

    private val api: PolarBleApi by lazy{
        // Notice all features are enabled
        PolarBleApiDefaultImpl.defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_LED_ANIMATION
            )
        )
    }

    private lateinit var broadcastDisposable: Disposable
    private var scanDisposable: Disposable? = null
    private var hrDisposable: Disposable? = null
    private var accDisposable: Disposable? = null
    private var gyrDisposable: Disposable? = null
    private var ppgDisposable: Disposable? = null

    private var bluetoothEnabled = false

    private lateinit var broadcastButton: Button

    // Polar 센서 4개 connect button 추가
    private lateinit var LAsensorBtn: Button
    private lateinit var LAHRBtn: Button
    private lateinit var LAPPGBtn: Button
    private lateinit var LAACCBtn: Button
    private lateinit var LAGYROBtn: Button
    private lateinit var LLsensorBtn: Button
    private lateinit var LLACCBtn: Button
    private lateinit var LLGYROBtn: Button
    private lateinit var RAsensorBtn: Button
    private lateinit var RAACCBtn: Button
    private lateinit var RAGYROBtn: Button
    private lateinit var RLsensorBtn: Button
    private lateinit var RLACCBtn: Button
    private lateinit var RLGYROBtn: Button
    private lateinit var devicesListView: ListView
    private lateinit var deviceButtonMap: Map<String, Button>
    var discoveredDevices=mutableListOf<PolarDeviceInfo>()
    val deviceStreamingRequirements = hashMapOf<String, String>() // 연결 및 스트리밍 요구 사항을 저장하는 맵
    var connectedDevices=mutableMapOf<String, PolarDeviceInfo>()

    private val entryCache: MutableMap<String, MutableList<PolarOfflineRecordingEntry>> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "version: " + PolarBleApiDefaultImpl.versionInfo())
        broadcastButton = findViewById(R.id.broadcast_button)

        // POLAR Sensor 4개 버튼 추가
        LAsensorBtn=findViewById(R.id.lasensorbtn)
        LAHRBtn=findViewById(R.id.lahr)
        LAPPGBtn=findViewById(R.id.lappg)
        LAACCBtn=findViewById(R.id.laacc)
        LAGYROBtn=findViewById(R.id.lagyro)
        LLsensorBtn=findViewById(R.id.llsensorbtn)
        LLACCBtn=findViewById(R.id.llacc)
        LLGYROBtn=findViewById(R.id.llgyro)
        RAsensorBtn=findViewById(R.id.rasensorbtn)
        RAACCBtn=findViewById(R.id.raacc)
        RAGYROBtn=findViewById(R.id.ragyro)
        RLsensorBtn=findViewById(R.id.rlsensorbtn)
        RLACCBtn=findViewById(R.id.rlacc)
        RLGYROBtn=findViewById(R.id.rlgyro)

        // 각 deviceId와 버튼을 맵핑
        deviceButtonMap = mapOf(
            "LAsensorDeviceId" to LAsensorBtn,
            "LLsensorDeviceId" to LLsensorBtn,
            "RAsensorDeviceId" to RAsensorBtn,
            "RLsensorDeviceId" to RLsensorBtn
        )

        devicesListView=findViewById<ListView>(R.id.devices_list_view)

        api.setPolarFilter(false)

        // If there is need to log what is happening inside the SDK, it can be enabled like this:
        val enableSdkLogs=false
        if(enableSdkLogs){
            api.setApiLogger{s:String->Log.d(API_LOGGER_TAG,s)}
        }

        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BLE power: $powered")
                bluetoothEnabled = powered
                if (powered) {
                    enableAllButtons()
                    showToast("Phone Bluetooth on")
                } else {
                    disableAllButtons()
                    showToast("Phone Bluetooth off")
                }
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                val deviceId=polarDeviceInfo.deviceId
                val button = findButtonByDeviceId(deviceId)

                button?.let{
                    val buttonTag=it.tag as? String
                    if(buttonTag!=null) {
                        val side = buttonTag
                        deviceStreamingRequirements[side]=deviceId
                        
                        // side에 따라서 button에 tag를 deviceId로 설정하기
                    }
                        /*deviceStreamingRequirements[deviceId] = Pair(side, requiredStreams)

                        startHrStreaming(deviceId)

                        //startSequentialStreaming(deviceId, side, requiredStreams)
                    }*/
                }
            }


            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "CONNECTING: ${polarDeviceInfo.deviceId}")
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                connectedDevices.remove(polarDeviceInfo.deviceId)
                Log.d(TAG, "DISCONNECTED: ${polarDeviceInfo.deviceId}")

                //UI 업데이트
                //deviceButtonMap[deviceId]?.setBackgroundColor(Color.GRAY)
                //deviceButtonMap[deviceId]?.text=deviceButtonMap[deviceId]?.text.toString().replace("$deviceId", "")
            }

        })

        // broadcast btn event
        broadcastButton.setOnClickListener {
            if (!this::broadcastDisposable.isInitialized || broadcastDisposable.isDisposed) {
                toggleButtonDown(broadcastButton, R.string.listening_broadcast)
                broadcastDisposable = api.startListenForPolarHrBroadcasts(null)
                    .subscribe(
                        { polarBroadcastData: PolarHrBroadcastData ->
                            Log.d(TAG, "HR BROADCAST ${polarBroadcastData.polarDeviceInfo.deviceId} HR: ${polarBroadcastData.hr} batt: ${polarBroadcastData.batteryStatus}")
                        },
                        { error: Throwable ->
                            toggleButtonUp(broadcastButton, R.string.listen_broadcast)
                            Log.e(TAG, "Broadcast listener failed. Reason $error")
                        },
                        { Log.d(TAG, "complete") }
                    )
            } else {
                toggleButtonUp(broadcastButton, R.string.listen_broadcast)
                broadcastDisposable.dispose()
            }
        }

        LAsensorBtn.setOnClickListener {
            showDeviceListAndConnect(LAsensorBtn)
            LAsensorBtn.tag = "LA"
        }

        LAHRBtn.setOnClickListener {
            val deviceId=deviceStreamingRequirements["LA"]
            val requestQueue=Volley.newRequestQueue(this)
            val url="http://IP주소/hrin.php" //IP 수정해야함
            val isDisposed = hrDisposable?.isDisposed ?: true
            if (isDisposed) {
                toggleButtonDown(LAHRBtn, R.string.stop_hr_stream)
                hrDisposable = api.startHrStreaming(deviceId.toString())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { hrData: PolarHrData ->
                            for (sample in hrData.samples) {
                                Log.d(TAG, "HR     bpm: ${sample.hr} rrs: ${sample.rrsMs} rrAvailable: ${sample.rrAvailable} contactStatus: ${sample.contactStatus} contactStatusSupported: ${sample.contactStatusSupported}")
                                // sample.hr -> mysql database upload
                                val timestamp=SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SS", Locale.getDefault()).format(Date())
                                val stringRequest=object: StringRequest(Request.Method.POST, url,
                                    Response.Listener<String>{response->
                                        Log.d("Response", response)
                                        //showToast(response)
                                    },
                                    Response.ErrorListener { error->
                                        Log.e("Error", error.toString())
                                    }){
                                    override fun getParams(): MutableMap<String, String>{
                                        val params=HashMap<String, String>()
                                        params["timestamp"]=timestamp
                                        params["hr"]=sample.hr.toString()
                                        return params
                                    }

                                }
                                requestQueue.add(stringRequest)
                            }
                        },
                        { error: Throwable ->
                            toggleButtonUp(LAHRBtn, R.string.start_hr_stream)
                            Log.e(TAG, "HR stream failed. Reason $error")
                        },
                        { Log.d(TAG, "HR stream complete") }
                    )
            } else {
                toggleButtonUp(LAHRBtn, R.string.start_hr_stream)
                // NOTE dispose will stop streaming if it is "running"
                hrDisposable?.dispose()
            }
        }

        LAPPGBtn.setOnClickListener {
            val deviceId=deviceStreamingRequirements["LA"]
            startPpgStreaming(deviceId.toString())
        }

        LAACCBtn.setOnClickListener {
            val deviceId=deviceStreamingRequirements["LA"]
            startAccStreaming(deviceId.toString(), "LA", LAACCBtn)
        }

        LAGYROBtn.setOnClickListener {
            val deviceId=deviceStreamingRequirements["LA"]
            startGyroStreaming(deviceId.toString(), "LA", LAGYROBtn)
        }

        LLsensorBtn.setOnClickListener {
            showDeviceListAndConnect(LLsensorBtn)
            LLsensorBtn.tag = "LL"
        }

        LLACCBtn.setOnClickListener {
            val deviceId=deviceStreamingRequirements["LL"]
            startAccStreaming(deviceId.toString(), "LL", LLACCBtn)
        }

        LLGYROBtn.setOnClickListener {
            val deviceId=deviceStreamingRequirements["LL"]
            startGyroStreaming(deviceId.toString(), "LL", LLGYROBtn)
        }

        RAsensorBtn.setOnClickListener {
            showDeviceListAndConnect(RAsensorBtn)
            RAsensorBtn.tag = "RA"
        }

        RAACCBtn.setOnClickListener {
            val deviceId=deviceStreamingRequirements["RA"]
            startAccStreaming(deviceId.toString(), "RA", RAACCBtn)
        }

        RAGYROBtn.setOnClickListener {
            val deviceId=deviceStreamingRequirements["RA"]
            startGyroStreaming(deviceId.toString(), "RA", RAGYROBtn)
        }

        RLsensorBtn.setOnClickListener {
            showDeviceListAndConnect(RLsensorBtn)
            RLsensorBtn.tag = "RL"
        }

        RLACCBtn.setOnClickListener {
            val deviceId=deviceStreamingRequirements["RL"]
            startAccStreaming(deviceId.toString(), "RL", RLACCBtn)
        }

        RLGYROBtn.setOnClickListener {
            val deviceId=deviceStreamingRequirements["RL"]
            startGyroStreaming(deviceId.toString(), "RL", RLGYROBtn)
        }

        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val selectedDevice = discoveredDevices[position]
            connectToDevice(selectedDevice.deviceId)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), PERMISSION_REQUEST_CODE)
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_CODE)
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), PERMISSION_REQUEST_CODE)
        }

    }


    private fun findButtonByDeviceId(deviceId: String): Button? {
        val buttonsContainer = findViewById<LinearLayout>(R.id.buttons_container)
        for (i in 0 until buttonsContainer.childCount) {
            val view = buttonsContainer.getChildAt(i)
            if (view is Button && view.text.toString() == deviceId) {
                return view
            }
        }
        return null // 일치하는 버튼이 없는 경우 null 반환
    }

    fun scanDevices(callingButton: Button){
        discoveredDevices.clear()

        // Polar API를 사용하여 장치 스캔 시작
        scanDisposable=api.searchForDevice()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {polarDeviceInfo: PolarDeviceInfo->
                    // 검색된 장치를 리스트에 추가
                    discoveredDevices.add(polarDeviceInfo)
                    Log.d(TAG, "Discovered Polar device: ${polarDeviceInfo.deviceId}")
                    //updateDeviceList()
                    showToast("Discovered Polar device: ${polarDeviceInfo.deviceId}")
                },
                {
                    error:Throwable->
                    Log.e(TAG, "Device scan failed. Reason: $error")
                },
                {
                    Log.d(TAG, "Device scan complete")
                    //updateDeviceList()
                    showDeviceListInDialog(callingButton)
                }
            )

        //10초 후에 스캔 중지
        Observable.timer(5, TimeUnit.SECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe{
                val disposable = scanDisposable
                if (disposable != null && !disposable.isDisposed) {
                    disposable.dispose()
                    Log.d(TAG, "Device scan stopped after 10 seconds")
                    //showToast("stop")
                    showDeviceListInDialog(callingButton)
                }
            }
    }

    // 장치에 연결 시도
    fun connectToDevice(deviceId:String){
        try{
            api.connectToDevice(deviceId)
        }catch(e:PolarInvalidArgument){
            Log.e(TAG,"Failed to connect to device $deviceId. Reason: $e")
        }
    }

    fun showDeviceListInDialog(callingButton: Button) {
        val devicesArray = discoveredDevices.map { "${it.name} (${it.deviceId})" }.toTypedArray()
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select a device to connect")

        builder.setItems(devicesArray) { dialog, which ->
            val selectedDeviceId = discoveredDevices[which].deviceId
            connectToDevice(selectedDeviceId)
            callingButton.text = "$selectedDeviceId"
            callingButton.setBackgroundColor(Color.BLUE)
            dialog.dismiss() // 대화상자 닫기
        }

        val dialog = builder.create()
        dialog.show()
    }

    fun showDeviceListAndConnect(callingButton: Button) {

        scanDevices(callingButton)
    }

    private fun startAccStreaming(deviceId: String, side: String, accButton: Button){
        val requestQueue=Volley.newRequestQueue(this)
        val url="http://IP주소/accin.php"
        val isDisposed = accDisposable?.isDisposed ?: true
        if (isDisposed) {
            toggleButtonDown(accButton, side+"stop_acc_streaming")
            accDisposable = requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ACC)
                .flatMap { settings: PolarSensorSetting ->
                    api.startAccStreaming(deviceId, settings)
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { polarAccelerometerData: PolarAccelerometerData ->
                        for (data in polarAccelerometerData.samples) {
                            Log.d(TAG, "ACC    x: ${data.x} y: ${data.y} z: ${data.z} timeStamp: ${data.timeStamp}")
                            // data.x data.y data.z -> mysql database upload
                            val timestamp=SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SS", Locale.getDefault()).format(Date())
                            val stringRequest=object: StringRequest(Request.Method.POST, url,
                                Response.Listener<String>{response->
                                    Log.d("Response", response)
                                    //showToast(response)
                                },
                                Response.ErrorListener { error->
                                    Log.e("Error", error.toString())
                                }){
                                override fun getParams(): MutableMap<String, String>{
                                    val params=HashMap<String, String>()
                                    params["timestamp"]=timestamp
                                    params["side"]=side
                                    params["x"]=data.x.toString()
                                    params["y"]=data.y.toString()
                                    params["z"]=data.z.toString()
                                    return params
                                }

                            }
                            requestQueue.add(stringRequest)
                        }
                    },
                    { error: Throwable ->
                        toggleButtonUp(accButton, side+"start_acc_streaming")
                        Log.e(TAG, "ACC stream failed. Reason $error")
                    },
                    {
                        //showToast("ACC stream complete")
                        Log.d(TAG, "ACC stream complete")
                    }
                )
        } else {
            toggleButtonUp(accButton, side+"start_acc_streaming")
            // NOTE dispose will stop streaming if it is "running"
            accDisposable?.dispose()
        }

    }

    private fun startGyroStreaming(deviceId: String, side: String, gyrButton: Button){
        val requestQueue=Volley.newRequestQueue(this)
        val url="http://IP주소/gyroin.php"
        val isDisposed = gyrDisposable?.isDisposed ?: true
        if (isDisposed) {
            toggleButtonDown(gyrButton, side+"stop_gyro_streaming")
            gyrDisposable =
                requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.GYRO)
                    .flatMap { settings: PolarSensorSetting ->
                        api.startGyroStreaming(deviceId, settings)
                    }
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { polarGyroData: PolarGyroData ->
                            for (data in polarGyroData.samples) {
                                Log.d(TAG, "GYR    x: ${data.x} y: ${data.y} z: ${data.z} timeStamp: ${data.timeStamp}")
                                // data.x data.y data.z -> mysql database upload
                                val timestamp=SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SS", Locale.getDefault()).format(Date())
                                val stringRequest=object: StringRequest(Request.Method.POST, url,
                                    Response.Listener<String>{response->
                                        Log.d("Response", response)
                                        //showToast(response)
                                    },
                                    Response.ErrorListener { error->
                                        Log.e("Error", error.toString())
                                    }){
                                    override fun getParams(): MutableMap<String, String>{
                                        val params=HashMap<String, String>()
                                        params["timestamp"]=timestamp
                                        params["side"]=side
                                        params["x"]=data.x.toString()
                                        params["y"]=data.y.toString()
                                        params["z"]=data.z.toString()
                                        return params
                                    }

                                }
                                requestQueue.add(stringRequest)
                            }
                        },
                        { error: Throwable ->
                            toggleButtonUp(gyrButton, side+"start_gyro_streaming")
                            Log.e(TAG, "GYR stream failed. Reason $error")
                        },
                        { Log.d(TAG, "GYR stream complete") }
                    )
        } else {
            toggleButtonUp(gyrButton, side+"start_gyro_streaming")
            // NOTE dispose will stop streaming if it is "running"
            gyrDisposable?.dispose()
        }
    }

    private fun startPpgStreaming(deviceId: String){
        val isDisposed = ppgDisposable?.isDisposed ?: true
        val requestQueue=Volley.newRequestQueue(this)
        val url="http://IP주소/ppgin.php"
        if (isDisposed) {
            //toggleButtonDown(ppgButton, R.string.stop_ppg_stream)
            ppgDisposable =
                requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.PPG)
                    .flatMap { settings: PolarSensorSetting ->
                        api.startPpgStreaming(deviceId, settings)
                    }
                    .subscribe(
                        { polarPpgData: PolarPpgData ->
                            if (polarPpgData.type == PolarPpgData.PpgDataType.PPG3_AMBIENT1) {
                                for (data in polarPpgData.samples) {
                                    Log.d(TAG, "PPG    ppg0: ${data.channelSamples[0]} ppg1: ${data.channelSamples[1]} ppg2: ${data.channelSamples[2]} ambient: ${data.channelSamples[3]} timeStamp: ${data.timeStamp}")
                                    // data.x data.y data.z -> mysql database upload
                                    val timestamp=SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SS", Locale.getDefault()).format(Date())
                                    val stringRequest=object: StringRequest(Request.Method.POST, url,
                                        Response.Listener<String>{response->
                                            Log.d("Response", response)
                                            //showToast(response)
                                        },
                                        Response.ErrorListener { error->
                                            Log.e("Error", error.toString())
                                        }){
                                        override fun getParams(): MutableMap<String, String>{
                                            val params=HashMap<String, String>()
                                            params["timestamp"]=timestamp
                                            params["channel0"]=data.channelSamples[0].toString()
                                            params["channel1"]=data.channelSamples[1].toString()
                                            params["channel2"]=data.channelSamples[2].toString()
                                            params["ambient"]=data.channelSamples[3].toString()
                                            return params
                                        }

                                    }
                                    requestQueue.add(stringRequest)
                                }
                            }
                        },
                        { error: Throwable ->
                            //toggleButtonUp(ppgButton, R.string.start_ppg_stream)
                            Log.e(TAG, "PPG stream failed. Reason $error")
                        },
                        { Log.d(TAG, "PPG stream complete") }
                    )
        } else {
            //toggleButtonUp(ppgButton, R.string.start_ppg_stream)
            // NOTE dispose will stop streaming if it is "running"
            ppgDisposable?.dispose()
        }

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (index in 0..grantResults.lastIndex) {
                if (grantResults[index] == PackageManager.PERMISSION_DENIED) {
                    disableAllButtons()
                    Log.w(TAG, "No sufficient permissions")
                    showToast("No sufficient permissions")
                    return
                }
            }
            Log.d(TAG, "Needed permissions are granted")
            enableAllButtons()
        }
    }

    public override fun onPause() {
        super.onPause()
    }

    public override fun onResume() {
        super.onResume()
        api.foregroundEntered()
    }

    public override fun onDestroy() {
        super.onDestroy()
        api.shutDown()
    }

    private fun toggleButtonDown(button: Button, text: String? = null) {
        toggleButton(button, true, text)
    }

    private fun toggleButtonDown(button: Button, @StringRes resourceId: Int) {
        toggleButton(button, true, getString(resourceId))
    }

    private fun toggleButtonUp(button: Button, text: String? = null) {
        toggleButton(button, false, text)
    }

    private fun toggleButtonUp(button: Button, @StringRes resourceId: Int) {
        toggleButton(button, false, getString(resourceId))
    }

    private fun toggleButton(button: Button, isDown: Boolean, text: String? = null) {
        if (text != null) button.text = text

        var buttonDrawable = button.background
        buttonDrawable = DrawableCompat.wrap(buttonDrawable!!)
        if (isDown) {
            DrawableCompat.setTint(buttonDrawable, resources.getColor(R.color.primaryDarkColor))
        } else {
            DrawableCompat.setTint(buttonDrawable, resources.getColor(R.color.primaryColor))
        }
        button.background = buttonDrawable
    }

    private fun requestStreamSettings(identifier: String, feature: PolarBleApi.PolarDeviceDataType): Flowable<PolarSensorSetting> {
        val availableSettings = api.requestStreamSettings(identifier, feature)
        val allSettings = api.requestFullStreamSettings(identifier, feature)
            .onErrorReturn { error: Throwable ->
                Log.w(TAG, "Full stream settings are not available for feature $feature. REASON: $error")
                PolarSensorSetting(emptyMap())
            }
        return Single.zip(availableSettings, allSettings) { available: PolarSensorSetting, all: PolarSensorSetting ->
            if (available.settings.isEmpty()) {
                throw Throwable("Settings are not available")
            } else {
                Log.d(TAG, "Feature " + feature + " available settings " + available.settings)
                Log.d(TAG, "Feature " + feature + " all settings " + all.settings)
                return@zip android.util.Pair(available, all)
            }
        }
            .observeOn(AndroidSchedulers.mainThread())
            .toFlowable()
            .flatMap { sensorSettings: android.util.Pair<PolarSensorSetting, PolarSensorSetting> ->
                DialogUtility.showAllSettingsDialog(
                    this@MainActivity,
                    sensorSettings.first.settings,
                    sensorSettings.second.settings
                ).toFlowable()
            }
    }

    private fun showToast(message: String) {
        val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
        toast.show()
    }

    private fun showSnackbar(message: String) {
        val contextView = findViewById<View>(R.id.buttons_container)
        Snackbar.make(contextView, message, Snackbar.LENGTH_LONG)
            .show()
    }

    private fun showDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                // Respond to positive button press
            }
            .show()
    }

    private fun disableAllButtons() {
        broadcastButton.isEnabled = false
    }

    private fun enableAllButtons() {
        broadcastButton.isEnabled = true
    }

    private fun disposeAllStreams() {
        //ecgDisposable?.dispose()
        accDisposable?.dispose()
        gyrDisposable?.dispose()
        //magDisposable?.dispose()
        ppgDisposable?.dispose()
    }
}
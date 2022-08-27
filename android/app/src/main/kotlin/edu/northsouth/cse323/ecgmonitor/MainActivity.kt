package edu.northsouth.cse323.ecgmonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.activity.result.registerForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.scichart.charting.ClipMode
import com.scichart.charting.model.dataSeries.XyDataSeries
import com.scichart.charting.modifiers.AxisDragModifierBase
import com.scichart.charting.modifiers.ModifierGroup
import com.scichart.charting.visuals.SciChartSurface
import com.scichart.charting.visuals.axes.IAxis
import com.scichart.charting.visuals.renderableSeries.IRenderableSeries
import com.scichart.core.annotations.Orientation
import com.scichart.core.framework.UpdateSuspender
import com.scichart.drawing.utility.ColorUtil
import com.scichart.extensions.builders.SciChartBuilder
import edu.northsouth.cse323.ecgmonitor.ui.theme.ECGMonitorTheme
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.util.*

private const val TAG_SCICHART = "TAG_SCICHART"
private const val TAG_IO = "TAG_IO"
private const val TAG_EX = "TAG_EX"
private const val BT_MAC_ADDRESS = "00:19:09:03:08:93"
private const val BT_SERVICE_UUID = "00001101-0000-1000-8000-00805f9b34fb"
private const val CHART_FIFO_CAPACITY = 500
private const val MAX_TRIES = 10
private const val DELAY: Long = 100  // milliseconds

private val NEW_BT_PERMISSIONS = Build.VERSION.SDK_INT > Build.VERSION_CODES.R
private val requiredPermissions =
    if (NEW_BT_PERMISSIONS) arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
    ) else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    ) else arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothDevice: BluetoothDevice
    private var bluetoothSocket: BluetoothSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter

        // Register for broadcasts when a device is discovered
        registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))

        licenseSciChart()

        setContent {
            ECGMonitorTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") { MainScreen(navController) }
                        composable("plot") { PlotScreen() }
                    }
                }
            }
        }
    }

    @Composable
    private fun MainScreen(navController: NavController) {
        val scaffoldState = rememberScaffoldState()
        val coroutineScope = rememberCoroutineScope()
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = { enableBluetooth() }, modifier = Modifier.fillMaxWidth()) {
                Text("Enable Bluetooth")
            }
            Button(onClick = { startBluetoothDeviceSearch() }, modifier = Modifier.fillMaxWidth()) {
                Text("Search for Bluetooth device")
            }
            Button(onClick = {
                bluetoothSocket ?: run {
                    Toast.makeText(
                        this@MainActivity,
                        "Please connect to the device first",
                        Toast.LENGTH_SHORT
                    ).show()
                    coroutineScope.launch {
                        scaffoldState.snackbarHostState.showSnackbar("Please connect to the device first")
                    }
                    return@Button
                }
                navController.navigate("plot")
            }) {
                Text("Begin ECG plot")
            }
        }
    }

    @Composable
    private fun PlotScreen() {
        AndroidView(factory = { context ->
            SciChartBuilder.init(context)
            val scichartBuilder: SciChartBuilder = SciChartBuilder.instance()
            val xAxis: IAxis = scichartBuilder.newNumericAxis()
                .withAxisTitle("x-axis")
                .build()
            val yAxis: IAxis = scichartBuilder.newNumericAxis()
                .withAxisTitle("y-axis")
                .build()
            val modifiers: ModifierGroup = scichartBuilder.newModifierGroup()
                .withPinchZoomModifier().withReceiveHandledEvents(true).build()
                .withZoomPanModifier().withReceiveHandledEvents(true).build()
                .withZoomExtentsModifier().withReceiveHandledEvents(true).build()
                .withXAxisDragModifier()
                .withReceiveHandledEvents(true)
                .withDragMode(AxisDragModifierBase.AxisDragMode.Scale)
                .withClipModeX(ClipMode.None)
                .build()
                .withYAxisDragModifier()
                .withReceiveHandledEvents(true)
                .withDragMode(AxisDragModifierBase.AxisDragMode.Pan)
                .build()
                .withLegendModifier().withOrientation(Orientation.HORIZONTAL)
                .withPosition(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM, 10).build()
                .build()

            val lineData: XyDataSeries<Int, Double> =
                scichartBuilder.newXyDataSeries(
                    Int::class.javaObjectType,
                    Double::class.javaObjectType
                )
                    .withFifoCapacity(CHART_FIFO_CAPACITY)
                    .build()
            val lineSeries: IRenderableSeries = scichartBuilder.newLineSeries()
                .withDataSeries(lineData)
                .withStrokeStyle(ColorUtil.LightBlue, 2f, true)
                .build()

            val surface = SciChartSurface(context).apply {
                xAxes += xAxis
                yAxes += yAxis
                renderableSeries += lineSeries
                chartModifiers += modifiers
            }

            val updateData = LineDataUpdaterTask(lineData, surface, bluetoothSocket!!)
            Timer().schedule(updateData, DELAY, DELAY)

            surface
        })
    }

    private fun licenseSciChart() {
        try {
            val key = getString(R.string.scichart_license_key)
            SciChartSurface.setRuntimeLicenseKey(key)
        } catch (e: Exception) {
            Log.e(TAG_SCICHART, "Error when setting the SciChart license", e)
        }
    }

    private fun enableBluetooth() {
        if (bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is already turned on", Toast.LENGTH_SHORT).show()
        } else {
            enableBluetoothLauncher.launch()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    ) { activityResult: ActivityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {  // user has turned Bluetooth on
            Toast.makeText(this, "Bluetooth is now on", Toast.LENGTH_SHORT).show()
        } else {  // user has rejected the request or an error has occurred
            // show a message then restart the process
            AlertDialog.Builder(this)
                .setMessage("This app requires Bluetooth")
                .setPositiveButton("ok") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    private fun requestBluetoothPermissions() { requestPermissionsLauncher.launch() }

    private val requestPermissionsLauncher = registerForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        input = requiredPermissions
    ) { permissionResults ->
        if (permissionResults.any { it.value.not() }) {  // if any permission was not granted
            AlertDialog.Builder(this)
                .setMessage("You've denied some permissions that are required for this app to functionality")
                .setPositiveButton("ok") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    private fun startBluetoothDeviceSearch() {
        if (!bluetoothAdapter.isEnabled) {  // Bluetooth is turned off
            Toast.makeText(this, "Please turn on Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }
        while (ActivityCompat.checkSelfPermission(
                this,
                if (NEW_BT_PERMISSIONS) Manifest.permission.BLUETOOTH else Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissions()
        }
        while (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissions()
        }

        bluetoothAdapter.startDiscovery()
    }

    // Create a BroadcastReceiver for ACTION_FOUND for when a Bluetooth device is detected.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_FOUND) return
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            device ?: run { TODO() }
            if (device.address == BT_MAC_ADDRESS) {
                bluetoothDevice = device
                connectToBluetoothDevice()
            }
        }
    }

    private fun connectToBluetoothDevice() {
        var counter = MAX_TRIES
        while (ActivityCompat.checkSelfPermission(
                this,
                if (NEW_BT_PERMISSIONS) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissions()
        }

        while (bluetoothSocket == null && counter-- != 0) {
            bluetoothSocket = bluetoothDevice.createInsecureRfcommSocketToServiceRecord(
                UUID.fromString(BT_SERVICE_UUID)
            )
        }
        bluetoothSocket ?: run {
            Toast.makeText(
                this,
                "Could not create a Bluetooth communication medium (socket) after $MAX_TRIES tries",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        bluetoothSocket!!.connect()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        try {
            bluetoothSocket?.close()
            bluetoothSocket = null
        } catch (e: IOException) {
            Log.e(TAG_IO, "I/O error while closing the Bluetooth socket", e)
        } catch (e: Exception) {
            Log.e(TAG_EX, "Could not close the Bluetooth socket", e)
        }
        super.onDestroy()
    }
}

class LineDataUpdaterTask(
    private val lineData: XyDataSeries<Int, Double>,
    private val scichartSurface: SciChartSurface,
    bluetoothSocket: BluetoothSocket
) : TimerTask() {
    private val inputStream: InputStream = bluetoothSocket.inputStream
    private val bufferedReader: BufferedReader = inputStream.bufferedReader()

    private var x = 0

    override fun run() {
        UpdateSuspender.using(scichartSurface) {
            try {
                val line: String? = bufferedReader.readLine()
                line ?: run { cancel(); return@using }
                val (_, _, y) = line.split(',')
                lineData.append(x++, y.toDouble())
                scichartSurface.zoomExtents()
            } catch (e: IOException) {
                Log.e(TAG_IO, "I/O error while adding new plot data", e)
            } catch (e: Exception) {
                Log.e(TAG_EX, "Error while adding new plot data", e)
            }
        }
    }

    override fun cancel(): Boolean {
        try {
            bufferedReader.close()
        } catch (e: IOException) {
            Log.e(TAG_IO, "I/O error in closing the BT socket's inputStream's bufferedReader", e)
        } catch (e: Exception) {
            Log.e(TAG_EX, "Could not close the Bluetooth socket's inputStream's bufferedReader", e)
        }
        try {
            inputStream.close()
        } catch (e: IOException) {
            Log.e(TAG_IO, "I/O error while closing the Bluetooth socket's inputStream", e)
        } catch (e: Exception) {
            Log.e(TAG_EX, "Could not close the Bluetooth socket's inputStream", e)
        }
        return super.cancel()
    }
}

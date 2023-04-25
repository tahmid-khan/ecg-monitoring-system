package edu.northsouth.cse323.ecgmonitor

import android.Manifest
import android.annotation.SuppressLint
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
import android.net.Uri
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
import androidx.compose.runtime.*
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
import java.io.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

private const val TAG_SCICHART = "TAG_SCICHART"
private const val TAG_IO = "TAG_IO"
private const val TAG_EX = "TAG_EX"
private const val BT_MAC_ADDRESS = "00:19:09:03:08:93"
private const val BT_SERVICE_UUID = "00001101-0000-1000-8000-00805f9b34fb"
private const val MAX_TRIES = 10
private const val PLOT_FIFO_CAPACITY = 50
private const val MEDIA_TYPE = "application/x-ecg-data"
private const val PLOT_UPDATE_INTERVAL: Long = 10  // milliseconds

private val NEW_BT_PERMISSIONS = Build.VERSION.SDK_INT > Build.VERSION_CODES.R
private val requiredPermissions =
    if (NEW_BT_PERMISSIONS) arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
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
    private var inFileUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter

        // Register for broadcasts when a device is discovered
        registerReceiver(broadcastReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))

        licenseSciChart()

        setContent {
            ECGMonitorTheme(darkTheme = true) {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background,
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") { MainScreen(navController) }
                        composable("new_plot") { NewPlotScreen() }
                        composable("read_plot") { ReadPlotScreen() }
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
            Button(onClick = { findBluetoothDevice() }, modifier = Modifier.fillMaxWidth()) {
                Text("Connect to Bluetooth device")
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
                navController.navigate("new_plot")
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Begin new ECG plot")
            }
            Button(onClick = {
                if (inFileUri == null) {
//                    AlertDialog.Builder(this)
//                        .setMessage("Please open a file first")
//                        .setPositiveButton("ok") { d, _ ->
//                            d.dismiss();
//                            docPickerLauncher.launch()
//                        }
//                        .show()
                    docPickerLauncher.launch()
                }
                navController.navigate("read_plot")
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Open an ECG plot file")
            }
        }
    }

//    private val heartRate = mutableStateOf("")

    @Composable
    private fun NewPlotScreen() {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
//            Text(heartRate.value)
            PlotArea()
        }
    }

    @Composable
    private fun PlotArea() {
        AndroidView(factory = { context ->
            SciChartBuilder.init(context)
            val scichartBuilder: SciChartBuilder = SciChartBuilder.instance()
            val xAxis: IAxis = scichartBuilder.newNumericAxis()
                .withAxisTitle("time")
                .build()
            val yAxis: IAxis = scichartBuilder.newNumericAxis()
                .withAxisTitle("voltage")
                .build()
            val modifiers: ModifierGroup = scichartBuilder.newModifierGroup()
//                .withPinchZoomModifier().withReceiveHandledEvents(true).build()
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
                .withLegendModifier()
                    .withOrientation(Orientation.HORIZONTAL)
                    .withPosition(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM, 10)
                    .build()
                .build()

            val lineData: XyDataSeries<Int, Double> =
                scichartBuilder.newXyDataSeries(
                    Int::class.javaObjectType,
                    Double::class.javaObjectType
                )
                    .withFifoCapacity(50)
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

//            val datetime = SimpleDateFormat("yyyy-MM-dd_HH_mm_ss", Locale.UK)
//                .format(DateFormat.getDateTimeInstance())!!
//            val filename = "$datetime.ecg.txt"
//            val outFile = File(context.filesDir, filename)
//            outFile.createNewFile()
            val updateData = LineDataUpdaterTask(
                lineData = lineData,
                scichartSurface = surface,
                bluetoothSocket = bluetoothSocket!!,
//                outFile = outFile
            )
            Timer().schedule(updateData, 10, 10)

            surface
        })
    }

    @Composable
    private fun ReadPlotScreen() {
        AndroidView(factory = { context ->
            SciChartBuilder.init(context)
            val scichartBuilder: SciChartBuilder = SciChartBuilder.instance()
            val xAxis: IAxis = scichartBuilder.newNumericAxis()
                .withAxisTitle("time")
//                .withVisibleRange()
                .build()
            val yAxis: IAxis = scichartBuilder.newNumericAxis()
                .withAxisTitle("voltage")
//                .withVisibleRange()
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
                .withLegendModifier()
                .withOrientation(Orientation.HORIZONTAL)
                .withPosition(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM, 10)
                .build()
                .build()
            val lineData: XyDataSeries<Int, Double> = scichartBuilder.newXyDataSeries(
                Int::class.javaObjectType,
                Double::class.javaObjectType
            ).build()
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

            val contentResolver = applicationContext.contentResolver
            contentResolver.openInputStream(inFileUri!!)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var x = 0
                    reader.forEachLine {
                        val y = it.toDouble()
                        lineData.append(x, y)
                        x++
                    }
                }
            }

            surface
        })
    }

    private fun licenseSciChart() {
        try {
            val key = getString(R.string.scichart_license_key)
            SciChartSurface.setRuntimeLicenseKey(key)
        } catch (e: Exception) {
            Log.v(TAG_SCICHART, "Error when setting the SciChart license", e)
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

    private fun findBluetoothDevice() {
        if (!bluetoothAdapter.isEnabled) {  // Bluetooth is turned off
            Toast.makeText(this, "Please turn on Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        // first, check if the HC-05 device is already paired
        while (ActivityCompat.checkSelfPermission(
                this,
                if (NEW_BT_PERMISSIONS) Manifest.permission.BLUETOOTH else Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissions()
        }
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        pairedDevices ?: run {
            Toast.makeText(
                this,
                "An error has occurred while getting the list of paired Bluetooth devices.  Please try again.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        pairedDevices.forEach { device ->
            if (device.address == BT_MAC_ADDRESS) {
                // the HC-05 is already paired, so no need to scan for it
                Toast.makeText(
                    this,
                    "The device HC-05 is already paired.  Connecting...",
                    Toast.LENGTH_SHORT
                ).show()
                bluetoothDevice = device
                connectToBluetoothDevice()
                return
            }
        }

        // our device is not paired, so start a scan for it
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
    private val broadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_FOUND) return
            Toast.makeText(this@MainActivity, "Reached broadcastReceiver", Toast.LENGTH_SHORT).show()
            val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
            if (device.address == BT_MAC_ADDRESS) {
                Toast.makeText(
                    this@MainActivity,
                    "Found the Arduino HC-05 Bluetooth module!",
                    Toast.LENGTH_SHORT
                ).show()
                bluetoothDevice = device
                connectToBluetoothDevice()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Found device ${device.name} (${device.address}):\n$device",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Assuming that the HC-05 is in range, connect to it
    private fun connectToBluetoothDevice() {
        while (ActivityCompat.checkSelfPermission(
                this,
                if (NEW_BT_PERMISSIONS) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissions()
        }

        bluetoothSocket?.isConnected?.run {
            Toast.makeText(
                this@MainActivity,
                "The Bluetooth device is already connected",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        repeat(MAX_TRIES) {
            if (bluetoothSocket != null) {
                return@repeat
            }
            bluetoothSocket = bluetoothDevice.createInsecureRfcommSocketToServiceRecord(
                UUID.fromString(BT_SERVICE_UUID)
            )
        }
        bluetoothSocket ?: run {
            Toast.makeText(
                this,
                "Could not create a Bluetooth communication medium (socket) after $MAX_TRIES tries." +
                        "\nIs the device within range?",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        repeat(MAX_TRIES) {
            if (bluetoothSocket!!.isConnected) {
                return@repeat
            }
            bluetoothSocket!!.connect()
        }
        Toast.makeText(
            this,
            if (bluetoothSocket == null) {
                "Could not connect to the Bluetooth device despite $MAX_TRIES tries"
            } else {
                "Connection successful"
            },
            Toast.LENGTH_LONG
        ).show()
    }

    private val docPickerLauncher = registerForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        input = arrayOf("text/plain")
    ) { uri: Uri? ->
        inFileUri = uri
    }

    override fun onDestroy() {
        unregisterReceiver(broadcastReceiver)
        try {
            bluetoothSocket?.close()
            bluetoothSocket = null
        } catch (e: IOException) {
            Log.v(TAG_IO, "I/O error while closing the Bluetooth socket", e)
        } catch (e: Exception) {
            Log.v(TAG_EX, "Could not close the Bluetooth socket", e)
        }
        super.onDestroy()
    }
}

class LineDataUpdaterTask(
    private val lineData: XyDataSeries<Int, Double>,
    private val scichartSurface: SciChartSurface,
    bluetoothSocket: BluetoothSocket,
//    outFile: File,
) : TimerTask() {
    private val inputStream: InputStream = bluetoothSocket.inputStream
    private val reader: BufferedReader = inputStream.bufferedReader()
//    private val outputStream: OutputStream = outFile.outputStream()
//    private val writer = outputStream.bufferedWriter()

    private var x = 0

    override fun run() {
        UpdateSuspender.using(scichartSurface) {
            try {
                val line: String? = reader.readLine()
                line ?: run { cancel(); return@using }
//                writer.write("$line\n")
                val (_, _, y) = line.split(',')
                lineData.append(x, y.toDouble())
                x++
                scichartSurface.zoomExtents()
            } catch (e: IOException) {
                Log.v(TAG_IO, "I/O error while adding new plot data", e)
                cancel()
            } catch (e: Exception) {
                Log.v(TAG_EX, "Error while adding new plot data", e)
                cancel()
            }
        }
    }

    override fun cancel(): Boolean {
        try {
            reader.close()
        } catch (e: IOException) {
            Log.v(TAG_IO, "I/O error in closing the BT socket's inputStream's bufferedReader", e)
        } catch (e: Exception) {
            Log.v(TAG_EX, "Could not close the Bluetooth socket's inputStream's bufferedReader", e)
        }
        try {
            inputStream.close()
        } catch (e: IOException) {
            Log.v(TAG_IO, "I/O error while closing the Bluetooth socket's inputStream", e)
        } catch (e: Exception) {
            Log.v(TAG_EX, "Could not close the Bluetooth socket's inputStream", e)
        }
//        try {
//            writer.close()
//        } catch (e: IOException) {
//            Log.v(TAG_IO, "I/O error in closing the output file's outputStream's bufferedWriter", e)
//        } catch (e: Exception) {
//            Log.v(TAG_EX, "Could not close the output file's outputStream's bufferedWriter", e)
//        }
//        try {
//            outputStream.close()
//        } catch (e: IOException) {
//            Log.v(TAG_IO, "I/O error while closing the output file's outputStream", e)
//        } catch (e: Exception) {
//            Log.v(TAG_EX, "Could not close the output file's outputStream", e)
//        }
        return super.cancel()
    }
}

package edu.northsouth.cse323.ecgmonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
import java.io.IOException
import java.util.*

private const val TAG_SCICHART = "TAG_SCICHART"
private const val TAG_IO = "TAG_IO"
private const val TAG_EX = "TAG_EX"

private const val CHART_FIFO_CAPACITY = 500

private const val BLUETOOTH_MAC_ADDRESS = "00:19:09:03:08:93"
private const val BLUETOOTH_SERVICE_UUID = "00001101-0000-1000-8000-00805f9b34fb"

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothDevice: BluetoothDevice

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        licenseSciChart()

        setContent {
            ECGMonitorTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    MainContent()
                }
            }
        }
    }

    private fun licenseSciChart() {
        try {
            val key = getString(R.string.scichart_license_key)
            SciChartSurface.setRuntimeLicenseKey(key)
        } catch (e: Exception) {
            Log.e(TAG_SCICHART, "Error when setting the license", e)
        }
    }

    @Composable private fun MainContent() {
        Column {
            Button(onClick = { startBluetoothSetup() }) {
                Text(text = "enable and connect bluetooth")
            }
            DummyPlot()
        }
    }

    @Composable private fun DummyPlot() {
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

            val updateDataTask = object : TimerTask() {
                val inputStream = getString(R.string.ecg_data).byteInputStream()
                val bufferedReader = inputStream.bufferedReader()

                override fun run() {
                    UpdateSuspender.using(surface) {
                        try {
                            val line: String? = bufferedReader.readLine()
                            line ?: cancel()
                            val (x, y) = line!!.split(',')
                            lineData.append(x.toInt(), y.toDouble())
                            surface.zoomExtents()
                        } catch (e: IOException) {
                            Log.e(TAG_IO, "I/O error adding new plot data", e)
                        } catch (e: Exception) {
                            Log.e(TAG_EX, "unknown error while adding new plot data", e)
                        }
                    }
                }

                override fun cancel(): Boolean {
                    bufferedReader.close()
                    inputStream.close()
                    return super.cancel()
                }
            }

            Timer().schedule(updateDataTask, 0, 10)

            surface
        })
    }

    // Register the permissions callback, which handles the user's response to the system
    // permissions dialog. Save the return value, an instance of ActivityResultLauncher.  You can
    // use either a val, as shown in this snippet, or a lateinit var in your onAttach() or
    // onCreate() method.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission is granted. Continue the action or workflow in your
            // app.
            startBluetoothSetup()
        } else {
            // Explain to the user that the feature is unavailable because the
            // features requires a permission that the user has denied. At the
            // same time, respect the user's decision. Don't link to system
            // settings in an effort to convince the user to change their
            // decision.
            requestPermissionBluetoothConnect()
        }
    }

    private fun requestPermissionBluetoothConnect() {
        requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun startBluetoothSetup() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter!!

        if (bluetoothAdapter.isEnabled) {  // Bluetooth is turned on
            connectBluetoothDevice()
        } else {  // Bluetooth is turned off
            enableBluetoothThenConnectDevice()
        }
    }

    // request user to enable Bluetooth, then handle the response
    private fun enableBluetoothThenConnectDevice() {
        val intentToEnableBluetooth = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(intentToEnableBluetooth)
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult: ActivityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {  // user has turned Bluetooth on
            connectBluetoothDevice()
        } else {  // user has rejected the request or an error has occurred
            // show a message then restart the process
            AlertDialog.Builder(this)
                .setMessage("This app requires Bluetooth")
                .setPositiveButton("ok") { dialog, _ ->
                    dialog.dismiss()
                    enableBluetoothThenConnectDevice()
                }
                .show()
        }
    }

    private fun connectBluetoothDevice() {
        findBluetoothDevice()
    }

    private fun findBluetoothDevice() {
        val deviceFilter = BluetoothDeviceFilter.Builder()
            .build()
        val pairingRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .build()

        val deviceManagerCallback = object : CompanionDeviceManager.Callback() {
            // Called when a device is found. Launch the IntentSender so the user
            // can select the device they want to pair with.
            override fun onDeviceFound(deviceChooserIntentSender: IntentSender) {
                val deviceChooserIntentSenderRequest = IntentSenderRequest
                    .Builder(deviceChooserIntentSender).build()
                chooseDeviceLauncher.launch(deviceChooserIntentSenderRequest)
            }

            override fun onFailure(error: CharSequence?) {
                // TODO: Handle the failure.
            }
        }

        val deviceManager by lazy { getSystemService(CompanionDeviceManager::class.java) }
        deviceManager.associate(pairingRequest, deviceManagerCallback, null)
    }

    private val chooseDeviceLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult: ActivityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val intent = activityResult.data
            val chosenDevice: BluetoothDevice? = intent
                ?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
            chosenDevice ?: { TODO() }
            bluetoothDevice = chosenDevice!!
            bluetoothDevice.createBond()
        } else {
            AlertDialog.Builder(this)
                .setMessage("Please select a device")
                .setPositiveButton("ok") { dialog, _ ->
                    dialog.dismiss()
                    findBluetoothDevice()
                }
                .show()
        }
    }
}

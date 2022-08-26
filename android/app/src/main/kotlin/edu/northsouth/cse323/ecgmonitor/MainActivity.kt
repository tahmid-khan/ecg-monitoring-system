package edu.northsouth.cse323.ecgmonitor

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
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
import java.util.Timer
import java.util.TimerTask

private const val TAG_SCICHART = "TAG_SCICHART"
private const val TAG_IO = "TAG_IO"
private const val TAG_EX = "TAG_EX"

private const val CHART_FIFO_CAPACITY = 500

class MainActivity : ComponentActivity() {
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
                    DummyPlot()
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
}

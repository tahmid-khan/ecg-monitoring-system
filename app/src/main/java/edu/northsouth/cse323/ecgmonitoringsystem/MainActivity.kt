package edu.northsouth.cse323.ecgmonitoringsystem

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import edu.northsouth.cse323.ecgmonitoringsystem.ui.theme.ECGMonitoringSystemTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val key = getString(R.string.scichart_license_key)
            com.scichart.charting.visuals.SciChartSurface.setRuntimeLicenseKey(key)
        } catch (e: Exception) {
            Log.e("SciChart", "Error when setting the license", e)
        }

        setContent {
            ECGMonitoringSystemTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    Greeting("Android")
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String) {
    Text(text = "Hello $name!")
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    ECGMonitoringSystemTheme {
        Greeting("Android")
    }
}

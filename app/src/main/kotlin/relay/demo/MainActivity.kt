package relay.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import relay.demo.home.ModuleHomeScreen
import relay.demo.home.RelayModule
import relay.demo.llm.LlmTestScreen
import relay.demo.ondevice.OnDeviceTestScreen
import relay.demo.theme.RelayDemoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RelayDemoTheme {
                RelayDemoApp()
            }
        }
    }
}

/**
 * One screen per Relay module. `relay-llm` and `relay-ondevice` are live; the rest stay
 * listed as disabled entries so the runtime shape remains visible as modules land.
 */
@Composable
private fun RelayDemoApp() {
    var openModule by remember { mutableStateOf<RelayModule?>(null) }

    when (openModule) {
        RelayModule.Llm -> LlmTestScreen(onBack = { openModule = null })
        RelayModule.OnDevice -> OnDeviceTestScreen(onBack = { openModule = null })
        else -> ModuleHomeScreen(onOpenModule = { openModule = it })
    }
}

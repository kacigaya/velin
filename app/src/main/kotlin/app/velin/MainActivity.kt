package app.velin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.velin.core.designsystem.theme.VelinTheme
import app.velin.navigation.VelinApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = application as VelinApplication
        setContent {
            VelinTheme {
                VelinApp(graph)
            }
        }
    }
}

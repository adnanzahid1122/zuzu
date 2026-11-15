package com.example.zuzuvpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.zuzuvpn.ui.ZuzuApp
import com.example.zuzuvpn.ui.splash.ZuzuSplashExit
import com.example.zuzuvpn.ui.theme.ZuzuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate(): shows Theme.Zuzu.Starting, then switches to Theme.Zuzu.
        // No setKeepOnScreenCondition: the splash leaves as soon as the first frame is ready.
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        splashScreen.setOnExitAnimationListener(ZuzuSplashExit::play)
        setContent {
            ZuzuTheme {
                ZuzuApp()
            }
        }
    }
}

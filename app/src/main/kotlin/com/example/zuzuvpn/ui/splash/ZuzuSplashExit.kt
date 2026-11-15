package com.example.zuzuvpn.ui.splash

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.splashscreen.SplashScreenViewProvider

/**
 * Exit transition from the system splash into Home: the mascot icon lifts slightly and fades
 * while the navy splash layer dissolves, revealing the already-drawn Home screen underneath.
 *
 * Runs only once the app is ready (no added waiting). Durations follow the system animator
 * scale, so "Remove animations" in accessibility settings makes it instant.
 */
object ZuzuSplashExit {
    private const val EXIT_MS = 320L

    fun play(provider: SplashScreenViewProvider) {
        val root: View = provider.view

        // Some OEM builds do not expose the icon view; the fade still works without it.
        val icon: View? = runCatching { provider.iconView }.getOrNull()
        if (icon != null) {
            icon.animate()
                .scaleX(1.08f)
                .scaleY(1.08f)
                .translationY(-icon.height * 0.04f)
                .alpha(0f)
                .setDuration(EXIT_MS)
                .setInterpolator(AccelerateInterpolator())
                .start()
        }

        root.animate()
            .alpha(0f)
            .setDuration(EXIT_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { provider.remove() }
            .start()
    }
}

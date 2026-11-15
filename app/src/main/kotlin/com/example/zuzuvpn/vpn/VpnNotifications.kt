package com.example.zuzuvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.zuzuvpn.MainActivity
import com.example.zuzuvpn.R

/** Ongoing notification of the foreground VPN service. Never shows server secrets. */
class VpnNotifications(private val context: Context) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun build(state: VpnState): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            context,
            1,
            ZuzuVpnService.disconnectIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = when (state) {
            is VpnState.Connected -> context.getString(R.string.vpn_notification_connected) to
                context.getString(R.string.vpn_notification_connected_text, state.serverLabel)
            is VpnState.Connecting -> context.getString(R.string.vpn_notification_connecting) to
                context.getString(stepText(state.step))
            else -> context.getString(R.string.vpn_notification_disconnecting) to ""
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (state is VpnState.Connected || state is VpnState.Connecting) {
                    addAction(0, context.getString(R.string.connect_button_disconnect), disconnect)
                }
                if (state is VpnState.Connected) {
                    setWhen(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - state.sinceElapsedRealtime))
                    setUsesChronometer(true)
                    setShowWhen(true)
                }
            }
            .build()
    }

    @Suppress("MissingPermission") // Without POST_NOTIFICATIONS the update is silently dropped.
    fun update(state: VpnState) {
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) manager.notify(ID, build(state))
    }

    companion object {
        const val ID = 1001
        private const val CHANNEL_ID = "vpn_status"

        @StringRes
        fun stepText(step: ConnectStep): Int = when (step) {
            ConnectStep.Preparing -> R.string.vpn_step_preparing
            ConnectStep.ResolvingServer -> R.string.vpn_step_resolving
            ConnectStep.StartingEngine -> R.string.vpn_step_engine
            ConnectStep.EstablishingTunnel -> R.string.vpn_step_tunnel
            ConnectStep.VerifyingTraffic -> R.string.vpn_step_verifying
        }
    }
}

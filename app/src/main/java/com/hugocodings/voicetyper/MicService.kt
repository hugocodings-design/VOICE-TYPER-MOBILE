package com.hugocodings.voicetyper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Short-lived foreground service (type microphone) kept only while dictating, so Android lets the mic
 * be used while another app is in front. Best effort: if Android refuses to start it, dictation is
 * still attempted.
 */
class MicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm: NotificationManager = getSystemService(NotificationManager::class.java)!!
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.mic_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.mic_notification))
            .setOngoing(true)
            .build()
        runCatching {
            ServiceCompat.startForeground(this, 1, notification,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        }.onSuccess { DiagnosticLog.add("Service micro (premier plan) démarré") }
            .onFailure { DiagnosticLog.add("Service micro refusé : ${it.javaClass.simpleName} ${it.message.orEmpty().take(120)}"); stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "dictation"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MicService::class.java)) }
                .onFailure { DiagnosticLog.add("Service micro non lancé : ${it.javaClass.simpleName} ${it.message.orEmpty().take(120)}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MicService::class.java)) }
        }
    }
}

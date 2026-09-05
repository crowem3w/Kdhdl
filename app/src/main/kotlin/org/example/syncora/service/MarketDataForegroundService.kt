package org.example.syncora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.example.syncora.MainActivity
import org.example.syncora.R
import org.example.syncora.SyncoraApplication
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PaperTradingConnectionState
import org.example.syncora.bitget.PipelineState
import java.util.Locale

class MarketDataForegroundService : Service() {

    private val app by lazy { application as SyncoraApplication }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(statusText = getString(R.string.market_data_notification_status_starting)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        app.ensureMarketDataStarted()

        observeStatusForNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        scope.cancel()
        app.stopMarketData()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeStatusForNotification() {
        notificationJob = combine(
            app.pipeline.pipelineState,
            app.liveTradingRepository.connectionState,
            app.liveTradingRepository.positions,
        ) { pipelineState, connectionState, positions ->
            statusText(pipelineState, connectionState, positions)
        }
            .onEach { statusText -> updateNotification(statusText) }
            .launchIn(scope)
    }

    private fun statusText(
        pipelineState: PipelineState,
        connectionState: PaperTradingConnectionState,
        positions: List<PaperPosition>,
    ): String {
        val marketDataPart = when (pipelineState) {
            PipelineState.LIVE -> getString(R.string.market_data_notification_market_data_live)
            PipelineState.STOPPED, PipelineState.IDLE -> getString(R.string.market_data_notification_market_data_offline)
            else -> getString(R.string.market_data_notification_market_data_connecting)
        }
        val positionPart = when {
            connectionState != PaperTradingConnectionState.LIVE ->
                getString(R.string.market_data_notification_position_unknown)
            positions.isEmpty() -> getString(R.string.market_data_notification_position_flat)
            else -> positions.joinToString(", ") { position ->
                val side = position.side.name.lowercase(Locale.US)
                val size = String.format(Locale.US, "%.4f", position.total).trimEnd('0').trimEnd('.')
                "$side $size BTC"
            }
        }
        return getString(R.string.market_data_notification_status_format, marketDataPart, positionPart)
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun buildNotification(statusText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MarketDataForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_status)
            .setContentTitle(getString(R.string.market_data_notification_title))
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.market_data_notification_stop_action), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.market_data_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.market_data_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "market_data_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "org.example.syncora.service.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, MarketDataForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MarketDataForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}

package dev.chimeraant.berryforge.mcp

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
import dev.chimeraant.berryforge.BerryForgeApp
import dev.chimeraant.berryforge.MainActivity
import dev.chimeraant.berryforge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the MCP server and its tunnel alive while a remote agent is connected.
 *
 * Runs as a `dataSync` foreground service so Android does not freeze the socket when the
 * app is backgrounded. The notification shows the live public endpoint, which is also the
 * fastest way for the user to copy it into OpenHands without reopening the app.
 */
class McpTunnelService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Actually shut the endpoint down. The previous version only called
                // stopSelf(), which removed the notification but left the HTTP server
                // and the tunnel running — the endpoint stayed publicly reachable with
                // no visible indication.
                val container = (application as BerryForgeApp).container
                runCatching { container.tunnels.stop() }
                runCatching { container.mcpHttpServer.stop() }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForegroundCompat()
        }
        observeTunnel()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(publicUrl = null, localUrl = null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeTunnel() {
        if (observeJob?.isActive == true) return
        val container = (application as BerryForgeApp).container
        observeJob = scope.launch {
            container.tunnels.state.collectLatest { state ->
                val publicUrl = (state as? TunnelState.Up)?.publicUrl
                val localUrl = (state as? TunnelState.Up)?.localUrl
                notify(buildNotification(publicUrl, localUrl))
            }
        }
    }

    private fun buildNotification(publicUrl: String?, localUrl: String?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, McpTunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = if (publicUrl != null) "MCP endpoint live" else "Starting MCP endpoint"
        val text = publicUrl ?: "Waiting for the tunnel to come up…"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_berryforge)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                buildString {
                    appendLine(text)
                    if (localUrl != null) appendLine("Local: $localUrl")
                    appendLine("Paste the HTTPS endpoint into OpenHands.")
                }.trim(),
            ))
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mcp_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.mcp_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Android 15 caps dataSync foreground services at six hours and then calls this.
     *
     * Without an override the platform throws and the process is killed. The endpoint is
     * shut down cleanly instead, and the notification says why, so the user is not left
     * wondering why the tunnel vanished.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val container = (application as BerryForgeApp).container
        runCatching { container.tunnels.stop() }
        runCatching { container.mcpHttpServer.stop() }
        runCatching { container.sessions.log(
            kind = "session",
            title = "MCP endpoint stopped",
            detail = "Android's foreground service time limit was reached. Start it again to reconnect.",
            severity = "warn",
        ) }
        stopSelf()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "berryforge_mcp"
        const val NOTIFICATION_ID = 4210
        const val ACTION_STOP = "dev.chimeraant.berryforge.STOP_MCP"

        fun start(context: Context) {
            val intent = Intent(context, McpTunnelService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, McpTunnelService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

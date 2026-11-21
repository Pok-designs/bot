package dev.pok.herd.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.pok.herd.R
import dev.pok.herd.ui.MainActivity

/**
 * Foreground service that keeps the app process — and so every pane's shell — alive while the UI
 * is closed. Also posts "needs attention" notifications when a pane becomes blocked or finishes
 * unseen, so you never have to hunt for the stuck one.
 */
class HerdService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val herd = Herd.get(this)
        ensureChannels(this)
        val n = ongoing(this, herd)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ONGOING_ID, n)
        }
        return START_STICKY
    }

    /** "Stop server" action on the ongoing notification. */
    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Herd.get(context).stopServer()
        }
    }

    companion object {
        private const val CH_SERVER = "server"
        private const val CH_ATTENTION = "attention"
        private const val ONGOING_ID = 1
        const val EXTRA_PANE = "dev.pok.herd.PANE"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, HerdService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background on newer Android; the next foreground start will do it.
            }
        }

        private fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_SERVER, "Server", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while panes are kept running in the background"
            })
            nm.createNotificationChannel(NotificationChannel(CH_ATTENTION, "Needs attention", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A pane is blocked waiting for input, or finished while you were away"
            })
        }

        private fun openIntent(ctx: Context, paneId: Int?, req: Int): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (paneId != null) i.putExtra(EXTRA_PANE, paneId)
            return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun ongoing(ctx: Context, herd: Herd): Notification {
            val panes = herd.panes.values
            val counts = panes.groupingBy { it.state }.eachCount()
            val summary = buildList {
                add("${panes.size} pane${if (panes.size == 1) "" else "s"}")
                PaneState.entries.reversed().forEach { s -> counts[s]?.takeIf { it > 0 }?.let { add("$it ${s.label}") } }
            }.joinToString(" · ")
            val stop = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CH_SERVER)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("herd server running")
                .setContentText(summary)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openIntent(ctx, null, 0))
                .addAction(Notification.Action.Builder(null, "Stop server", stop).build())
                .build()
        }

        /** Updates the ongoing notification's summary. */
        fun refresh(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.activeNotifications.none { it.id == ONGOING_ID }) return
            nm.notify(ONGOING_ID, ongoing(ctx, Herd.get(ctx)))
        }

        fun notifyAttention(ctx: Context, pane: Pane, state: PaneState) {
            ensureChannels(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val text = pane.tail(1).take(120)
            val n = Notification.Builder(ctx, CH_ATTENTION)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("${pane.ref} · ${pane.title} is ${state.label}")
                .setContentText(text)
                .setColor(state.color)
                .setAutoCancel(true)
                .setContentIntent(openIntent(ctx, pane.id, 100 + pane.id))
                .build()
            nm.notify(100 + pane.id, n)
        }

        /** `herdr notification show`. */
        fun notifyCustom(ctx: Context, title: String, body: String?, paneId: Int) {
            ensureChannels(ctx)
            val n = Notification.Builder(ctx, CH_ATTENTION)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(body ?: "")
                .setAutoCancel(true)
                .setContentIntent(openIntent(ctx, paneId, 1000 + paneId))
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(1000 + paneId, n)
        }

        fun clearAttention(ctx: Context, paneId: Int) {
            ctx.getSystemService(NotificationManager::class.java).cancel(100 + paneId)
        }
    }
}

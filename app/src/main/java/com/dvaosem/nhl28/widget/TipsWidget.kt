package com.dvaosem.nhl28.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import com.dvaosem.nhl28.R
import com.dvaosem.nhl28.ui.MainActivity

/** Scrollable list of the day's games; tap a team to tip (queued, sent when the app is opened). */
class TipsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) update(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TIP) {
            val gameId = intent.getStringExtra(EXTRA_GAME) ?: return
            val pick = intent.getStringExtra(EXTRA_PICK) ?: return
            WidgetStore.queueTip(context, gameId, pick)
            refreshAll(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_TIP = "com.dvaosem.nhl28.TIP"
        const val EXTRA_GAME = "game"
        const val EXTRA_PICK = "pick"

        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TipsWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) update(context, mgr, id)
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
        }

        private fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val th = WidgetStore.theme(context)
            val games = WidgetStore.games(context)
            val v = RemoteViews(context.packageName, R.layout.widget_tips)

            v.setInt(R.id.w_bg, "setColorFilter", th.bg)
            v.setInt(R.id.w_bg, "setImageAlpha", 240)
            val label = WidgetStore.label(context)
            v.setTextViewText(R.id.w_title, if (label.isBlank()) "NHL28" else "NHL28 · $label")
            v.setTextColor(R.id.w_title, th.acc)
            val tipped = games.count { it.pick.isNotEmpty() }
            v.setTextViewText(R.id.w_count, if (games.isEmpty()) "" else "$tipped/${games.size}")
            v.setTextColor(R.id.w_count, th.dim)
            v.setTextColor(R.id.w_empty, th.dim)

            val svc = Intent(context, TipsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.w_list, svc)
            v.setEmptyView(R.id.w_list, R.id.w_empty)

            val tipIntent = Intent(context, TipsWidget::class.java).setAction(ACTION_TIP)
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            v.setPendingIntentTemplate(
                R.id.w_list,
                PendingIntent.getBroadcast(context, 0, tipIntent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
            )

            val open = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.w_title, open)
            v.setOnClickPendingIntent(R.id.w_empty, open)

            mgr.updateAppWidget(id, v)
        }
    }
}

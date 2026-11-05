package com.dvaosem.nhl28.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.dvaosem.nhl28.R
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Latest chat messages, newest first (read-only; tap opens the chat tab). */
class ChatWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) update(context, appWidgetManager, id)
    }

    companion object {
        fun items(c: Context): JSONArray = WidgetStore.kind(c, "chat")?.optJSONArray("items") ?: JSONArray()

        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ChatWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) update(context, mgr, id)
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
        }

        private fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val th = WidgetStore.theme(context)
            val unread = WidgetStore.kind(context, "chat")?.optInt("unread") ?: 0
            val v = WidgetUtil.frame(
                context, th, "NHL28 · Chat",
                if (unread > 0) "$unread nových" else "", "Zatiaľ žiadne správy. Otvor NHL28."
            )
            val svc = Intent(context, ChatService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.w_list, svc)
            v.setEmptyView(R.id.w_list, R.id.w_empty)
            v.setPendingIntentTemplate(R.id.w_list, WidgetUtil.template(context, 30, "chat"))
            v.setOnClickPendingIntent(R.id.w_title, WidgetUtil.open(context, 31, "chat"))
            v.setOnClickPendingIntent(R.id.w_empty, WidgetUtil.open(context, 31, "chat"))
            mgr.updateAppWidget(id, v)
        }
    }
}

class ChatService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val ctx: Context) : RemoteViewsFactory {
        var rows: JSONArray = JSONArray()
        private val timeFmt = SimpleDateFormat("HH:mm", Locale("sk"))
        private val dayFmt = SimpleDateFormat("d. M.", Locale("sk"))

        private fun load() { rows = ChatWidget.items(ctx) }
        override fun onCreate() = load()
        override fun onDataSetChanged() = load()
        override fun onDestroy() {}
        override fun getCount() = rows.length()
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView(): RemoteViews? = null

        private fun sameDay(a: Long, b: Long): Boolean {
            val x = Calendar.getInstance().apply { timeInMillis = a }
            val y = Calendar.getInstance().apply { timeInMillis = b }
            return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
        }

        override fun getViewAt(pos: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_line_row)
            val o = rows.optJSONObject(pos) ?: return v
            val th = WidgetStore.theme(ctx)
            val ts = o.optLong("ts")
            val mine = o.optBoolean("mine")

            v.setInt(R.id.rl_bg, "setColorFilter", th.card)
            v.setTextViewText(R.id.rl_main, o.optString("text"))
            v.setTextColor(R.id.rl_main, th.text)
            v.setTextViewText(R.id.rl_sub, if (mine) "ty" else o.optString("name"))
            v.setTextColor(R.id.rl_sub, if (mine) th.acc else th.dim)
            val now = System.currentTimeMillis()
            v.setTextViewText(R.id.rl_mark, if (sameDay(ts, now)) timeFmt.format(Date(ts)) else dayFmt.format(Date(ts)))
            v.setTextColor(R.id.rl_mark, th.dim)
            v.setInt(R.id.rl_mark, "setMaxLines", 1)
            v.setOnClickFillInIntent(R.id.rl_root, Intent())
            return v
        }
    }
}

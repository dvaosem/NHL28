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
import java.util.Date
import java.util.Locale

/** Last finished games with the score and whether your tip was right. */
class ResultsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) update(context, appWidgetManager, id)
    }

    companion object {
        fun items(c: Context): JSONArray = WidgetStore.kind(c, "results")?.optJSONArray("items") ?: JSONArray()

        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ResultsWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) update(context, mgr, id)
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
        }

        private fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val th = WidgetStore.theme(context)
            val a = items(context)
            var ok = 0
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("pick").isNotEmpty() && o.optString("pick") == o.optString("winner")) ok++
            }
            val v = WidgetUtil.frame(
                context, th, "NHL28 · Výsledky",
                if (a.length() == 0) "" else "✓ $ok/${a.length()}", "Zatiaľ žiadne výsledky. Otvor NHL28."
            )
            val svc = Intent(context, ResultsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.w_list, svc)
            v.setEmptyView(R.id.w_list, R.id.w_empty)
            v.setPendingIntentTemplate(R.id.w_list, WidgetUtil.template(context, 20, null))
            v.setOnClickPendingIntent(R.id.w_title, WidgetUtil.open(context, 21, null))
            v.setOnClickPendingIntent(R.id.w_empty, WidgetUtil.open(context, 21, null))
            mgr.updateAppWidget(id, v)
        }
    }
}

class ResultsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val ctx: Context) : RemoteViewsFactory {
        var rows: JSONArray = JSONArray()
        private val fmt = SimpleDateFormat("d. M.", Locale("sk"))

        override fun onCreate() { rows = ResultsWidget.items(ctx) }
        override fun onDataSetChanged() { rows = ResultsWidget.items(ctx) }
        override fun onDestroy() {}
        override fun getCount() = rows.length()
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(pos: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_line_row)
            val o = rows.optJSONObject(pos) ?: return v
            val th = WidgetStore.theme(ctx)

            val away = o.optString("away")
            val awayName = o.optString("awayName").ifBlank { away }
            val homeName = o.optString("homeName").ifBlank { o.optString("home") }
            val pick = o.optString("pick")
            val winner = o.optString("winner")
            val pickName = when (pick) {
                "" -> "bez tipu"
                away -> awayName
                else -> homeName
            }

            v.setInt(R.id.rl_bg, "setColorFilter", th.card)
            v.setTextViewText(R.id.rl_main, "$awayName ${o.optInt("awayScore")} : ${o.optInt("homeScore")} $homeName")
            v.setTextColor(R.id.rl_main, th.text)
            v.setTextViewText(R.id.rl_sub, "tip: $pickName · ${fmt.format(Date(o.optLong("kickoff")))}")
            v.setTextColor(R.id.rl_sub, th.dim)

            val (mark, color) = when {
                pick.isEmpty() -> "–" to th.dim
                pick == winner -> "✓" to 0xFF9DB38A.toInt()
                else -> "✗" to 0xFFD98B7C.toInt()
            }
            v.setTextViewText(R.id.rl_mark, mark)
            v.setTextColor(R.id.rl_mark, color)
            v.setOnClickFillInIntent(R.id.rl_root, Intent())
            return v
        }
    }
}

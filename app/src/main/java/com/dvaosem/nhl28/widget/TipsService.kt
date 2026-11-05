package com.dvaosem.nhl28.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.dvaosem.nhl28.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TipsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val ctx: Context) : RemoteViewsFactory {
        var rows: List<WidgetStore.Game> = emptyList()
        var pending: Set<String> = emptySet()
        private val fmt = SimpleDateFormat("HH:mm", Locale("sk"))

        private fun load() {
            rows = WidgetStore.games(ctx).sortedBy { it.kickoff }
            pending = WidgetStore.pendingIds(ctx)
        }

        override fun onCreate() = load()
        override fun onDataSetChanged() = load()
        override fun onDestroy() {}
        override fun getCount() = rows.size
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(pos: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_tip_row)
            val g = rows.getOrNull(pos) ?: return v
            val th = WidgetStore.theme(ctx)

            v.setInt(R.id.r_bg, "setColorFilter", th.card)

            v.setTextViewText(R.id.r_time, fmt.format(Date(g.kickoff)))
            v.setTextColor(R.id.r_time, th.dim)
            val state = when {
                g.id in pending -> "čaká na odoslanie"
                g.status == "final" -> "koniec"
                !g.open -> "uzavreté"
                else -> ""
            }
            v.setTextViewText(R.id.r_state, state)
            v.setTextColor(R.id.r_state, if (g.id in pending) th.acc else th.dim)

            bindTeam(v, g, g.away, g.awayName, R.id.r_a, R.id.r_a_bg, R.id.r_a_t, th)
            bindTeam(v, g, g.home, g.homeName, R.id.r_h, R.id.r_h_bg, R.id.r_h_t, th)
            return v
        }

        private fun bindTeam(
            v: RemoteViews, g: WidgetStore.Game, abbr: String, name: String,
            boxId: Int, bgId: Int, txtId: Int, th: WidgetStore.Theme
        ) {
            val picked = g.pick == abbr
            v.setTextViewText(txtId, name)
            v.setInt(bgId, "setColorFilter", if (picked) th.acc else th.bg)
            v.setInt(bgId, "setImageAlpha", if (g.open || picked) 255 else 140)
            v.setTextColor(txtId, if (picked) th.bg else th.text)
            if (g.open) {
                val fill = Intent()
                    .putExtra(TipsWidget.EXTRA_GAME, g.id)
                    .putExtra(TipsWidget.EXTRA_PICK, abbr)
                v.setOnClickFillInIntent(boxId, fill)
            }
        }
    }
}

package com.dvaosem.nhl28.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.widget.RemoteViews
import com.dvaosem.nhl28.R
import org.json.JSONArray

/** Standings as a horizontal bar chart drawn to a bitmap (follows the widget size and the app theme). */
class BoardWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) update(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, mgr, id)
    }

    companion object {
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            for (id in mgr.getAppWidgetIds(ComponentName(context, BoardWidget::class.java))) update(context, mgr, id)
        }

        private fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val th = WidgetStore.theme(context)
            val rows = WidgetStore.kind(context, "board")?.optJSONArray("rows") ?: JSONArray()
            val d = context.resources.displayMetrics.density
            val opt = mgr.getAppWidgetOptions(id)
            val wDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(120)
            val hDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180).coerceAtLeast(90)
            val w = ((wDp - 20) * d).toInt().coerceIn(200, 900)
            val h = ((hDp - 50) * d).toInt().coerceIn(100, 900)

            val v = RemoteViews(context.packageName, R.layout.widget_board)
            v.setInt(R.id.b_bg, "setColorFilter", th.bg)
            v.setInt(R.id.b_bg, "setImageAlpha", 240)
            v.setTextViewText(R.id.b_title, "NHL28 · Poradie")
            v.setTextColor(R.id.b_title, th.acc)
            if (rows.length() > 0) v.setImageViewBitmap(R.id.b_img, draw(rows, th, w, h, d))
            else v.setImageViewBitmap(R.id.b_img, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
            v.setOnClickPendingIntent(R.id.b_root, WidgetUtil.open(context, 40, null))
            mgr.updateAppWidget(id, v)
        }

        private fun draw(rows: JSONArray, th: WidgetStore.Theme, w: Int, h: Int, d: Float): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val rowH = 30f * d
            val n = minOf(rows.length(), maxOf(1, (h / rowH).toInt()))
            var maxPts = 1
            for (i in 0 until n) maxPts = maxOf(maxPts, rows.getJSONObject(i).optInt("points"))

            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.text; textSize = 13f * d }
            val pts = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.dim; textSize = 12f * d }
            val bar = Paint(Paint.ANTI_ALIAS_FLAG)

            var nameW = 0f
            for (i in 0 until n) nameW = maxOf(nameW, text.measureText(rows.getJSONObject(i).optString("name").take(12)))
            nameW = minOf(nameW, w * 0.34f)
            val x0 = nameW + 8f * d
            val ptsW = pts.measureText("999 b") + 6f * d
            val barMax = (w - x0 - ptsW).coerceAtLeast(20f * d)

            for (i in 0 until n) {
                val o = rows.getJSONObject(i)
                val y = i * rowH
                val name = o.optString("name").take(12)
                val p = o.optInt("points")
                val mine = o.optBoolean("mine")
                text.color = if (mine) th.acc else th.text
                cv.drawText(name, 0f, y + rowH * 0.66f, text)
                val len = maxOf(4f * d, barMax * p / maxPts)
                bar.color = if (mine) th.acc else Color.argb(150, Color.red(th.acc), Color.green(th.acc), Color.blue(th.acc))
                cv.drawRoundRect(RectF(x0, y + rowH * 0.2f, x0 + len, y + rowH * 0.8f), 4f * d, 4f * d, bar)
                cv.drawText("$p b", x0 + len + 6f * d, y + rowH * 0.66f, pts)
            }
            return bmp
        }
    }
}

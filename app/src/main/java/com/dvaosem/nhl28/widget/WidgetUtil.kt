package com.dvaosem.nhl28.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.dvaosem.nhl28.R
import com.dvaosem.nhl28.ui.MainActivity

object WidgetUtil {
    /** Opens the app (optionally on the chat tab). */
    fun open(ctx: Context, req: Int, extra: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
        if (extra != null) i.putExtra("open", extra)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Template for list rows (each row only adds an empty fill-in intent). */
    fun template(ctx: Context, req: Int, extra: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
        if (extra != null) i.putExtra("open", extra)
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    /** Common frame of the list-style widgets (reuses the widget_tips layout). */
    fun frame(ctx: Context, th: WidgetStore.Theme, title: String, count: String, empty: String): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_tips)
        v.setInt(R.id.w_bg, "setColorFilter", th.bg)
        v.setInt(R.id.w_bg, "setImageAlpha", 240)
        v.setTextViewText(R.id.w_title, title)
        v.setTextColor(R.id.w_title, th.acc)
        v.setTextViewText(R.id.w_count, count)
        v.setTextColor(R.id.w_count, th.dim)
        v.setTextViewText(R.id.w_empty, empty)
        v.setTextColor(R.id.w_empty, th.dim)
        return v
    }
}

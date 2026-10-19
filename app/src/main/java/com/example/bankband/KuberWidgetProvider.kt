package com.example.bankband

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home-screen overview widget. Values are read from Kuber's existing local snapshot. */
class KuberWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateOne(context, manager, it) }
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, KuberWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateOne(context, manager, it) }
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            // Always publish a valid RemoteViews first. If local finance data is malformed,
            // the launcher still gets a usable widget instead of showing "Can't load widget".
            val views = RemoteViews(context.packageName, R.layout.widget_kuber)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            try {
                val holdings = Prefs.str(context, "sec_hold").trim()
                val investmentText = if (holdings.isBlank()) {
                    "No holdings configured"
                } else {
                    holdings.lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.equals("HOLDINGS", true) && !it.matches(Regex("[-_=]{3,}")) }
                        .take(3).joinToString("\n").take(100)
                        .ifBlank { "No holdings configured" }
                }
                views.setTextViewText(R.id.widget_investments, investmentText)

                val spend = Spends.monthRows(context).firstOrNull()
                views.setTextViewText(
                    R.id.widget_spends,
                    if (spend != null) "₹${inr(kotlin.math.abs(spend.second))}" else "₹ —"
                )
                val financeAt = Prefs.time(context, "financeUpdatedAt")
                val spendAt = Prefs.time(context, "spendUpdatedAt")
                val updatedAt = maxOf(financeAt, spendAt)
                val updated = if (updatedAt == 0L) "Awaiting first refresh" else
                    "Updated ${SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(updatedAt))}"
                views.setTextViewText(R.id.widget_updated, updated)
            } catch (_: Exception) {
                // Keep the widget renderable even if preferences or stored transaction data fail.
                views.setTextViewText(R.id.widget_investments, "Open Kuber to load investments")
                views.setTextViewText(R.id.widget_spends, "₹ —")
                views.setTextViewText(R.id.widget_updated, "Tap to open Kuber")
            }
            manager.updateAppWidget(id, views)
        }
    }
}

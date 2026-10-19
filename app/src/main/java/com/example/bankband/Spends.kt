package com.example.bankband

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Stores parsed debit transactions on the phone and builds the month-wise Spends text. */
object Spends {
    private fun file(c: Context) = File(c.filesDir, "spends.json")

    @Synchronized
    fun load(c: Context): MutableList<Txn> = try {
        val a = JSONArray(file(c).readText())
        MutableList(a.length()) { i ->
            val o = a.getJSONObject(i)
            Txn(o.getLong("ts"), o.getDouble("amt"), o.optString("acct"), o.optString("ref"),
                o.optString("snd"), o.optString("sig"), o.optString("m"), o.optBoolean("x"),
                o.optBoolean("cr"), o.optBoolean("sal"))
        }
    } catch (_: Exception) { mutableListOf() }

    @Synchronized
    private fun save(c: Context, l: List<Txn>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("ts", it.ts).put("amt", it.amt).put("acct", it.acct).put("ref", it.ref)
                .put("snd", it.sender).put("sig", it.sig).put("m", it.merchant).put("x", it.excluded)
                .put("cr", it.credit).put("sal", it.salary))
        }
        file(c).writeText(a.toString())
    }

    @Synchronized
    private fun add(c: Context, items: List<Txn>) {
        if (items.isEmpty()) return
        save(c, SpendParser.dedupe(load(c) + items))
    }

    /** Called for every incoming SMS. */
    fun onSms(c: Context, from: String, body: String, ts: Long): Boolean = try {
        SpendParser.parse(from, body, ts, salaryKeys(c), excludeKeys(c))?.let { add(c, listOf(it)); true } ?: false
    } catch (_: Exception) { false }

    fun salaryKeys(c: Context): List<String> =
        Prefs.str(c, "salary", "NTT DA").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** SMS containing any of these words are never counted (default: Policy). */
    fun excludeKeys(c: Context): List<String> =
        Prefs.str(c, "exclkw", "Policy").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun granted(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Reads the SMS inbox. full = true clears the stored list and rescans (excluded entries stay excluded). */
    @Synchronized
    fun refresh(c: Context, full: Boolean): Int {
        if (!granted(c)) return 0
        val now = System.currentTimeMillis()
        val excl = if (full) load(c).filter { it.excluded }.map { it.key }.toSet() else emptySet()
        val since = if (full || Prefs.time(c, "spScan") == 0L) now - 400L * 86_400_000L
        else Prefs.time(c, "spScan")
        val found = ArrayList<Txn>()
        val keys = salaryKeys(c)
        val exKeys = excludeKeys(c)
        var maxDate = since
        c.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body", "date"),
            "date > ?", arrayOf(since.toString()), "date ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val d = cur.getLong(2)
                if (d > maxDate) maxDate = d
                SpendParser.parse(cur.getString(0) ?: "", cur.getString(1) ?: "", d, keys, exKeys)?.let { found.add(it) }
            }
        }
        if (full) save(c, emptyList())
        add(c, found.map { if (it.key in excl) it.copy(excluded = true) else it })
        if (full && excl.isNotEmpty()) save(c, load(c).map { if (it.key in excl) it.copy(excluded = true) else it })
        Prefs.ed(c).putLong("spScan", maxDate).apply()
        return found.size
    }

    @Synchronized
    fun setExcluded(c: Context, key: String, ex: Boolean) {
        save(c, load(c).map { if (it.key == key) it.copy(excluded = ex) else it })
    }

    private fun short(v: Double) = when {
        v >= 1e7 -> "%.2fCr".format(Locale.US, v / 1e7)
        v >= 1e5 -> "%.2fL".format(Locale.US, v / 1e5)
        else -> inr(v)
    }

    fun monthRows(c: Context): List<Pair<String, Double>> {
        val totals = SpendParser.monthTotals(load(c))
        val cal = Calendar.getInstance()
        val mf = SimpleDateFormat("MMM", Locale.US)
        val out = ArrayList<Pair<String, Double>>()
        for (k in 0..5) {
            val v = totals[SpendParser.monthKey(cal.timeInMillis)] ?: 0.0
            if (k == 0 || v > 0) out.add(mf.format(cal.time) to v)
            cal.add(Calendar.MONTH, -1)
        }
        return out
    }

    /** Notification text; every row stays within 15 characters. */
    fun board(c: Context): String {
        if (!granted(c)) return "SPENDS\n---------------\nSMS access off"
        val rows = monthRows(c)
        val sb = StringBuilder("SPENDS\n---------------\n")
        rows.forEach { (m, v) ->
            val neg = if (v < 0) "-" else ""
            val full = neg + "₹" + inr(Math.abs(v))
            sb.append(fit(m, if ("$m $full".length <= 15) full else neg + "₹" + short(Math.abs(v)))).append('\n')
        }
        val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        sb.append("---------------\n")
        sb.append(fit("PER DAY", sgn(rows.first().second / day, false)))
        return sb.toString()
    }
}

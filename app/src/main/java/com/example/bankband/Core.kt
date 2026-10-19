package com.example.bankband

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object Prefs {
    private val NAMES = listOf("Canara")
    private val SENDERS = listOf("CANBNK")
    private val MODES = listOf("call")
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun str(c: Context, k: String, d: String = "") = sp(c).getString(k, d) ?: d
    fun num(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun flag(c: Context, k: String, d: Boolean = false) = sp(c).getBoolean(k, d)
    fun time(c: Context, k: String) = sp(c).getLong(k, 0L)
    fun ed(c: Context): SharedPreferences.Editor = sp(c).edit()
    fun name(c: Context, i: Int) = str(c, "n$i", NAMES[i - 1])
    fun sender(c: Context, i: Int) = str(c, "snd$i", SENDERS[i - 1])
    fun mode(c: Context, i: Int) = str(c, "mode$i", MODES[i - 1])
    /** all items on by default except Ethereum */
    fun item(c: Context, k: String) = flag(c, k, k != "eth")
}

fun inr(v: Double, dec: Int = 0): String {
    val s = "%.${dec}f".format(Locale.US, v)
    val neg = s.startsWith("-"); val t = s.removePrefix("-")
    val ip = t.substringBefore('.')
    val fp = if (t.contains('.')) "." + t.substringAfter('.') else ""
    val g = if (ip.length <= 3) ip else
        ip.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + ip.takeLast(3)
    return (if (neg) "-" else "") + g + fp
}

/** Label + value on one row if it fits in 15 characters, otherwise on two rows. */
fun fit(label: String, value: String) =
    if ("$label $value".length <= 15) "$label $value" else "$label\n$value"

/** Signed rupee amount, e.g. -₹1,200 or +₹800. */
fun sgn(v: Double, plus: Boolean = true): String =
    (if (v < 0) "-" else if (plus) "+" else "") + "₹" + inr(Math.abs(v))

object Notifs {
    private const val CH = "kuber"
    const val PROMPT = 1
    const val BANK = 2; const val HOLD = 3; const val MARKET = 4; const val SPEND = 7
    private const val EXPIRE_MS = 12L * 60 * 60 * 1000
    private const val D = "---------------"

    private fun nm(c: Context): NotificationManager {
        val n = c.getSystemService(NotificationManager::class.java)
        try { n.deleteNotificationChannel("bankband") } catch (_: Exception) {}
        n.createNotificationChannel(NotificationChannel(CH, "Kuber", NotificationManager.IMPORTANCE_HIGH))
        return n
    }
    private fun post(c: Context, id: Int, n: Notification) { try { nm(c).notify(id, n) } catch (_: Exception) {} }

    /** Chat-style notification so the band treats it like WhatsApp (shows Reply + quick replies). */
    fun prompt(c: Context) {
        val ri = RemoteInput.Builder("r").setLabel("Reply").build()
        val pi = PendingIntent.getBroadcast(
            c, 0, Intent(c, ReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val action = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", pi)
            .addRemoteInput(ri).setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false).build()
        // plain button fallback: no typing needed, just runs everything
        val runPi = PendingIntent.getBroadcast(
            c, 2, Intent(c, ReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val me = Person.Builder().setName("You").build()
        val bot = Person.Builder().setName("Kuber").build()
        val style = NotificationCompat.MessagingStyle(me)
            .addMessage("Refresh? Reply Yes", System.currentTimeMillis(), bot)
        post(c, PROMPT, NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_kuber)
            .setStyle(style)
            .addAction(action)
            .addAction(android.R.drawable.ic_popup_sync, "Run now", runPi)
            .addAction(android.R.drawable.ic_btn_speak_now, "Assistant", assistPi(c, 3))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).build())
    }

    private fun assistPi(c: Context, code: Int) = PendingIntent.getActivity(
        c, code, Intent(c, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Tap-to-open fallback, used when Android blocks launching the assistant straight from a reply. */
    fun assistTap(c: Context) = post(c, 8, NotificationCompat.Builder(c, CH)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Voice assistant").setContentText("Tap to talk")
        .setContentIntent(assistPi(c, 4)).setAutoCancel(true)
        .setTimeoutAfter(EXPIRE_MS).setPriority(NotificationCompat.PRIORITY_HIGH).build())

    fun bankText(c: Context): String {
        if (Prefs.str(c, "num1").isBlank()) return ""
        val b = Prefs.str(c, "bal1").toDoubleOrNull()
        return "BANK BALANCE\n$D\n" + Prefs.name(c, 1).take(15) + "\n" +
                (if (b == null) "waiting..." else "₹" + inr(b, 2))
    }

    /** One notification per block. With a title, the first line is the title; otherwise everything is body. */
    private fun section(c: Context, id: Int, text: String, titled: Boolean = true) {
        val t = text.trim()
        if (t.isEmpty()) { try { nm(c).cancel(id) } catch (_: Exception) {}; return }
        val lines = t.lines()
        val body = (if (titled) lines.drop(1) else lines).joinToString("\n").trim()
        val b = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_kuber)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setTimeoutAfter(EXPIRE_MS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (titled) {
            b.setContentTitle(lines.first())
            b.setContentText(lines.drop(1).firstOrNull { it.isNotBlank() && !it.startsWith("--") } ?: "")
        } else b.setContentText(body)
        post(c, id, b.build())
    }

    fun spends(c: Context) {
        if (Prefs.flag(c, "notifySpend", true)) section(c, SPEND, Spends.board(c))
        else try { nm(c).cancel(SPEND) } catch (_: Exception) {}
    }

    fun finance(c: Context) {
        if (Prefs.flag(c, "notifyFinance", true)) {
            val market = listOf("sec_hold", "sec_idx", "sec_metal", "sec_crypto")
                .map { Prefs.str(c, it).trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            section(c, HOLD, market, false)
        } else try { nm(c).cancel(HOLD) } catch (_: Exception) {}
    }

    /** Cancels our old notifications (including ones from older versions), then posts fresh ones. */
    fun all(c: Context) {
        try { for (id in 2..9) nm(c).cancel(id) } catch (_: Exception) {}
        if (Prefs.flag(c, "notifyBank", true)) section(c, BANK, bankText(c))
        finance(c)
        spends(c)
    }

    fun bank(c: Context) {
        if (Prefs.flag(c, "notifyBank", true)) section(c, BANK, bankText(c))
        else try { nm(c).cancel(BANK) } catch (_: Exception) {}
    }
}

object Pipeline {
    const val ALL = 0; const val BAL = 1; const val PRICES = 2; const val SPEND = 3
    private const val D = "---------------"

    data class Q(val price: Double, val prev: Double) {
        val pct get() = if (prev != 0.0) (price - prev) / prev * 100 else 0.0
    }
    data class H(val sym: String, val name: String, val qty: Double, val avg: Double)

    fun fetch(sym: String): Q? = try {
        val u = URL("https://query1.finance.yahoo.com/v8/finance/chart/" +
                URLEncoder.encode(sym, "UTF-8") + "?interval=1d&range=1d")
        val con = u.openConnection() as HttpURLConnection
        con.setRequestProperty("User-Agent", "Mozilla/5.0")
        con.connectTimeout = 6000; con.readTimeout = 6000
        val m = JSONObject(con.inputStream.bufferedReader().readText())
            .getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
        val p = m.getDouble("regularMarketPrice")
        Q(p, m.optDouble("chartPreviousClose", p))
    } catch (e: Exception) { null }

    /** Fetch all symbols in parallel. */
    fun fetchAll(syms: List<String>): Map<String, Q> {
        val res = ConcurrentHashMap<String, Q>()
        syms.distinct().map { s -> Thread { fetch(s)?.let { res[s] = it } }.also { it.start() } }
            .forEach { it.join(8000) }
        return res
    }

    fun parseHoldings(s: String): List<H> = s.lines().mapNotNull { l ->
        val t = l.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val q = t.getOrNull(1)?.toDoubleOrNull(); val a = t.getOrNull(2)?.toDoubleOrNull()
        if (t.size < 3 || q == null || a == null) null
        else H(t[0].uppercase(), (t.getOrNull(3) ?: t[0].substringBefore('.')).uppercase(), q, a)
    }

    private fun handle(c: Context, tm: TelecomManager, sub: Int): PhoneAccountHandle? {
        if (sub < 0) return null
        val icc = try { c.getSystemService(SubscriptionManager::class.java)
            .getActiveSubscriptionInfo(sub)?.iccId ?: "" } catch (_: Exception) { "" }
        return tm.callCapablePhoneAccounts.firstOrNull {
            it.id == sub.toString() || (icc.length >= 10 && it.id.startsWith(icc.take(10)))
        }
    }

    fun sendSms(c: Context, i: Int) {
        val number = Prefs.str(c, "num$i")
        if (number.isBlank()) return
        val sub = Prefs.num(c, "sub$i", -1)
        val sm: SmsManager = when {
            sub >= 0 -> @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(sub)
            Build.VERSION.SDK_INT >= 31 -> c.getSystemService(SmsManager::class.java)
            else -> @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        sm.sendTextMessage(number, null, Prefs.str(c, "txt$i", "BAL"), null, null)
    }

    /** Dials, waits ~4.5s, hangs up, waits for the line to clear. Blocks until done. */
    fun missedCall(c: Context, i: Int) {
        val number = Prefs.str(c, "num$i")
        if (number.isBlank()) return
        val tm = c.getSystemService(TelecomManager::class.java)
        val extras = Bundle()
        handle(c, tm, Prefs.num(c, "sub$i", -1))?.let {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
        }
        tm.placeCall(Uri.fromParts("tel", number, null), extras)
        Thread.sleep(4500)
        try { @Suppress("DEPRECATION") tm.endCall() } catch (_: Exception) {}
        Thread.sleep(1000)
    }

    fun requestBalance(c: Context, i: Int) {
        if (Prefs.str(c, "num$i").isBlank()) return
        if (Prefs.mode(c, i) == "call") missedCall(c, i) else sendSms(c, i)
    }

    /** One row of <=15 chars when it fits, otherwise price and change on two rows. */
    private fun row(sb: StringBuilder, price: String, pct: Double) {
        val chg = "%+.1f%%".format(pct)
        val one = "$price $chg"
        if (one.length <= 15) sb.append(one) else sb.append(price).append('\n').append(chg)
        sb.append('\n')
    }
    private fun block(sb: StringBuilder, name: String, price: String, pct: Double) {
        sb.append(D).append('\n').append(name.take(15)).append('\n'); row(sb, price, pct)
    }
    private fun na(sb: StringBuilder, name: String) { sb.append(D).append('\n').append(name.take(15)).append("\nn/a\n") }

    private fun item(name: String, price: String, pct: Double): String {
        val sb = StringBuilder(); sb.append(name.take(15)).append('\n'); row(sb, price, pct); return sb.toString()
    }
    private fun naItem(name: String) = name.take(15) + "\nn/a\n"
    private fun sec(title: String, items: List<String>) =
        if (items.isEmpty()) "" else (title + "\n" + D + "\n" + items.joinToString(D + "\n")).trim()
    /** Market items have no heading and no separators. */
    private fun secM(items: List<String>) = items.joinToString("").trim()

    /** Returns notification text per block: hold, metal, crypto, idx. Equity blocks are omitted on weekend scheduled runs. */
    fun prices(c: Context, scheduled: Boolean): Map<String, String> {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val skipEq = scheduled && (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)
        val holds = if (skipEq) emptyList() else parseHoldings(Prefs.str(c, "holds"))
        val prem = 1 + (Prefs.str(c, "prem", "0").toDoubleOrNull() ?: 0.0) / 100
        val gold = Prefs.item(c, "gold"); val silver = Prefs.item(c, "silver")
        val idx = listOf("vix" to ("INDIA VIX" to "^INDIAVIX"), "nifty" to ("NIFTY 50" to "^NSEI"),
            "banknifty" to ("BANKNIFTY" to "^NSEBANK"), "sensex" to ("SENSEX" to "^BSESN"),
            "niftyit" to ("NIFTY IT" to "^CNXIT"))
            .filter { !skipEq && Prefs.item(c, it.first) }
        val usd = Prefs.item(c, "usdinr")

        val syms = mutableListOf<String>()
        holds.forEach { syms += it.sym }
        if (gold) syms += "GC=F"
        if (silver) syms += "SI=F"
        if (gold || silver || usd) syms += "USDINR=X"
        idx.forEach { syms += it.second.second }
        if (Prefs.item(c, "btc")) syms += "BTC-INR"
        if (Prefs.item(c, "eth")) syms += "ETH-INR"
        val m = fetchAll(syms)
        val fx = m["USDINR=X"]?.price

        val out = HashMap<String, String>()
        if (!skipEq) {
            val hItems = ArrayList<String>()
            var value = 0.0; var invested = 0.0
            holds.forEach { h ->
                val q = m[h.sym]
                if (q == null) hItems += naItem(h.name)
                else {
                    value += q.price * h.qty; invested += h.avg * h.qty
                    hItems += item(h.name, "₹" + inr(q.price * h.qty), if (h.avg > 0) (q.price - h.avg) / h.avg * 100 else 0.0)
                }
            }
            if (holds.isNotEmpty() && invested > 0) {
                val pnl = value - invested
                hItems += "TOTAL\n" + fit("VALUE", "₹" + inr(value)) + "\n" +
                        fit("INVESTED", "₹" + inr(invested)) + "\n" +
                        fit("P&L", sgn(pnl)) + "\n" +
                        "%+.1f%%".format(pnl / invested * 100) + "\n"
            }
            out["hold"] = sec("HOLDINGS", hItems)
            out["idx"] = secM(idx.map { (_, v) ->
                val q = m[v.second]
                if (q == null) naItem(v.first) else item(v.first, inr(q.price, 2), q.pct)
            })
        }
        fun metal(name: String, sym: String, perGram: Double): String {
            val q = m[sym] ?: return naItem(name)
            return if (fx != null) item(name, "₹" + inr(q.price * fx / 31.1035 * perGram * prem), q.pct)
            else item(name, "$" + inr(q.price) + "/oz", q.pct)
        }
        val metals = ArrayList<String>()
        if (gold) metals += metal("GOLD/10g", "GC=F", 10.0)
        if (silver) metals += metal("SILVER/kg", "SI=F", 1000.0)
        out["metal"] = secM(metals)

        val cr = ArrayList<String>()
        if (Prefs.item(c, "btc")) cr += m["BTC-INR"]?.let { item("BITCOIN", "₹" + inr(it.price), it.pct) } ?: naItem("BITCOIN")
        if (Prefs.item(c, "eth")) cr += m["ETH-INR"]?.let { item("ETHEREUM", "₹" + inr(it.price), it.pct) } ?: naItem("ETHEREUM")
        if (usd) cr += m["USDINR=X"]?.let { item("USD/INR", "₹" + inr(it.price, 2), it.pct) } ?: naItem("USD/INR")
        out["crypto"] = secM(cr)
        return out
    }

    fun run(c: Context, mode: Int, scheduled: Boolean, done: () -> Unit) {
        val app = c.applicationContext
        Thread {
            try {
                val box = arrayOfNulls<Map<String, String>>(1)
                // Finance and Spend refresh market/holding values. Spend also rescans recent SMS.
                val refreshPrices = mode == ALL || mode == PRICES || mode == SPEND
                val pt = if (refreshPrices) Thread { box[0] = prices(app, scheduled) }.also { it.start() } else null
                if (mode == ALL || mode == BAL) {
                    Prefs.ed(app).putLong("req", System.currentTimeMillis()).apply()
                    try { requestBalance(app, 1) } catch (_: Exception) {}
                }
                pt?.join(12000)
                box[0]?.let { r ->
                    val e = Prefs.ed(app)
                    r.forEach { (k, v) -> e.putString("sec_$k", v) }
                    e.putLong("financeUpdatedAt", System.currentTimeMillis())
                    e.apply()
                }
                if (mode == ALL || mode == SPEND) {
                    try { Spends.refresh(app, false) } catch (_: Exception) {}
                    Prefs.ed(app).putLong("spendUpdatedAt", System.currentTimeMillis()).apply()
                }
                when (mode) {
                    BAL -> Notifs.bank(app)
                    PRICES -> Notifs.finance(app)
                    SPEND -> { Notifs.spends(app); Notifs.finance(app) }
                    else -> Notifs.all(app)
                }
            } finally {
                if (mode == ALL) Notifs.prompt(app)
                done()
            }
        }.start()
    }
}

object Alarms {
    fun schedule(c: Context) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Prefs.num(c, "h", 8)); set(Calendar.MINUTE, Prefs.num(c, "m", 0))
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 1, Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }
}

/** Starts the phone's voice assistant (Gemini if it is the default assistant). */
object Assistant {
    fun launch(c: Context) {
        val tries = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND),
            Intent(Intent.ACTION_ASSIST),
            c.packageManager.getLaunchIntentForPackage("com.google.android.apps.bard")
        )
        for (i in tries) {
            if (i == null) continue
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
    }
}

/** Invisible activity so a notification button can start the assistant. */
class AssistActivity : Activity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); Assistant.launch(this); finish() }
}

class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val t = RemoteInput.getResultsFromIntent(i)?.getCharSequence("r")
            ?.toString()?.trim()?.lowercase() ?: ""
        val bankKey = Prefs.str(c, "triggerBank", "BANK").trim().lowercase()
        val financeKey = Prefs.str(c, "triggerFinance", "Finance").trim().lowercase()
        val spendKey = Prefs.str(c, "triggerSpend", "Spend").trim().lowercase()
        val masterKey = Prefs.str(c, "triggerMaster", "Master").trim().lowercase()
        val mode = when {
            t.startsWith("no") -> -1
            t.contains("voice") || t.contains("assistant") || t.contains("gemini") -> -2
            t.isNotBlank() && masterKey.isNotBlank() && t.contains(masterKey) -> Pipeline.ALL
            t.isNotBlank() && bankKey.isNotBlank() && t.contains(bankKey) -> Pipeline.BAL
            t.isNotBlank() && spendKey.isNotBlank() && t.contains(spendKey) -> Pipeline.SPEND
            t.isNotBlank() && financeKey.isNotBlank() && t.contains(financeKey) -> Pipeline.PRICES
            t.contains("bal") && !t.contains("spend") -> Pipeline.BAL
            t.contains("price") || t.contains("stock") || t.contains("market") -> Pipeline.PRICES
            t.contains("spend") -> Pipeline.SPEND
            else -> Pipeline.ALL
        }
        if (mode == -2) { Assistant.launch(c); Notifs.assistTap(c); Notifs.prompt(c); return }
        if (mode < 0) { Notifs.prompt(c); return }
        val p = goAsync()
        Pipeline.run(c, mode, false) { p.finish() }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Alarms.schedule(c)
        val p = goAsync()
        Pipeline.run(c, Pipeline.ALL, true) { p.finish() }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Alarms.schedule(c); Notifs.prompt(c) }
}

/** Reads each bank's SMS, extracts available balance, stores it per bank. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        val from = msgs.firstOrNull()?.originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        // A recognized transaction SMS refreshes both spend totals and the investment snapshot.
        if (Spends.onSms(c, from, body, msgs.first().timestampMillis)) {
            val pending = goAsync()
            Pipeline.run(c, Pipeline.SPEND, false) { pending.finish() }
        }
        if (body.contains("otp", true) || !body.contains("bal", true)) return
        val bank = (1..1).firstOrNull { b ->
            Prefs.str(c, "num$b").isNotBlank() &&
                    Prefs.sender(c, b).let { it.isNotBlank() && from.contains(it, true) }
        } ?: return
        val amt = BalanceParser.parse(body) ?: return
        val now = System.currentTimeMillis()
        Prefs.ed(c).putString("bal$bank", amt.toString()).putLong("balT$bank", now).apply()
        // only buzz the band if this is a reply to a request we just made
        if (now - Prefs.time(c, "req") < 120_000) Notifs.bank(c)
    }
}

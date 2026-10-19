package com.example.bankband

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private class BankUi(val name: EditText, val number: EditText, val sender: EditText,
                         val sms: EditText, val mode: RadioGroup, val sim: RadioGroup)
    private val banks = ArrayList<BankUi>()
    private val checks = LinkedHashMap<String, CheckBox>()
    private lateinit var fHolds: EditText
    private lateinit var fPrem: EditText
    private lateinit var fSalary: EditText
    private lateinit var fExcl: EditText
    private lateinit var timeBtn: Button
    private lateinit var triggerBank: EditText
    private lateinit var triggerFinance: EditText
    private lateinit var triggerSpend: EditText
    private lateinit var triggerMaster: EditText
    private lateinit var notifyBank: CheckBox
    private lateinit var notifyFinance: CheckBox
    private lateinit var notifySpend: CheckBox
    private lateinit var investmentView: TextView
    private lateinit var spendView: TextView
    private lateinit var statusView: TextView
    private var hour = 8
    private var minute = 0
    private lateinit var dashboard: LinearLayout
    private lateinit var notifications: LinearLayout
    private lateinit var setup: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(s: String) = TextView(this).apply { text = s; textSize = 14f; setPadding(0, dp(12), 0, dp(5)) }
    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply { this.hint = hint; setText(value); inputType = type; textSize = 15f }
    private fun card(title: String, content: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(14))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFF5F7FA.toInt()); cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0xFFE0E6ED.toInt())
        }
        addView(TextView(this@MainActivity).apply {
            text = title; textSize = 18f; setTextColor(0xFF102A43.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, dp(8))
        })
        addView(content)
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; setOnClickListener { action() }
    }
    private fun addBank(root: LinearLayout, i: Int) {
        root.addView(label("Bank balance source"))
        val eName = field("Bank name", Prefs.name(this, i))
        val eNum = field("Number to call / SMS", Prefs.str(this, "num$i"), InputType.TYPE_CLASS_PHONE)
        val eSnd = field("Reply sender contains", Prefs.sender(this, i))
        val eSms = field("SMS text", Prefs.str(this, "txt$i", "BAL"))
        val gMode = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val cur = Prefs.mode(this, i)
        listOf("call" to "Missed call (dial, hang up)", "sms" to "SMS request").forEach { (k, v) ->
            gMode.addView(RadioButton(this).apply { text = v; tag = k; id = View.generateViewId(); isChecked = k == cur })
        }
        val gSim = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        root.addView(eName); root.addView(eNum); root.addView(label("Balance request method")); root.addView(gMode)
        root.addView(eSms); root.addView(eSnd); root.addView(label("SIM for request")); root.addView(gSim)
        banks.add(BankUi(eName, eNum, eSnd, eSms, gMode, gSim))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        hour = Prefs.num(this, "h", 8); minute = Prefs.num(this, "m", 0)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(8)); setBackgroundColor(0xFFF3F6FA.toInt()) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(10)) }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_kuber_fg)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF0B1F3A.toInt()); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
            addView(TextView(this@MainActivity).apply { text = "Kuber"; textSize = 26f; setTextColor(0xFF102A43.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) })
            addView(TextView(this@MainActivity).apply { text = "Your money at a glance"; textSize = 13f; setTextColor(0xFF627D98.toInt()) })
        })
        outer.addView(header)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val body = FrameLayoutCompat(this)
        dashboard = pageColumn(); notifications = pageColumn(); setup = pageColumn()
        buildDashboard(); buildNotifications(); buildSetup()
        val dashboardScroll = ScrollView(this).apply { isFillViewport = true; addView(dashboard) }
        val notificationsScroll = ScrollView(this).apply { isFillViewport = true; visibility = View.GONE; addView(notifications) }
        val setupScroll = ScrollView(this).apply { isFillViewport = true; visibility = View.GONE; addView(setup) }
        val pages = listOf("Overview" to dashboardScroll, "Notifications" to notificationsScroll, "Setup" to setupScroll)
        fun showPage(target: ScrollView) {
            dashboardScroll.visibility = if (target === dashboardScroll) View.VISIBLE else View.GONE
            notificationsScroll.visibility = if (target === notificationsScroll) View.VISIBLE else View.GONE
            setupScroll.visibility = if (target === setupScroll) View.VISIBLE else View.GONE
            pages.forEach { (title, page) ->
                val tab = tabs.findViewWithTag<Button>(title)
                tab?.let { it.setBackgroundColor(if (page === target) 0xFF102A43.toInt() else 0xFFE3EAF2.toInt()); it.setTextColor(if (page === target) 0xFFFFFFFF.toInt() else 0xFF243B53.toInt()) }
            }
        }
        pages.forEach { (title, page) -> tabs.addView(Button(this).apply {
            text = title; tag = title; textSize = 12f; isAllCaps = false; elevation = dp(1).toFloat()
            setPadding(dp(2), 0, dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) }
            setOnClickListener { showPage(page) }
        }) }
        outer.addView(tabs)
        body.setBackgroundColor(0xFFF3F6FA.toInt())
        body.addView(dashboardScroll); body.addView(notificationsScroll); body.addView(setupScroll)
        showPage(dashboardScroll)
        outer.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer)
        val perms = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
        loadSims(); refreshDashboard(); KuberWidgetProvider.refresh(this)
    }

    private fun pageColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(20)); visibility = View.VISIBLE
    }
    private fun addSection(page: LinearLayout, title: String, content: View) {
        page.addView(card(title, content), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun buildDashboard() {
        dashboard.visibility = View.VISIBLE
        statusView = TextView(this).apply { textSize = 12f; setTextColor(0xFF627D98.toInt()); setPadding(0, 0, 0, dp(10)) }
        dashboard.addView(statusView)
        investmentView = TextView(this).apply { textSize = 14f; setTextColor(0xFF243B53.toInt()); setLineSpacing(dp(4).toFloat(), 1f) }
        val invContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(investmentView); addView(button("Refresh investments", { runPipeline(Pipeline.PRICES) })) }
        addSection(dashboard, "Investments", invContent)
        spendView = TextView(this).apply { textSize = 15f; setTextColor(0xFF243B53.toInt()); setLineSpacing(dp(4).toFloat(), 1f) }
        val spendContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(spendView)
            addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL
                addView(button("Refresh spends + investments", { runPipeline(Pipeline.SPEND) }), LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("Transactions", { showTxns() }), LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        addSection(dashboard, "Spending", spendContent)
        val quick = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(button("Refresh everything · Master", { runPipeline(Pipeline.ALL) }))
            addView(button("Refresh bank balance", { runPipeline(Pipeline.BAL) }))
            addView(button("Assistant", { Assistant.launch(this@MainActivity) }))
        }
        addSection(dashboard, "Quick actions", quick)
    }

    private fun buildNotifications() {
        notifications.addView(TextView(this).apply {
            text = "Reply to the Kuber notification with a trigger word. Each trigger refreshes only its assigned sections."
            textSize = 13f; setTextColor(0xFF627D98.toInt()); setPadding(0, 0, 0, dp(10))
        })
        triggerBank = field("BANK", Prefs.str(this, "triggerBank", "BANK"))
        triggerFinance = field("Finance", Prefs.str(this, "triggerFinance", "Finance"))
        triggerSpend = field("Spend", Prefs.str(this, "triggerSpend", "Spend"))
        triggerMaster = field("Master", Prefs.str(this, "triggerMaster", "Master"))
        val triggers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(label("Bank trigger → refresh bank balance only")); addView(triggerBank)
            addView(label("Finance trigger → refresh holdings, indices, metals and crypto")); addView(triggerFinance)
            addView(label("Spend trigger → refresh spend totals and investments")); addView(triggerSpend)
            addView(label("Master trigger → refresh all sections")); addView(triggerMaster)
        }
        addSection(notifications, "Reply triggers", triggers)
        notifyBank = CheckBox(this).apply { text = "Bank balance notification"; isChecked = Prefs.flag(this@MainActivity, "notifyBank", true) }
        notifyFinance = CheckBox(this).apply { text = "Investment / finance notification"; isChecked = Prefs.flag(this@MainActivity, "notifyFinance", true) }
        notifySpend = CheckBox(this).apply { text = "Spend summary notification"; isChecked = Prefs.flag(this@MainActivity, "notifySpend", true) }
        val toggles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(notifyBank); addView(notifyFinance); addView(notifySpend) }
        addSection(notifications, "Notification delivery", toggles)
        timeBtn = button("", { TimePickerDialog(this, { _, h, m -> hour = h; minute = m; refreshTime() }, hour, minute, true).show() })
        val schedule = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(label("Daily refresh time")); addView(timeBtn)
            addView(TextView(this@MainActivity).apply { text = "The daily refresh updates balances, investments and spends. Android may delay alarms to save battery."; textSize = 12f; setTextColor(0xFF627D98.toInt()) })
        }
        addSection(notifications, "Schedule", schedule)
        notifications.addView(button("Save notification settings", { save(false); Toast.makeText(this, "Notification settings saved", Toast.LENGTH_SHORT).show() }))
        refreshTime()
    }

    private fun buildSetup() {
        addBank(setup, 1)
        setup.addView(label("Holdings · one per line: SYMBOL QTY AVGPRICE [NAME]"))
        fHolds = EditText(this).apply {
            hint = "CANBK.NS 100 95.5"; setText(Prefs.str(this@MainActivity, "holds"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 4; gravity = Gravity.TOP
        }
        addSection(setup, "Investment holdings", fHolds)
        setup.addView(button("Add a holding", { addHoldingDialog() }))
        fSalary = field("Salary keywords (comma separated)", Prefs.str(this, "salary", "NTT DA"))
        fExcl = field("Ignore SMS keywords (comma separated)", Prefs.str(this, "exclkw", "Policy"))
        val spendSettings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(label("Credits containing these words are ignored as salary")); addView(fSalary)
            addView(label("Ignore transactions containing these words")); addView(fExcl)
            addView(button("Rescan all SMS", { rescan() }))
            addView(button("View / exclude transactions", { showTxns() }))
            addView(button("Open app settings / permissions", { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }))
        }
        addSection(setup, "Spend tracking", spendSettings)
        setup.addView(label("Market items shown in investment refresh"))
        listOf("gold" to "Gold", "silver" to "Silver", "vix" to "India VIX", "nifty" to "Nifty 50",
            "banknifty" to "Bank Nifty", "sensex" to "Sensex", "niftyit" to "Nifty IT",
            "btc" to "Bitcoin", "usdinr" to "USD/INR", "eth" to "Ethereum").forEach { (k, v) ->
            val cb = CheckBox(this).apply { text = v; isChecked = Prefs.item(this@MainActivity, k) }
            checks[k] = cb; setup.addView(cb)
        }
        fPrem = field("Metal premium % (duty + GST)", Prefs.str(this, "prem", "0"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        setup.addView(label("Metal premium %")); setup.addView(fPrem)
        setup.addView(button("Save setup", { save(false); Toast.makeText(this, "Setup saved", Toast.LENGTH_SHORT).show() }))
        setup.addView(button("Clear saved bank balance", {
            Prefs.ed(this).remove("bal1").remove("balT1").apply(); Thread { Notifs.bank(this) }.start()
            Toast.makeText(this, "Saved balance cleared", Toast.LENGTH_SHORT).show()
        }))
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); loadSims(); rescan(false)
    }

    private fun refreshDashboard() {
        val financeTime = Prefs.time(this, "financeUpdatedAt")
        val spendTime = Prefs.time(this, "spendUpdatedAt")
        val df = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        statusView.text = "Finance updated: ${if (financeTime == 0L) "Not yet" else df.format(Date(financeTime))}  ·  Spends updated: ${if (spendTime == 0L) "Not yet" else df.format(Date(spendTime))}"
        val holdings = Prefs.str(this, "sec_hold").trim().lines()
            .map { it.trim() }.filter { it.isNotBlank() && !it.equals("HOLDINGS", true) && !it.matches(Regex("[-_=]{3,}")) }
        investmentView.text = if (holdings.isEmpty()) "No investment snapshot yet. Add holdings in Setup, then refresh investments."
            else holdings.take(4).joinToString("\n")
        spendView.text = if (!Spends.granted(this)) "Allow SMS permission to calculate spending."
            else Spends.monthRows(this).joinToString("\n") { "${it.first}: ${sgn(it.second, false)}" }
    }

    private fun runPipeline(mode: Int) {
        Toast.makeText(this, "Refreshing…", Toast.LENGTH_SHORT).show()
        Pipeline.run(this, mode, false) {
            runOnUiThread { refreshDashboard(); KuberWidgetProvider.refresh(this); Toast.makeText(this, "Refresh complete", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun rescan(full: Boolean = true) {
        if (full) Prefs.ed(this).putString("salary", fSalary.text.toString().trim()).putString("exclkw", fExcl.text.toString().trim()).apply()
        if (!Spends.granted(this)) { Toast.makeText(this, "Allow SMS permission first", Toast.LENGTH_LONG).show(); return }
        Thread {
            val n = try { Spends.refresh(this, full) } catch (_: Exception) { -1 }
            Prefs.ed(this).putLong("spendUpdatedAt", System.currentTimeMillis()).apply()
            Notifs.spends(this)
            runOnUiThread { refreshDashboard(); KuberWidgetProvider.refresh(this); if (full) Toast.makeText(this, "Found $n spend SMS", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private fun showTxns() {
        val list = Spends.load(this).sortedByDescending { it.ts }.take(300)
        if (list.isEmpty()) { Toast.makeText(this, "No spends yet. Rescan SMS first.", Toast.LENGTH_SHORT).show(); return }
        val df = SimpleDateFormat("dd MMM", Locale.US)
        val labels = list.map { "${df.format(Date(it.ts))}  ${if (it.credit) "+" else "-"}₹${inr(it.amt, 2)}  ${if (it.salary) "[salary] " else ""}${it.merchant}" }.toTypedArray()
        val on = BooleanArray(list.size) { !list[it].excluded }
        AlertDialog.Builder(this).setTitle("Checked = counted (- debit, + credit)")
            .setMultiChoiceItems(labels, on) { _, i, checked -> Spends.setExcluded(this, list[i].key, !checked) }
            .setPositiveButton("Done") { _, _ -> refreshDashboard(); Thread { Notifs.spends(this) }.start() }.show()
    }

    private fun addHoldingDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val sym = field("Symbol, e.g. CANBK.NS or 543XXX.BO", "")
        val qty = field("Quantity", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val avg = field("Average price", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val nm = field("Short name (optional)", "")
        listOf(sym, qty, avg, nm).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("Add holding").setView(box)
            .setPositiveButton("Add") { _, _ ->
                val line = listOf(sym.text.toString().trim().uppercase(), qty.text.toString().trim(), avg.text.toString().trim(), nm.text.toString().trim().replace(' ', '_'))
                    .filter { it.isNotEmpty() }.joinToString(" ")
                if (line.split(" ").size >= 3) {
                    val cur = fHolds.text.toString().trim(); fHolds.setText(if (cur.isEmpty()) line else "$cur\n$line")
                } else Toast.makeText(this, "Symbol, quantity and average price are required", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun refreshTime() { timeBtn.text = "Daily refresh at %02d:%02d".format(hour, minute) }

    private fun loadSims() {
        val list = try { getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList ?: emptyList() }
        catch (_: SecurityException) { emptyList() }
        banks.forEachIndexed { idx, ui ->
            ui.sim.removeAllViews(); val saved = Prefs.num(this, "sub${idx + 1}", -1)
            fun add(title: String, sub: Int) { ui.sim.addView(RadioButton(this).apply { text = title; tag = sub; id = View.generateViewId(); isChecked = sub == saved }) }
            add("Default SIM", -1); list.forEach { add("SIM ${it.simSlotIndex + 1} - ${it.carrierName}", it.subscriptionId) }
            if (ui.sim.checkedRadioButtonId == -1 && ui.sim.childCount > 0) (ui.sim.getChildAt(0) as RadioButton).isChecked = true
        }
    }

    private fun save(runNow: Boolean) {
        val e = Prefs.ed(this)
        banks.forEachIndexed { idx, ui ->
            val i = idx + 1
            val m = ui.mode.findViewById<RadioButton>(ui.mode.checkedRadioButtonId)?.tag as? String
            val s = ui.sim.findViewById<RadioButton>(ui.sim.checkedRadioButtonId)?.tag as? Int
            e.putString("n$i", ui.name.text.toString().trim()).putString("num$i", ui.number.text.toString().trim())
                .putString("snd$i", ui.sender.text.toString().trim()).putString("txt$i", ui.sms.text.toString().trim())
                .putString("mode$i", m ?: "sms").putInt("sub$i", s ?: -1)
        }
        e.putString("holds", fHolds.text.toString().trim()).putString("salary", fSalary.text.toString().trim())
            .putString("exclkw", fExcl.text.toString().trim()).putString("prem", fPrem.text.toString().trim())
            .putInt("h", hour).putInt("m", minute)
            .putString("triggerBank", triggerBank.text.toString().trim())
            .putString("triggerFinance", triggerFinance.text.toString().trim())
            .putString("triggerSpend", triggerSpend.text.toString().trim())
            .putString("triggerMaster", triggerMaster.text.toString().trim())
            .putBoolean("notifyBank", notifyBank.isChecked).putBoolean("notifyFinance", notifyFinance.isChecked)
            .putBoolean("notifySpend", notifySpend.isChecked)
        checks.forEach { (k, cb) -> e.putBoolean(k, cb.isChecked) }
        e.apply(); Alarms.schedule(this)
        if (runNow) runPipeline(Pipeline.ALL) else Notifs.prompt(this)
    }
}

/** A simple frame layout wrapper kept local to avoid additional UI dependencies. */
private class FrameLayoutCompat(context: android.content.Context) : android.widget.FrameLayout(context)

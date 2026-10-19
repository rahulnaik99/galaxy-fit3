package com.example.bankband

import java.text.SimpleDateFormat
import java.util.*

/** One debit transaction parsed from an SMS. */
data class Txn(
    val ts: Long, val amt: Double, val acct: String, val ref: String,
    val sender: String, val sig: String, val merchant: String, val excluded: Boolean = false,
    val credit: Boolean = false, val salary: Boolean = false
) {
    val key get() = "${ts}_${amt}_${if (credit) "c" else "d"}"
}

/** Pure parsing / de-duplication logic (no Android dependencies). */
object SpendParser {
    private val reject = listOf(
        "otp", "one time password", "will be debited", "to be debited", "will be deducted",
        "collect request", "declined", "failed", "unsuccessful", "not successful", "reversed",
        "reversal", "refund", "cashback", "offer", "pre-approved", "pre approved", "statement",
        "minimum amount due", "total amount due", "due on", "mandate", "insufficient",
        "congratulations", "apply now"
    )
    private val debitWord = Regex(
        "(?i)\\b(?:debited|spent|paid|purchased?|withdrawn|withdrawal|deducted|sent)\\b" +
                "|\\bdebit\\s+(?:of|by|inr|rs|₹)|\\bdr\\b|\\b(?:txn|transaction)\\s+of\\s+(?:rs|inr|₹)"
    )
    private val creditWord = Regex("(?i)\\b(?:credited|received|deposited|refunded)\\b")

    private val amtNear = Regex(
        "(?i)(?:debited|spent|paid|withdrawn|deducted|sent|purchased?)\\s*(?:by|for|of|with|:)?\\s*" +
                "(?:\\brs\\.?|\\binr|₹)\\s*([\\d,]+(?:\\.\\d+)?)"
    )
    private val amtCredit = Regex(
        "(?i)(?:credited|received|deposited|refunded)\\s*(?:by|with|for|of|:)?\\s*" +
                "(?:\\brs\\.?|\\binr|₹)\\s*([\\d,]+(?:\\.\\d+)?)"
    )
    private val fromRx = Regex(
        "(?i)(?:\\bfrom\\b|\\bby\\b)\\s+([A-Za-z0-9@._&\\- ]{3,30}?)" +
                "(?=\\s+(?:on|ref\\w*|upi|avl|bal|via|to|using|ending)\\b|[.,;]|$)"
    )
    private val amtAny = Regex("(?i)(?:\\brs\\.?|\\binr|₹)\\s*([\\d,]+(?:\\.\\d+)?)")
    private val amtBare = Regex("(?i)(?:debited|withdrawn|deducted)\\s*(?:by|for|of|with)?\\s*([\\d,]+\\.\\d{1,2})")

    private val acctRx = Regex("(?i)(?:a/?c|acct?|account|card)[^0-9]{0,20}?[xX*]*(\\d{4})\\b")
    private val refRx = Regex(
        "(?i)(?:ref(?:erence)?(?:\\s*(?:no|number|id))?|rrn|utr|txn\\s*(?:id|no)|upi)[^A-Za-z0-9]{0,6}(\\d{6,})"
    )
    private val ref12 = Regex("\\b(\\d{12})\\b")
    private val merchRx = Regex(
        "(?i)(?:\\bto\\b|\\bat\\b|\\binfo:?|towards|\\bvpa\\b)\\s+([A-Za-z0-9@._&\\- ]{3,30}?)" +
                "(?=\\s+(?:on|ref\\w*|upi|avl|bal|via|from|using|ending)\\b|[.,;]|$)"
    )

    fun normSender(s: String): String {
        val p = s.uppercase().split('-')
        return if (p.size >= 2) p[1] else p[0]
    }

    /** Parses a debit or a credit SMS. Credits containing any salaryKeys word are flagged as salary. */
    fun parse(sender: String, body: String, ts: Long, salaryKeys: List<String> = emptyList(),
              excludeKeys: List<String> = emptyList()): Txn? {
        val l = body.lowercase()
        if (excludeKeys.any { it.isNotBlank() && l.contains(it.trim().lowercase()) }) return null
        if (reject.any { l.contains(it) }) return null
        val hasDebit = debitWord.containsMatchIn(body)
        val hasCredit = creditWord.containsMatchIn(body)
        if (!hasDebit && !hasCredit) return null
        val credit = !hasDebit
        val m = if (credit) (amtCredit.find(body) ?: amtAny.find(body))
        else (amtNear.find(body) ?: amtAny.find(body) ?: amtBare.find(body))
        val amt = (m?.groupValues?.get(1) ?: return null).replace(",", "").toDoubleOrNull() ?: return null
        if (amt <= 0.0) return null
        val acct = acctRx.find(body)?.groupValues?.get(1) ?: ""
        val ref = refRx.find(body)?.groupValues?.get(1) ?: ref12.find(body)?.groupValues?.get(1) ?: ""
        val merchant = (if (credit) fromRx.find(body) else merchRx.find(body))
            ?.groupValues?.get(1)?.trim()?.take(20) ?: ""
        val flat = body.uppercase().replace(Regex("\\s+"), " ")
        val salary = credit && salaryKeys.any { it.isNotBlank() && flat.contains(it.trim().uppercase()) }
        val sig = l.replace(Regex("\\s+"), " ").trim().hashCode().toString()
        return Txn(ts, amt, acct, ref, normSender(sender), sig, merchant, false, credit, salary)
    }

    private const val WINDOW = 10 * 60 * 1000L

    fun isDup(a: Txn, b: Txn): Boolean {
        if (a.amt != b.amt || a.credit != b.credit) return false
        if (a.ref.isNotEmpty() && b.ref.isNotEmpty()) return a.ref == b.ref
        if (Math.abs(a.ts - b.ts) > WINDOW) return false
        if (a.sender == b.sender) return a.sig == b.sig
        return a.acct == b.acct || a.acct.isEmpty() || b.acct.isEmpty()
    }

    /** Removes duplicates, keeping the earliest and filling its blank fields from the later copy. */
    fun dedupe(all: List<Txn>): MutableList<Txn> {
        val kept = ArrayList<Txn>()
        for (t in all.sortedBy { it.ts }) {
            val i = kept.indexOfLast { isDup(it, t) }
            if (i < 0) kept.add(t)
            else {
                val k = kept[i]
                kept[i] = k.copy(
                    acct = k.acct.ifEmpty { t.acct }, ref = k.ref.ifEmpty { t.ref },
                    merchant = k.merchant.ifEmpty { t.merchant }, excluded = k.excluded || t.excluded,
                    salary = k.salary || t.salary
                )
            }
        }
        return kept
    }

    fun monthKey(ts: Long) = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(ts))

    /** Net spent per month = debits - credits (salary credits and excluded entries ignored). */
    fun monthTotals(list: List<Txn>): Map<String, Double> =
        list.filter { !it.excluded && !it.salary }.groupBy { monthKey(it.ts) }
            .mapValues { e -> e.value.sumOf { if (it.credit) -it.amt else it.amt } }
}

/** Extracts the available balance from a bank SMS without being fooled by dates, times or account numbers. */
object BalanceParser {
    private val noise = listOf(
        Regex("\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b"),
        Regex("\\b\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}\\b"),
        Regex("(?i)\\b\\d{1,2}[-/ ]?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-/ ,.]*\\d{0,4}\\b"),
        Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"),
        Regex("(?i)\\b(?:a/?c|acct?|account|card|ref\\w*|no\\.?)\\s*[:.]?\\s*(?:no\\.?)?\\s*[xX*]*\\d+")
    )
    private val strong = Regex("(?i)(?:avl\\.?\\s*bal(?:ance)?|available\\s*(?:bal(?:ance)?)?|clear\\s*bal(?:ance)?|closing\\s*bal(?:ance)?)")
    private val weak = Regex("(?i)\\bbal(?:ance)?\\b")
    private val money = Regex("(?i)(?:\\bINR|\\bRs\\.?|₹)\\s*-?([\\d,]+(?:\\.\\d+)?)")
    private val decimal = Regex("([\\d,]*\\d\\.\\d{1,2})\\b")
    private val plain = Regex("\\b(\\d[\\d,]{2,})\\b")

    private fun num(s: String) = s.replace(",", "").trimEnd('.').toDoubleOrNull()

    fun parse(body: String): Double? {
        var t = body
        noise.forEach { t = it.replace(t, " ") }
        for (kw in listOf(strong, weak)) {
            for (m in kw.findAll(t)) {
                val after = t.substring(m.range.last + 1).take(45)
                (money.find(after) ?: decimal.find(after) ?: plain.find(after.take(25)))
                    ?.let { r -> num(r.groupValues[1])?.let { return it } }
            }
        }
        return null
    }
}

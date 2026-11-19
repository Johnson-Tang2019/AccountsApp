package com.abyssredemption.accounts

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import java.security.MessageDigest

data class PaymentRecord(
    val id: Long,
    val merchant: String,
    val amount: Double,
    val source: String,
    val paidAt: Long,
    val kind: String,
    val category: String,
    val note: String
)

data class MonthSummary(val income: Double, val expense: Double)
data class BudgetStats(val monthRemaining: Double, val accumulatedRemaining: Double)
data class DailyAmount(val dayKey: String, val income: Double, val expense: Double)

enum class AutoPaymentResult { RECORDED, REPLAY, CROSS_CHANNEL_DUPLICATE, CONFIRMATION_REQUIRED }

class AccountDb(context: Context) : SQLiteOpenHelper(context, "accounts.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE payments(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            merchant TEXT NOT NULL,
            amount REAL NOT NULL,
            source TEXT NOT NULL,
            paid_at INTEGER NOT NULL,
            fingerprint TEXT NOT NULL UNIQUE,
            kind TEXT NOT NULL DEFAULT 'expense',
            category TEXT NOT NULL DEFAULT '其他',
            note TEXT NOT NULL DEFAULT ''
        )""")
        createRecognitionTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE payments ADD COLUMN kind TEXT NOT NULL DEFAULT 'expense'")
            db.execSQL("ALTER TABLE payments ADD COLUMN category TEXT NOT NULL DEFAULT '其他'")
            db.execSQL("ALTER TABLE payments ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) createRecognitionTables(db)
    }

    private fun createRecognitionTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE recognition_observations (
                fingerprint TEXT PRIMARY KEY NOT NULL,
                channel TEXT NOT NULL,
                observed_at INTEGER NOT NULL,
                paired INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX recognition_observations_time ON recognition_observations(observed_at)")
    }

    fun recordAutomaticPayment(
        merchant: String, amount: Double, source: String, paidAt: Long,
        channel: String, fingerprint: String, confirmSameAmount: Boolean = false
    ): AutoPaymentResult {
        require(amount.isFinite() && amount > 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = when {
                db.rawQuery(
                    "SELECT 1 FROM recognition_observations WHERE fingerprint=?",
                    arrayOf(fingerprint)
                ).use { it.moveToFirst() } -> AutoPaymentResult.REPLAY
                else -> {
                    val other = findCrossChannelMatch(db, source, merchant, amount, paidAt, channel)
                    if (other != null) {
                        db.execSQL("UPDATE recognition_observations SET paired=1 WHERE fingerprint=?", arrayOf(other))
                        rememberObservation(db, fingerprint, channel, paidAt, paired = true)
                        AutoPaymentResult.CROSS_CHANNEL_DUPLICATE
                    } else if (confirmSameAmount && latestExpenseHasAmount(db, amount)) {
                        AutoPaymentResult.CONFIRMATION_REQUIRED
                    } else if (insertRecord(merchant, amount, source, paidAt, "expense", "自动记账", "", fingerprint)) {
                        rememberObservation(db, fingerprint, channel, paidAt, paired = false)
                        AutoPaymentResult.RECORDED
                    } else {
                        AutoPaymentResult.REPLAY
                    }
                }
            }
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun rememberObservation(db: SQLiteDatabase, fingerprint: String, channel: String, paidAt: Long, paired: Boolean) {
        db.execSQL(
            "INSERT INTO recognition_observations(fingerprint,channel,observed_at,paired) VALUES(?,?,?,?)",
            arrayOf(fingerprint, channel, paidAt, if (paired) 1 else 0)
        )
    }

    private fun latestExpenseHasAmount(db: SQLiteDatabase, amount: Double): Boolean =
        db.rawQuery("SELECT amount FROM payments WHERE kind='expense' ORDER BY paid_at DESC, id DESC LIMIT 1", null)
            .use { it.moveToFirst() && kotlin.math.abs(it.getDouble(0) - amount) < 0.001 }

    private fun findCrossChannelMatch(
        db: SQLiteDatabase, source: String, merchant: String, amount: Double, paidAt: Long, channel: String
    ): String? {
        data class Match(val fingerprint: String, val merchant: String, val distanceMs: Long)
        val matches = mutableListOf<Match>()
        db.rawQuery(
            """
            SELECT p.fingerprint,p.merchant,o.observed_at
            FROM payments p JOIN recognition_observations o ON o.fingerprint=p.fingerprint
            WHERE p.kind='expense' AND p.source=? AND ABS(p.amount-?)<0.001
              AND o.observed_at BETWEEN ? AND ? AND o.channel<>? AND o.paired=0
            ORDER BY ABS(o.observed_at-CAST(? AS INTEGER)) ASC
            """.trimIndent(),
            arrayOf(source, amount.toString(), (paidAt - 20_000).toString(), (paidAt + 20_000).toString(), channel, paidAt.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) matches += Match(
                cursor.getString(0), cursor.getString(1), kotlin.math.abs(cursor.getLong(2) - paidAt)
            )
        }
        val exact = matches.filter { it.merchant.trim().equals(merchant.trim(), ignoreCase = true) }
        val closest = exact.firstOrNull()
        if (closest != null) return closest.fingerprint.takeUnless { exact.getOrNull(1)?.distanceMs == closest.distanceMs }
        val only = matches.singleOrNull() ?: return null
        return only.fingerprint.takeIf { PaymentEvidence.sameMerchant(merchant, only.merchant, source) }
    }

    fun insertManual(merchant: String, amount: Double, kind: String, category: String, note: String): Boolean =
        insertRecord(merchant.ifBlank { note.ifBlank { category } }, amount, "手动记录", System.currentTimeMillis(), kind, category, note, UUID.randomUUID().toString())

    fun update(id: Long, merchant: String, amount: Double, kind: String, category: String, note: String): Boolean =
        writableDatabase.update("payments", ContentValues().apply {
            put("merchant", merchant.ifBlank { category })
            put("amount", amount)
            put("kind", kind)
            put("category", category)
            put("note", note)
        }, "id=?", arrayOf(id.toString())) > 0

    private fun insertRecord(merchant: String, amount: Double, source: String, paidAt: Long, kind: String, category: String, note: String, fingerprint: String): Boolean =
        writableDatabase.insertWithOnConflict("payments", null, ContentValues().apply {
            put("merchant", merchant); put("amount", amount); put("source", source)
            put("paid_at", paidAt); put("fingerprint", fingerprint); put("kind", kind)
            put("category", category); put("note", note)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun recent(kind: String? = null): List<PaymentRecord> {
        val result = mutableListOf<PaymentRecord>()
        val where = if (kind == null) "" else "WHERE kind=?"
        val args = if (kind == null) null else arrayOf(kind)
        readableDatabase.rawQuery("SELECT id,merchant,amount,source,paid_at,kind,category,note FROM payments $where ORDER BY paid_at DESC LIMIT 200", args).use { c ->
            while (c.moveToNext()) result += PaymentRecord(c.getLong(0), c.getString(1), c.getDouble(2), c.getString(3), c.getLong(4), c.getString(5), c.getString(6), c.getString(7))
        }
        return result
    }

    fun allRecords(): List<PaymentRecord> {
        val result = mutableListOf<PaymentRecord>()
        readableDatabase.rawQuery(
            "SELECT id,merchant,amount,source,paid_at,kind,category,note FROM payments ORDER BY paid_at ASC",
            null
        ).use { c ->
            while (c.moveToNext()) result += PaymentRecord(
                c.getLong(0), c.getString(1), c.getDouble(2), c.getString(3),
                c.getLong(4), c.getString(5), c.getString(6), c.getString(7)
            )
        }
        return result
    }

    fun insertImported(record: ImportedRecord): Boolean {
        val alreadyExists = readableDatabase.rawQuery(
            "SELECT 1 FROM payments WHERE paid_at=? AND kind=? AND merchant=? AND ABS(amount-?)<0.001 LIMIT 1",
            arrayOf(record.paidAt.toString(), record.kind, record.merchant, record.amount.toString())
        ).use { it.moveToFirst() }
        if (alreadyExists) return false
        val identity = listOf(
            "xlsx", record.paidAt, record.kind, record.category, record.merchant,
            record.amount, record.source, record.note
        ).joinToString("|")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return insertRecord(
            record.merchant, record.amount, record.source, record.paidAt,
            record.kind, record.category, record.note, fingerprint
        )
    }

    fun monthSummary(start: Long, end: Long): MonthSummary {
        var income = 0.0; var expense = 0.0
        readableDatabase.rawQuery("SELECT kind,COALESCE(SUM(amount),0) FROM payments WHERE paid_at>=? AND paid_at<? GROUP BY kind", arrayOf(start.toString(), end.toString())).use { c ->
            while (c.moveToNext()) if (c.getString(0) == "income") income = c.getDouble(1) else expense = c.getDouble(1)
        }
        return MonthSummary(income, expense)
    }

    fun dailyAmounts(start: Long, end: Long): List<DailyAmount> {
        val byDay = linkedMapOf<String, DoubleArray>()
        readableDatabase.rawQuery(
            """
            SELECT strftime('%Y-%m-%d', paid_at / 1000, 'unixepoch', 'localtime') AS day_key,
                   kind,
                   COALESCE(SUM(amount),0)
            FROM payments
            WHERE paid_at>=? AND paid_at<?
            GROUP BY day_key, kind
            ORDER BY day_key ASC
            """.trimIndent(),
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val values = byDay.getOrPut(c.getString(0)) { doubleArrayOf(0.0, 0.0) }
                if (c.getString(1) == "income") values[0] = c.getDouble(2) else values[1] = c.getDouble(2)
            }
        }
        return byDay.map { (day, values) -> DailyAmount(day, values[0], values[1]) }
    }

    fun budgetStats(monthlyBudget: Double, currentBalance: Double, currentMonthStart: Long): BudgetStats {
        var accumulated = 0.0
        readableDatabase.rawQuery(
            "SELECT strftime('%Y-%m', paid_at / 1000, 'unixepoch', 'localtime') AS month_key, COALESCE(SUM(amount),0) FROM payments WHERE kind='expense' AND paid_at<? GROUP BY month_key",
            arrayOf(currentMonthStart.toString())
        ).use { c ->
            while (c.moveToNext()) accumulated += monthlyBudget - c.getDouble(1)
        }
        return BudgetStats(currentBalance, accumulated + currentBalance)
    }

    fun netChangeSince(timestamp: Long): Double {
        var net = 0.0
        readableDatabase.rawQuery(
            "SELECT kind,COALESCE(SUM(amount),0) FROM payments WHERE paid_at>? GROUP BY kind",
            arrayOf(timestamp.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val amount = c.getDouble(1)
                net += if (c.getString(0) == "income") amount else -amount
            }
        }
        return net
    }

    fun delete(id: Long) = writableDatabase.delete("payments", "id=?", arrayOf(id.toString()))
}

package com.jigong.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.YearMonth

/**
 * 一张表的全部数据访问。
 *
 * 设计前提：
 *  - 同一天永远只有一条记录（work_date UNIQUE），"记半天/记一天"是覆盖，不是累加。
 *  - 不联网、不同步、不导出。数据只有这一个 SQLite 文件。
 */
class WorkRepo private constructor(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 第一次真正要查数据时才开库，而不是构造 WorkRepo 时就开。
     *
     * 上一轮把三条查询搬进了 IO 线程，但构造 WorkRepo 仍然发生在
     * `MainActivity.onCreate` 里 —— 而 `SQLiteOpenHelper.writableDatabase`
     * 这一步本身就包含「创建/打开文件、校验 schema、必要时建表、编译语句」，
     * 正是最贵的那一下。也就是说：真正压在主线程上的那次 I/O 一直没搬走，
     * 搬走的只是之后的几次小查询。
     *
     * 改成 lazy 之后，第一次访问会发生在 `reload()` / `apply()` 里的
     * `withContext(Dispatchers.IO)` 中 —— 冷启动那一帧不用再等磁盘。
     * 单例 + 只有一个线程会先碰到它，所以 lazy 的开销可以忽略。
     */
    private val db: SQLiteDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Helper(appContext).writableDatabase
    }

    companion object {
        /**
         * 全进程一份。
         *
         * 原来 MainActivity 每次 onCreate 都 new 一个 WorkRepo，等于每重建一次界面
         * 就多开一条 SQLite 连接，而且再也没有人关它。改字体大小、切深色模式、
         * 系统回收后重建 —— 都会走 onCreate。连接攒多了不只是浪费，
         * 多连接同时写还可能撞 SQLITE_BUSY。
         *
         * 单表、单进程、无并发写，一份连接足够，也没有加连接池的理由。
         */
        @Volatile
        private var shared: WorkRepo? = null

        fun get(ctx: Context): WorkRepo =
            shared ?: synchronized(this) {
                shared ?: WorkRepo(ctx.applicationContext).also { shared = it }
            }
    }

    /** 某个月里记过的日子：日期 -> 工天（1.0 / 0.5） */
    fun monthMap(ym: YearMonth): Map<LocalDate, Double> {
        val out = LinkedHashMap<LocalDate, Double>()
        db.rawQuery(
            "SELECT work_date, work_amount FROM work_record " +
                "WHERE work_date BETWEEN ? AND ? ORDER BY work_date",
            arrayOf(ym.atDay(1).toString(), ym.atEndOfMonth().toString())
        ).use { c ->
            while (c.moveToNext()) {
                out[LocalDate.parse(c.getString(0))] = c.getDouble(1)
            }
        }
        return out
    }

    /**
     * 单独查某一天，没记过返回 null。
     *
     * 存在的理由：`monthMap` 只装**当前显示那个月**的记录。首页那颗「今天记一天」
     * 按钮要的是**今天**的工天，而用户完全可以把日历翻到上个月去看 —— 这时候
     * `monthMap(today 所在月)` 是空的，按钮就会以为今天还没记，一点把已经记的
     * 0.5 天覆盖成 1 天。所以"今天"必须单独查，不能从当月的 map 里取。
     */
    fun dayAmount(date: LocalDate): Double? =
        db.rawQuery(
            "SELECT work_amount FROM work_record WHERE work_date = ?",
            arrayOf(date.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else null }

    fun monthTotal(ym: YearMonth): Double =
        sumBetween(ym.atDay(1).toString(), ym.atEndOfMonth().toString())

    fun yearTotal(year: Int): Double =
        sumBetween("$year-01-01", "$year-12-31")

    /** 任意区间的工天合计，给"算账"用 */
    fun rangeTotal(from: LocalDate, to: LocalDate): Double =
        if (from.isAfter(to)) 0.0 else sumBetween(from.toString(), to.toString())

    /**
     * 把某一天设成 amount。覆盖语义 —— 重复调用结果相同，点不出 2 天。
     *
     * 用「先 UPDATE，没改到行再 INSERT」而不是 UPSERT：
     * UPSERT(ON CONFLICT DO UPDATE) 要 SQLite 3.24，即 Android 11 以上；
     * 这个写法在 Android 8 上也能跑，且不会像 INSERT OR REPLACE 那样
     * 先删后插（会换 id、会触发外键级联）。
     */
    fun setDay(date: LocalDate, amount: Double) {
        val key = date.toString()
        val cv = ContentValues().apply {
            put("work_amount", amount)
        }
        db.beginTransaction()
        try {
            val changed = db.update("work_record", cv, "work_date = ?", arrayOf(key))
            if (changed == 0) {
                db.insertOrThrow(
                    "work_record", null,
                    ContentValues().apply {
                        put("work_date", key)
                        put("work_amount", amount)
                    }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearDay(date: LocalDate) {
        db.delete("work_record", "work_date = ?", arrayOf(date.toString()))
    }

    private fun sumBetween(from: String, to: String): Double =
        db.rawQuery(
            "SELECT COALESCE(SUM(work_amount), 0) FROM work_record " +
                "WHERE work_date BETWEEN ? AND ?",
            arrayOf(from, to)
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "jigong.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE work_record (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  work_date   TEXT    NOT NULL UNIQUE,
                  work_amount REAL    NOT NULL DEFAULT 1.0
                              CHECK (work_amount > 0 AND work_amount <= 3),
                  note        TEXT
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 只有一张表，v1 没有迁移
        }
    }
}

package com.jigong.ui

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jigong.data.DayRecord
import com.jigong.data.WorkRepo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jigong.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Duration
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Locale

private val WEEK_HEAD = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 备注最多 200 字。
 *
 * 不是技术限制（TEXT 列能存很多），是用途限制：这行字的用处是「想起来那天干了啥」，
 * 二十个字足够；一旦有人往里面粘整段文字，日历格子上的小圆点、分享图、以后可能的
 * 搜索全都要跟着处理长文本。200 是一个够用又不会失控的数。
 */
private const val NOTE_MAX = 200

/**
 * 正在编辑的那一天，以及**那天实际的记录**（工天 + 备注）。
 *
 * 为什么记录要跟着日期一起带过来，而不是在 DaySheet 里用 `days[date]` 查：
 * `days` 只装当前显示那个月的记录。用户把日历翻到 9 月、再点底部那颗
 * 「今天已记 1 天」，date 是 10 月 28 日，`days[10-28]` 必然是 null ——
 * 弹层显示「还没记」、两个选项都不高亮、「删除这天的记录」还是灰的，
 * 结果就是今天这条记录根本删不掉。真机已复现。
 *
 * 这是上一轮修主按钮时漏掉的同一个坑：凡是「今天」这个日期，都不能从
 * 当月 map 里取，必须用单独查出来的 todayRecord。
 *
 * 备注跟着 record 一起带，理由和工天完全一样：翻到上个月时点开「今天已记 1 天」，
 * 输入框里必须是今天那条备注。从 days[今天] 取同样会拿到 null，
 * 用户看到空格子、随手一保存，就把原来的备注覆盖没了。
 */
private data class Editing(val date: LocalDate, val record: DayRecord?)

/**
 * 一次 reload 要刷新的全部东西，一起从 IO 线程搬回来。
 *
 * 用 data class 而不是 Triple：工天现在带着备注，第四个字段（今天的记录）
 * 再往 Triple 里塞就变成嵌套，读的时候 `r.first` / `r.third` 谁也记不住是哪个。
 */
private data class Snapshot(
    val days: Map<LocalDate, DayRecord>,
    val yearDays: Double,
    val today: DayRecord?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repo: WorkRepo) {
    val ctx = LocalContext.current

    /*
     * 「今天」必须是会变的状态，不能 remember 一次就完事。
     *
     * 原来写的是 `val today = remember { LocalDate.now() }` —— 只有进程重建才会重算。
     * 手机把 app 挂在后台过一夜、第二天早上直接切回来，进程没死，today 还是昨天：
     *   · 今天那一格被判成「未来」，点一下弹「还没到日子」，记不了工；
     *   · 主按钮写着「今天记一天」，点下去写进库里的是**昨天**，把昨天的记录改掉。
     * 两个都不报错，属于最难查的那种 bug。
     *
     * 补两条路：回到前台算一次（覆盖「挂后台过夜」），睡到 24 点再算一次
     * （覆盖「一直开着没关」）。两条都走 refreshToday()，逻辑只有一份。
     */
    var today by remember { mutableStateOf(LocalDate.now()) }
    var ym by remember { mutableStateOf(YearMonth.from(today)) }

    fun refreshToday() {
        val d = LocalDate.now()
        if (d == today) return
        // 用户正看着「当前月」时跟着翻到新月份；他要是正在翻历史记录就别打扰
        if (ym == YearMonth.from(today)) ym = YearMonth.from(d)
        today = d
    }

    // LocalLifecycleOwner.current 是 @Composable 读取，必须在组合里取出来，
    // 不能写在 DisposableEffect 的 lambda 里（那里不是组合上下文）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refreshToday()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(Duration.between(now, nextMidnight).toMillis() + 1_000)
            refreshToday()
        }
    }

    var days by remember { mutableStateOf<Map<LocalDate, DayRecord>>(emptyMap()) }
    var yearDays by remember { mutableDoubleStateOf(0.0) }
    // 「今天」单独存一份：days 只有当前显示那个月的记录，
    // 翻到别的月份时从里面取今天必然是 null（见 WorkRepo.dayRecord 的注释）。
    // 工天和备注装在一起，因为它们在库里本来就是同一行 —— 分开存就有对不齐的机会。
    var todayRecord by remember { mutableStateOf<DayRecord?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }

    // 算账
    val prefs = remember { ctx.getSharedPreferences("jigong", android.content.Context.MODE_PRIVATE) }
    var showCalc by remember { mutableStateOf(false) }
    var calcFrom by remember { mutableStateOf(YearMonth.from(today).atDay(1)) }
    var calcTo by remember { mutableStateOf(today) }
    var rate by remember { mutableStateOf(prefs.getString("rate", "").orEmpty()) }

    // 更新
    var showUpdate by remember { mutableStateOf(false) }
    var updateAddr by remember { mutableStateOf(prefs.getString("update_addr", "").orEmpty()) }
    var newVer by remember { mutableStateOf<Updater.Info?>(null) }

    // 启动时悄悄问一次电脑有没有新版本。
    // 超时压到 1.5 秒、失败完全静默 —— 没连电脑的人不该感到任何变化。
    LaunchedEffect(Unit) {
        val a = prefs.getString("update_addr", "").orEmpty()

        // 先擦屁股：上次装着装着没走完，缓存里可能躺着 18MB 的安装包
        withContext(Dispatchers.IO) { runCatching { Updater.purgeStale(ctx) } }

        if (a.isNotBlank()) {
            val info = withContext(Dispatchers.IO) {
                runCatching { Updater.check(a, 1500) }.getOrNull()
            }
            if (info != null && info.versionCode > appVersionCode(ctx)) newVer = info
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    /**
     * 三条查询一起搬到 IO 线程。
     *
     * 原来这三行是直接在主线程跑的：LaunchedEffect 和点击回调都在主线程，
     * 于是每次翻月、每记一笔都是三次同步 SQLite 查询。这个表最多 31 行，单次 1~2ms，
     * 肉眼看不出来 —— 但「看不出来」不等于「可以这么写」：数据库第一次打开要建文件、
     * 建表、编译语句，那一下是几十毫秒级，正好发生在冷启动第一帧。
     *
     * 官方对主线程的要求没有例外条款：阻塞 I/O 不进主线程。
     * 三个查询一起丢进一个 withContext，只切一次线程。
     */
    suspend fun reload() {
        val m = ym
        val t = today
        val r = withContext(Dispatchers.IO) {
            Snapshot(repo.monthMap(m), repo.yearTotal(m.year), repo.dayRecord(t))
        }
        days = r.days
        yearDays = r.yearDays
        todayRecord = r.today
    }

    // 换月份要重load；跨天之后「今天」变了也要重load（todayRecord 和格子高亮都靠它）
    LaunchedEffect(ym, today) { reload() }

    /**
     * 唯一写入口。覆盖语义，永远不会累加出 2 天。
     *
     * 撤销用的「原来是多少」一定要从库里读，不能从 days 里取：
     * days 只装了当前显示那个月的记录，翻月之后从这里取会拿到 null ——
     * 于是「撤销」会把本来记着的半天整个删掉，而不是改回去。
     *
     * 读旧值 + 写入放进同一个 IO 块：原来是两行同步代码，一起搬走顺序语义不变，
     * 但不再占主线程。弹层由调用方立刻关掉，不等这一次写库。
     */
    fun apply(date: LocalDate, amount: Double?, note: String?) {
        scope.launch {
            val old = try {
                withContext(Dispatchers.IO) {
                    val o = repo.dayRecord(date)
                    if (amount == null) repo.clearDay(date) else repo.setDay(date, amount, note)
                    o
                }
            } catch (e: Exception) {
                /*
                 * 写库失败（存储写满、数据库文件坏了）不能让进程直接崩。
                 *
                 * scope.launch 里未捕获的异常会一路抛到主线程把 app 杀掉：用户点完
                 * 「一天」，屏幕一闪回到桌面，再打开发现什么都没记上 —— 既没有解释
                 * 也没有补救办法。这里至少要说一句人话，并且不弹"撤销"（没写成，
                 * 撤销是假的）。
                 */
                android.util.Log.w("jigong", "写库失败", e)
                Toast.makeText(ctx, "没记上：${e.message ?: "写入失败"}", Toast.LENGTH_LONG).show()
                return@launch
            }
            reload()
            val msg = if (amount == null) {
                "${date.monthValue}月${date.dayOfMonth}日 已清除"
            } else {
                "${date.monthValue}月${date.dayOfMonth}日 记 ${fmtDays(amount)} 天"
            }
            val r = snackbar.showSnackbar(
                message = msg,
                actionLabel = "撤销",
                duration = SnackbarDuration.Short
            )
            if (r == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) {
                    // 撤销要把**备注也还原**。只还原工天的话，用户改完备注点撤销，
                    // 数字回到原样、备注却已经变了 —— 一个"撤销"只撤了一半。
                    if (old == null) repo.clearDay(date) else repo.setDay(date, old.amount, old.note)
                }
                reload()
            }
        }
    }

    /**
     * 只存备注，不动工天。
     *
     * 场景：用户打开某天、在输入框里补了一句备注、然后直接把弹层划走。
     * 这条路径没有"记 1 天"那种明确动作，弹"已记 1 天"的提示是错的；
     * 什么都不做又会让用户以为白写了。所以静默写库 + 静默刷新，
     * 只在日历格子上多出一个小圆点 —— 那就是"存进去了"的回执。
     *
     * 失败也不弹 Toast：备注是附带的，写不进去不该打断记工。
     */
    fun saveNote(date: LocalDate, note: String?) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.setNote(date, note) } }
                .onFailure { android.util.Log.w("jigong", "备注没写进去", it) }
            reload()
        }
    }

    /** 输入框里的东西和库里不一样才写。空串、纯空格、只有首尾空格都算"没变"。 */
    fun noteChanged(before: String?, after: String): Boolean =
        before.orEmpty().trim() != after.trim()

    val monthDays = days.values.sumOf { it.amount }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = {
                // 往上抬，别把"今天记一天"这颗主按钮挡住
                SnackbarHost(hostState = snackbar, modifier = Modifier.padding(bottom = 92.dp))
            },
            containerColor = Palette.bg
        ) { pad ->
            /*
             * 整页放进可滚动容器。
             *
             * 原来是 `Column(fillMaxSize)` + `Spacer(weight(1f))`：内容装不下时 weight 会被
             * 压成 0，但**后面的子项照样往下排**，超出屏幕的部分直接被裁掉，而且没有任何
             * 滚动可以救。会撞上的是这两种情况：
             *
             *   · 别人家的小屏手机。360×640dp 的机器在 1.0 字号下就已经装不下，而底部那颗
             *     「今天记一天」正好是被裁掉的那一块。这个 app 是要发给工友用的，
             *     不能只在 800dp 高的大屏上成立。
             *   · 系统字号放大到 1.3 倍以上（老人手机几乎都会调大），内容同样超出。
             *
             * 横屏已经不算了 —— MainActivity 在清单里锁了竖屏，那个场景从源头不存在。
             *
             * 官方《自适应布局》的要求是：内容可能高于视口时必须可滚动，而不是靠固定尺寸
             * 「大部分机器够用」。这里用 BoxWithConstraints 拿视口高度，再 heightIn(min=视口)：
             *   · 内容比视口矮 → 列被撑到视口高，SpaceBetween 把「合计 + 主按钮」顶到底部，
             *     观感和原来一模一样（不是改成顶部堆叠）；
             *   · 内容比视口高 → 列高 = 内容高，整页可滚，主按钮滑一下就能看到。
             */
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = Dimen.pagePadding)
            ) {
                val viewport = maxHeight
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = viewport),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        MonthHeader(
                            ym = ym,
                            newVersion = newVer?.versionName,
                            onPrev = { ym = ym.minusMonths(1) },
                            onNext = { ym = ym.plusMonths(1) },
                            onUpdate = { showUpdate = true }
                        )

                        WeekHead()

                        CalendarGrid(
                            ym = ym,
                            today = today,
                            days = days,
                            // 格子里的日期一定属于当前显示这个月，所以 days[它] 就是准确值
                            onPick = { d -> editing = Editing(d, days[d]) }
                        )

                        Text(
                            text = "点日期记工，顺手写备注",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 15.sp,
                            color = Palette.inkSoft
                        )
                    }

                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "本月 ${fmtDays(monthDays)} 天",
                                    fontSize = Dimen.bigNumber,
                                    fontWeight = FontWeight.Bold,
                                    color = Palette.ink
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    // 必须跟着「显示的那个年份」写。原来固定写「今年」，
                                    // 用户翻到 2025 年时数字是 2025 全年，标签却还是「今年」，
                                    // 两句话互相矛盾 —— 老人看到会以为数字错了。
                                    text = if (ym.year == today.year) {
                                        "今年 ${fmtDays(yearDays)} 天"
                                    } else {
                                        "${ym.year} 年 ${fmtDays(yearDays)} 天"
                                    },
                                    fontSize = Dimen.body,
                                    color = Palette.inkSoft
                                )
                            }
                            TextButton(
                                onClick = { showCalc = true },
                                modifier = Modifier.height(56.dp)
                            ) {
                                Text("算账", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.accent)
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        TodayButton(
                            amount = todayRecord?.amount,
                            onClick = {
                                // 用 todayRecord，不是 days[today] —— 用户可能正翻在别的月份
                                if (todayRecord == null) apply(today, 1.0, null)
                                else editing = Editing(today, todayRecord)
                            }
                        )

                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }

        editing?.let { e ->
            DaySheet(
                date = e.date,
                amount = e.record?.amount,
                note = e.record?.note,
                onPick = { a, n ->
                    // 工天和备注一次写完：分两次写会出现"天数变了备注没变"的中间态，
                    // 而撤销只能还原一次
                    apply(e.date, a, n)
                    editing = null
                },
                onClear = { apply(e.date, null, null); editing = null },
                onDismiss = { text ->
                    /*
                     * 用户只补了备注、没点半天/一天，直接把弹层划走 —— 这条路径最常见
                     * （"今天记了一天，顺手写句加班"）。这里静默存下来。
                     *
                     * 为什么不在输入框旁边放一颗"保存备注"按钮：老人不会去找它，
                     * 写了字没点按钮就关掉，文字白写还查不出原因。宁可少一个按钮，
                     * 也不让"填过的东西消失"这种事发生。
                     */
                    val r = e.record
                    if (r != null) {
                        if (noteChanged(r.note, text)) saveNote(e.date, text)
                    } else if (text.isNotBlank()) {
                        /*
                         * 输入框现在一进来就有，于是多出一条以前不存在的路径：
                         * 这天还没记工、用户先写了备注、然后直接划走。
                         *
                         * 这条备注存不进去，而且是**不能**存：表里 work_amount 是
                         * NOT NULL DEFAULT 1.0，为了装一句备注插一行，那天就凭空多出
                         * 一天工，月底算账直接算错。宁可提示一句，也不能让账错。
                         *
                         * 提示而不是静默丢弃：老人打完字、屏幕上什么都没发生，
                         * 他会以为存上了 —— 下次想起来翻记录才发现没了。
                         */
                        scope.launch {
                            snackbar.showSnackbar(
                                message = "还没选半天或一天，备注没存上",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                    editing = null
                }
            )
        }

        if (showCalc) {
            // 原来是 remember(calcFrom, calcTo) { repo.rangeTotal(...) } —— 在**组合期**查数据库。
            // 能跑，但组合函数应该是纯的：I/O 放进来，一旦哪天卡片重组的时机变了就是一次主线程
            // 卡顿，而且异常会直接把界面炸掉。改成副作用里查。
            var calcDays by remember { mutableStateOf<Double?>(null) }
            LaunchedEffect(calcFrom, calcTo) {
                calcDays = withContext(Dispatchers.IO) { repo.rangeTotal(calcFrom, calcTo) }
            }
            CalcSheet(
                from = calcFrom,
                to = calcTo,
                days = calcDays,
                rate = rate,
                onRateChange = { s ->
                    if (s.count { it == '.' } <= 1 && s.all { it.isDigit() || it == '.' }) {
                        rate = s
                        prefs.edit().putString("rate", s).apply()
                    }
                },
                onPickFrom = {
                    pickDate(ctx, calcFrom) { picked ->
                        calcFrom = picked
                        if (calcTo.isBefore(picked)) calcTo = picked
                    }
                },
                onPickTo = {
                    pickDate(ctx, calcTo) { picked ->
                        calcTo = picked
                        if (calcFrom.isAfter(picked)) calcFrom = picked
                    }
                },
                onDismiss = { showCalc = false }
            )
        }

        if (showUpdate) {
            UpdateSheet(
                addr = updateAddr,
                onAddrChange = {
                    updateAddr = it
                    prefs.edit().putString("update_addr", it.trim()).apply()
                },
                onDismiss = { showUpdate = false },
                monthTitle = "${ym.year} 年 ${ym.monthValue} 月",
                monthDays = monthDays,
                onShareMonth = {
                    // 先把面板关掉再去调系统分享：分享面板会盖在 Dialog 上面，
                    // 回来的时候留着一个半透明遮罩很怪
                    showUpdate = false
                    // 画图 + 压 PNG 在 IO 线程（1080×1080 位图，别卡主线程）
                    scope.launch {
                        runCatching { ShareImage.share(ctx, ym, monthDays) }
                            .onFailure {
                                Toast.makeText(ctx, "生成分享图失败：${it.message}", Toast.LENGTH_LONG).show()
                            }
                    }
                },
                newVersion = newVer?.versionName
            )
        }
    }
}

// ---------------------------------------------------------------- 顶栏

@Composable
private fun MonthHeader(
    ym: YearMonth,
    newVersion: String?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onUpdate: () -> Unit
) {
    /*
     * 顶栏只有一行：‹ 年月 ›  +  版本气泡。
     *
     * 为什么不再放「分享」：这台机密度 560dpi，屏宽实际只有 360dp，去掉左右
     * pagePadding 只剩 328dp。两个箭头 104 + 年月 123 = 227，留给右侧的本就只有 101dp。
     * 之前硬塞「分享」+「版本」的结果是年月被顶到换行；加 maxLines=1 又变成截断 ——
     * M3 顶栏规范明确禁止「wrap / truncate / shrink to fit」，官方给出的解法是
     * 把放不下的操作收起来（overflow / 移到别处），而不是压字号、切文字。
     * 所以「分享这个月的工天」移进了更新与分享面板，做成整行大按钮
     * （顺带满足适老化规范：主要按钮点击区 ≥44dp，老人手指按得准）。
     *
     * 版本文案也用短的「v1.9」而不是「更新 · v1.9」：系统字体放大到 1.3 倍时，
     * 多两个字就会把年月挤没。
     */
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArrowBtn("‹", onPrev)
        AutoFitTitle(
            text = "${ym.year}年${ym.monthValue}月",
            modifier = Modifier.weight(1f)
        )
        ArrowBtn("›", onNext)

        // 右上角是「三横」菜单，不是版本号。
        // 版本号本身对使用者没有意义（他不需要知道 1.9 还是 2.0，只关心「有没有新版」），
        // 把它放在顶栏最贵的位置上，既占宽度又没人看懂。版本信息放在面板第一行。
        // 有新版本时在按钮右上角挂一个红点 —— 这才是使用者要的信号。
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                // 图标是自己画的 Canvas，语义树里什么都没有 —— 读屏会念「按钮」或者干脆跳过。
                // 红点也是个纯视觉信号，所以「有新版本」要写进描述里。
                .clickable(
                    onClickLabel = "打开更新与分享",
                    onClick = onUpdate
                )
                .semantics {
                    contentDescription = if (newVersion != null) {
                        "更新与分享，有新版本 $newVersion"
                    } else {
                        "更新与分享"
                    }
                    role = Role.Button
                },
            contentAlignment = Alignment.Center
        ) {
            // 手画三条横线，不用图标库：省一个依赖，也能保证粗细/圆角跟自己想要的一致
            Canvas(Modifier.size(width = 22.dp, height = 16.dp)) {
                val thick = 2.5.dp.toPx()
                val gap = (size.height - thick * 3) / 2
                repeat(3) { i ->
                    val y = i * (thick + gap) + thick / 2
                    drawLine(
                        color = Palette.ink,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = thick,
                        cap = StrokeCap.Round
                    )
                }
            }
            if (newVersion != null) {
                // 红点贴着右上角，8dp 直径：够显眼，又不至于像个错号
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 9.dp, end = 9.dp)
                        .size(9.dp)
                        .background(Palette.danger, CircleShape)
                )
            }
        }
    }
}

/**
 * 年月标题：放不下就按 1sp 步长往下缩，而不是把最后一个字切掉。
 *
 * 实测（360dp 宽、密度 560dpi）：两个箭头各 52dp + 右上菜单 48dp，标题只剩 176dp。
 * 系统字号调到 200%（Android 14 起支持非线性放大到 200%）时 "2026年10月" 需要约 220dp，
 * 之前 maxLines=1 + softWrap=false 的结果是「月」被裁成半个字 —— 顶栏上最该看清的三个字
 * 反而坏了。
 *
 * 为什么不是直接用 fontScale 算：官方明确说了从 Android 14 起字体放大是非线性的，
 * Configuration.fontScale / scaledDensity 不再能作为等式使用，只能实测。
 * 所以这里用 TextMeasurer 真的量一遍宽度（measure 走的是同一个 Density，
 * 已经包含系统字号缩放），放不下才降 1sp，最多降到 13sp。
 *
 * 200% 下实测缩到约 17sp，等效观感 34sp，仍远大于正文 18sp —— 符合
 * 「文字必须能放大到 200% 且不丢失内容」，只是不再无脑放大到被裁掉。
 */
@Composable
private fun AutoFitTitle(text: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val maxPx = with(density) { maxWidth.toPx() }
        // 以「文字 / 可用宽度 / 密度（含系统字号）」为缓存键：只有这三样变了才重新量
        val size = remember(text, maxPx, density) {
            // TextUnit 之间没有减号，所以拿 .value 在 sp 的数值上走，最后再 .sp 回去
            var v = Dimen.title.value
            while (v > 13f &&
                measurer.measure(
                    text = text,
                    style = TextStyle(fontSize = v.sp, fontWeight = FontWeight.Bold)
                ).size.width > maxPx
            ) {
                v -= 1f
            }
            v.sp
        }
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = size,
            fontWeight = FontWeight.Bold,
            color = Palette.ink,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun ArrowBtn(symbol: String, onClick: () -> Unit) {
    // ‹ › 这两个字符读屏会念成「小于号」「大于号」，必须给 contentDescription
    val label = if (symbol == "‹") "上个月" else "下个月"
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 34.sp, color = Palette.ink, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- 日历

@Composable
private fun WeekHead() {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        WEEK_HEAD.forEach {
            Text(
                text = it,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = Dimen.weekday,
                color = Palette.inkSoft
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    ym: YearMonth,
    today: LocalDate,
    days: Map<LocalDate, DayRecord>,
    modifier: Modifier = Modifier,
    onPick: (LocalDate) -> Unit
) {
    val cells = ArrayList<LocalDate?>(42)
    repeat(ym.atDay(1).dayOfWeek.value - 1) { cells.add(null) }   // 周一为一周起始
    for (d in 1..ym.lengthOfMonth()) cells.add(ym.atDay(d))
    while (cells.size % 7 != 0) cells.add(null)

    Column(modifier.fillMaxWidth()) {
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().height(Dimen.cellHeight)) {
                week.forEach { date ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                amount = days[date]?.amount,
                                hasNote = days[date]?.note != null,
                                isToday = date == today,
                                isFuture = date.isAfter(today),
                                onPick = onPick
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    amount: Double?,
    hasNote: Boolean,
    isToday: Boolean,
    isFuture: Boolean,
    onPick: (LocalDate) -> Unit
) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(12.dp)

    val bg = when {
        amount != null -> Palette.accent
        isToday -> Palette.accentSoft
        else -> Color.Transparent
    }
    val fg = when {
        amount != null -> Color.White
        isFuture -> Palette.hint
        isToday -> Palette.accent
        else -> Palette.ink
    }

    // 读屏要念出「几号 + 记了几天」，光念一个「5」老人根本没法用。
    // 颜色和底部白杠都是纯视觉信号，语音里必须补回来。
    val desc = buildString {
        append("${date.monthValue}月${date.dayOfMonth}日")
        when {
            isFuture -> append("，还没到")
            amount == null -> append("，还没记")
            amount < 1.0 -> append("，记了半天")
            else -> append("，记了${fmtDays(amount)}天")
        }
        // 右上角那个小点必须用语音补回来：它没有任何文字，读屏用户不可能知道
        if (hasNote) append("，有备注")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .clickable(
                onClickLabel = if (isFuture) null else "记工",
                onClick = {
                    if (isFuture) {
                        Toast.makeText(ctx, "还没到日子", Toast.LENGTH_SHORT).show()
                    } else {
                        onPick(date)
                    }
                }
            )
            .semantics {
                contentDescription = desc
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        /*
         * 点击区 = 整个格子（约 51 × 68dp），不是里面那颗 48dp 的方块。
         *
         * 行高 68dp 而方块只有 48dp，原来上下各有 10dp 是点不动的死区：老人手指粗，
         * 一偏就落在这条缝里，屏幕毫无反应，他只会觉得"这手机又卡了"。
         * 48dp 本身是够 44dp 的合规线，但合规不等于好按 —— 这几颗数字是整个界面
         * 里最常被点的东西（补记、改半天都靠它），能放大就放大。
         *
         * 视觉尺寸不变：里层那颗药丸还是 48dp，只有热区变大。
         */
        Box(
            Modifier
                .size(Dimen.cellSize)
                .clip(shape)
                .background(bg)
                .then(
                    if (isToday && amount == null) Modifier.border(2.dp, Palette.accent, shape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${date.dayOfMonth}",
                fontSize = Dimen.dayCell,
                color = fg,
                fontWeight = FontWeight.Medium
            )
            // 半天：底部一条白杠。形状 + 颜色双通道，不只靠深浅
            if (amount != null && amount < 1.0) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .width(18.dp)
                        .height(3.dp)
                        .background(Color.White, RoundedCornerShape(2.dp))
                )
            }
        }

        /*
         * 有备注：格子右下角一个小圆点。
         *
         * 画在**药丸外面**、格子的下方那条 10dp 空带里，不是画进药丸 ——
         * 药丸只有 48dp，200% 字号下「28」这两个数字几乎顶满，圆点塞进去会跟字形
         * 叠在一起，两个白色色块糊成一团。那条空带本来就是点得动但看不见的死区，
         * 拿来放标记正好。
         *
         * 为什么要有这个点：备注是"写进去就再也看不见"的东西 —— 不点开那一天，
         * 用户根本不知道哪天写过。有个点至少能让他想起来"这天有东西"。
         * 它是纯视觉信号，语音里由 contentDescription 的「有备注」补回来。
         */
        if (hasNote) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 2.dp)
                    .size(6.dp)
                    .background(Palette.accent, CircleShape)
            )
        }
    }
}

// ---------------------------------------------------------------- 主按钮

@Composable
private fun TodayButton(amount: Double?, onClick: () -> Unit) {
    val recorded = amount != null
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimen.primaryButtonHeight),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (recorded) Palette.btnIdle else Palette.accent,
            contentColor = if (recorded) Palette.ink else Color.White
        )
    ) {
        Text(
            text = if (recorded) "今天已记 ${fmtDays(amount!!)} 天" else "今天记一天",
            fontSize = Dimen.primaryButton,
            fontWeight = FontWeight.Bold
        )
    }
}

// ---------------------------------------------------------------- 记工弹层

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySheet(
    date: LocalDate,
    amount: Double?,
    note: String?,
    onPick: (Double, String) -> Unit,
    onClear: () -> Unit,
    onDismiss: (String) -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    /*
     * 输入框里的内容只能由这个弹层自己拿住。
     *
     * 用 remember(date) 而不是 remember：这个 Composable 在 composition 里始终是
     * 「同一个位置」，换一天时不会重建 —— 不按 date 重置的话，用户 26 号写了备注、
     * 关掉再点 27 号，输入框里还挂着 26 号那句，一点「一天」就写到 27 号上了。
     * 连着补几天工是这app最常见的用法，这条路径必然被踩到。
     */
    var text by remember(date) { mutableStateOf(note.orEmpty()) }

    ModalBottomSheet(
        onDismissRequest = { onDismiss(text) },
        sheetState = state,
        containerColor = Palette.sheetBg
    ) {
        /*
         * 整层可滚 + imePadding：键盘一弹出来，屏幕下半截就被占了。
         * 弹层里现在有一个多行输入框，200% 系统字号下再加上键盘，
         * 「删除这天的记录」会被顶到键盘底下 —— 和 1.15 那次「拉不到底」
         * 是同一类问题，宁可让它能滚，也不赌每台机器都够高。
         */
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "${date.monthValue}月${date.dayOfMonth}日 ${weekdayCn(date)}",
                fontSize = Dimen.title,
                fontWeight = FontWeight.Bold,
                color = Palette.ink
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (amount == null) "还没记" else "已记 ${fmtDays(amount)} 天",
                fontSize = Dimen.body,
                color = Palette.inkSoft
            )

            /*
             * 备注在按钮**上面**，而且一进来就有 —— 这是「记工的时候就能填备注」。
             *
             * 1.16 里输入框在下面、且只有记过工才出现，于是想写备注的人被迫走两遍：
             * 点日期 → 点「一天」（弹层关掉）→ 再点同一天 → 才有输入框。
             * 老人根本不会想到「再点一次」，他会以为这功能就是不能写。
             *
             * 放到按钮上面，整件事就变成一屏一次：写备注（可以不写）→ 点半天/一天，
             * 工天和备注一起落库（onPick 两个参数一起提交，本来就是这么设计的）。
             * 只记工不写备注的人路径完全不变：输入框空着，直接点「一天」，弹层关掉。
             *
             * 为什么不改成「点完一天不关弹层、停在原地等填」：那要让老人多点一次
             * 「完成」才能收起来。记工是这个 app 最常做的动作，不能为备注加一步。
             */
            Text(
                text = "备注（可不填）",
                fontSize = Dimen.body,
                color = Palette.inkSoft
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= NOTE_MAX) text = it },
                placeholder = {
                    Text("比如：加班到十点、下雨只干半天", fontSize = 17.sp, color = Palette.hint)
                },
                textStyle = TextStyle(fontSize = 20.sp, color = Palette.ink),
                // 1 行起步、4 行封顶：字号调到 2 倍时一行只放得下十来个字，
                // 给到 4 行才够写「加班到十点」这种长度，再多就该收起来
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetBtn("半天", amount == 0.5, Modifier.weight(1f)) { onPick(0.5, text) }
                SheetBtn("一天", amount == 1.0, Modifier.weight(1f)) { onPick(1.0, text) }
            }

            Spacer(Modifier.height(10.dp))

            TextButton(
                onClick = onClear,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimen.sheetButtonHeight),
                enabled = amount != null
            ) {
                Text(
                    text = "删除这天的记录",
                    fontSize = Dimen.sheetButton,
                    color = if (amount != null) Palette.danger else Palette.hint
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetBtn(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .heightIn(min = Dimen.sheetButtonHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.accent else Palette.btnIdle)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = Dimen.sheetButton,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else Palette.ink
        )
    }
}

// ---------------------------------------------------------------- 算账弹层

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalcSheet(
    from: LocalDate,
    to: LocalDate,
    days: Double?,
    rate: String,
    onRateChange: (String) -> Unit,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val rateValue = rate.toDoubleOrNull()
    val total = if (days == null || rateValue == null) null else days * rateValue

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Palette.sheetBg
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text("算账", fontSize = Dimen.title, fontWeight = FontWeight.Bold, color = Palette.ink)
            Spacer(Modifier.height(14.dp))

            DateRow("从", from, onPickFrom)
            Spacer(Modifier.height(8.dp))
            DateRow("到", to, onPickTo)

            Spacer(Modifier.height(14.dp))
            Text("这期间 ${days?.let { fmtDays(it) } ?: "…"} 天", fontSize = Dimen.body, color = Palette.inkSoft)

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = rate,
                onValueChange = onRateChange,
                label = { Text("日薪（元/天）", fontSize = 17.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = TextStyle(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.ink
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(18.dp))
            Text("合计", fontSize = Dimen.body, color = Palette.inkSoft)
            Text(
                text = total?.let { "¥" + money(it) } ?: "—",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = if (total == null) Palette.hint else Palette.accent
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DateRow(label: String, date: LocalDate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.btnIdle)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = Dimen.body, color = Palette.inkSoft)
        Spacer(Modifier.width(14.dp))
        Text(
            text = "%d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth),
            modifier = Modifier.weight(1f),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Palette.ink
        )
        Text("›", fontSize = 24.sp, color = Palette.inkSoft)
    }
}

/** 系统原生日期选择器：老人熟悉，也不用自己画 */
private fun pickDate(
    ctx: android.content.Context,
    initial: LocalDate,
    onPicked: (LocalDate) -> Unit
) {
    DatePickerDialog(
        ctx,
        { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth
    ).show()
}

/** 6600.0 -> "6,600"；6600.5 -> "6,600.50" */
private fun money(v: Double): String {
    val s = String.format(Locale.CHINA, "%,.2f", v)
    return if (s.endsWith(".00")) s.dropLast(3) else s
}

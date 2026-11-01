package tw.local.seaweather

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.time.OffsetDateTime
import java.util.concurrent.Executors

private const val PREFS = "settings"
private const val KEY_API = "api_key"
private const val KEY_COAST = "coast"
private const val KEY_HISTORY = "history"
private const val SEA_TTL_MS = 30 * 60 * 1000L

private data class CoastTown(val county: String, val name: String, val dataset: String, val tideId: String, val zone: String) {
    val label get() = county + name
}

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var picker: Button
    private lateinit var content: LinearLayout

    private val coastTowns: List<CoastTown> by lazy {
        assets.open("coast_towns.tsv").bufferedReader().readLines().map {
            val (county, name, dataset, tideId, zone) = it.split('\t')
            CoastTown(county, name, dataset, tideId, zone)
        }
    }

    private var seaZones: Map<String, List<SeaDay>> = emptyMap()
    private var seaZonesAt = 0L
    /** Bumped per load so a slow response cannot overwrite a newer selection. */
    private var generation = 0

    private val isDark
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    private val pageColor get() = if (isDark) 0xFF10181F.toInt() else 0xFFF2F6F9.toInt()
    private val cardColor get() = if (isDark) 0xFF1C2730.toInt() else Color.WHITE
    private val ink get() = if (isDark) 0xFFE8EEF2.toInt() else 0xFF17232C.toInt()
    private val muted get() = if (isDark) 0xFF9FB0BC.toInt() else 0xFF5A6B77.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        load()
        if (apiKey().isEmpty()) askKey()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun apiKey() = prefs.getString(KEY_API, "").orEmpty().ifEmpty { BUILT_IN_API_KEY }

    private fun buildLayout(): View {
        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageColor)
            fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(4))
            addView(
                TextView(context).apply {
                    text = getString(R.string.app_name)
                    textSize = 22f
                    setTextColor(ink)
                    typeface = Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(flatButton("更新") { load(force = true) })
            addView(flatButton("授權碼") { askKey() })
        }
        picker = Button(this).apply {
            isAllCaps = false
            textSize = 16f
            setOnClickListener { openPicker() }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        rootView.addView(header)
        rootView.addView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(6), dp(4))
                addView(picker, LinearLayout.LayoutParams(0, -2, 1f))
                addView(flatButton("歷史") { openHistory() })
            },
        )
        rootView.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        refreshPicker()
        return rootView
    }

    private fun flatButton(label: String, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun refreshPicker() {
        picker.text = "📍 " + coastTown().label
    }

    private fun coastTown(): CoastTown {
        val label = prefs.getString(KEY_COAST, null)
        return coastTowns.firstOrNull { it.label == label }
            ?: coastTowns.first { it.label == "基隆市中正區" }
    }

    private fun openPicker() {
        val counties = coastTowns.map { it.county }.distinct()
        choose("選擇縣市", counties.map { it to it }) { county ->
            val options = coastTowns.filter { it.county == county }.map { it.name to it.label }
            choose("選擇 $county 的沿海鄉鎮", options) { save(KEY_COAST, it) }
        }
    }

    private fun history() =
        prefs.getString(KEY_HISTORY, "").orEmpty().split('\n').filter { it.isNotEmpty() }

    private fun openHistory() {
        val places = history().filter { place -> coastTowns.any { it.label == place } }
        if (places.isEmpty()) return toast("還沒有查詢紀錄")
        AlertDialog.Builder(this)
            .setTitle("查詢歷史")
            .setItems(places.toTypedArray()) { _, i -> save(KEY_COAST, places[i]) }
            .setNeutralButton("清除紀錄") { _, _ ->
                prefs.edit().remove(KEY_HISTORY).apply()
                toast("已清除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        load()
    }

    private fun choose(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
        var shown = options
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.first }.toMutableList())
        val list = ListView(this).apply { this.adapter = adapter }
        val search = EditText(this).apply {
            hint = "搜尋"
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val q = s.toString().trim().replace('台', '臺')
                    shown = options.filter { it.first.replace('台', '臺').contains(q, ignoreCase = true) }
                    adapter.clear()
                    adapter.addAll(shown.map { it.first })
                }
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(search)
            addView(list, LinearLayout.LayoutParams(-1, dp(380)))
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(box)
            .setNegativeButton("取消", null).create()
        list.setOnItemClickListener { _, _, i, _ ->
            dialog.dismiss()
            onPick(shown[i].second)
        }
        dialog.show()
    }

    private fun askKey() {
        val input = EditText(this).apply {
            hint = "CWA-XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setText(apiKey())
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("輸入氣象署授權碼")
            .setMessage("授權碼只存在這支手機，用來向氣象資料開放平臺查詢。")
            .setView(box)
            .setPositiveButton("儲存") { _, _ ->
                prefs.edit().putString(KEY_API, input.text.toString().trim()).apply()
                load(force = true)
            }
            .setNegativeButton("取消") { _, _ -> if (apiKey().isEmpty()) showMessage("請先輸入授權碼") }
            .show()
    }

    private fun load(force: Boolean = false) {
        refreshPicker()
        val key = apiKey()
        if (key.isEmpty()) return showMessage("請先輸入授權碼")
        val current = ++generation
        showMessage("載入中…")
        val client = CwaClient(key)
        val coast = coastTown()
        prefs.edit().putString(KEY_HISTORY, withVisit(history(), coast.label).joinToString("\n")).apply()
        val zonesStale = force || seaZones.isEmpty() || System.currentTimeMillis() - seaZonesAt > SEA_TTL_MS
        worker.execute {
            val render: () -> Unit = try {
                val periods = client.township(coast.dataset, coast.name)
                // Wind is the core of this view; waves and tide degrade to a note.
                val notes = mutableListOf<String>()
                val tide = try {
                    client.tide(coast.tideId)
                } catch (e: ApiError) {
                    notes += "潮汐載入失敗：${e.message}"
                    emptyList()
                }
                val zones = try {
                    if (zonesStale) client.seaZones() else seaZones
                } catch (e: ApiError) {
                    notes += "海面預報載入失敗：${e.message}"
                    seaZones
                }
                val report = CoastReport(periods, tide, zones[coast.zone].orEmpty(), notes);
                {
                    if (zones.isNotEmpty() && zonesStale) {
                        seaZones = zones
                        seaZonesAt = System.currentTimeMillis()
                    }
                    showCoast(report, coast)
                }
            } catch (e: ApiError) {
                { showMessage(e.message.orEmpty()) }
            } catch (e: RuntimeException) {
                { showMessage("資料解析失敗，請回報：${e.javaClass.simpleName}") }
            }
            runOnUiThread { if (current == generation && !isDestroyed) render() }
        }
    }

    private fun showMessage(text: String) {
        content.removeAllViews()
        content.addView(
            label(text, 16f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            },
        )
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun heat(hue: Int, level: Float): Int {
        val t = level.coerceIn(0f, 1f) * 0.92f
        fun mix(shift: Int) =
            (((cardColor shr shift) and 255) * (1 - t) + ((hue shr shift) and 255) * t).toInt()
        return Color.rgb(mix(16), mix(8), mix(0))
    }

    /** Cells carry their value as text, so ink only has to stay legible on the fill. */
    private fun inkOn(fill: Int): Int {
        val light = 0.299 * Color.red(fill) + 0.587 * Color.green(fill) + 0.114 * Color.blue(fill)
        return if (light > 150) 0xFF17232C.toInt() else Color.WHITE
    }

    private fun cell(text: String, width: Int, height: Int, fill: Int = cardColor, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(inkOn(fill))
            setBackgroundColor(fill)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(width, height).apply {
                rightMargin = dp(1)
                bottomMargin = dp(1)
            }
        }

    private fun windArrow(direction: String) = when (direction) {
        "偏北風" -> "↓"
        "東北風" -> "↙"
        "偏東風" -> "←"
        "東南風" -> "↖"
        "偏南風" -> "↑"
        "西南風" -> "↗"
        "偏西風" -> "→"
        "西北風" -> "↘"
        "" -> ""
        else -> "·"
    }

    private fun weatherIcon(wx: String) = when {
        "雷" in wx -> "⛈"
        "雪" in wx -> "🌨"
        "雨" in wx -> "🌧"
        "陰" in wx -> "☁"
        "雲" in wx -> "⛅"
        "晴" in wx -> "☀"
        wx.isEmpty() -> ""
        else -> "·"
    }

    private fun showCoast(report: CoastReport, town: CoastTown) {
        content.removeAllViews()
        val periods = report.periods
        if (periods.isEmpty()) return showMessage("查無預報資料")
        val w = dp(46)
        val dayInset = dp(4)
        val h = dp(34)
        val windHue = if (isDark) 0xFF6FB7FF.toInt() else 0xFF1259A8.toInt()
        val tempHue = if (isDark) 0xFFFFA45C.toInt() else 0xFFC8500A.toInt()
        val rainHue = if (isDark) 0xFF8FD3C8.toInt() else 0xFF0F7A6C.toInt()
        val waveHue = if (isDark) 0xFFB9A2FF.toInt() else 0xFF5B3DB8.toInt()

        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dayStarts = mutableListOf<Float>()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val timeline = withTideSlots(periods, report.tide)
        val blankWidth = (w + dp(1)) * (timeline.size - periods.size) - dp(1)
        fun line(name: String, height: Int, wholeTimeline: Boolean = false, cells: LinearLayout.() -> Unit) {
            labels.addView(cell(name, dp(58), height, bold = true))
            grid.addView(
                LinearLayout(this).apply {
                    // One-line and two-line cells would otherwise shift to share a text baseline.
                    isBaselineAligned = false
                    cells()
                    // One view for all empty slots: a cell per slot would add thousands of views.
                    if (!wholeTimeline && blankWidth > 0) addView(cell("", blankWidth, height))
                },
            )
        }
        fun value(p: String) = p.toFloatOrNull()

        val dayCells = mutableListOf<Triple<TextView, Int, Int>>()
        val dateHeight = dp(42)
        line("日期", dateHeight, wholeTimeline = true) {
            var left = 0
            timeline.groupBy { it.start.take(10) }.forEach { (date, group) ->
                val width = (w + dp(1)) * group.size - dp(1)
                val view = cell(dayLabel(date), width, dateHeight, bold = true)
                view.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                view.setPadding(dayInset, 0, 0, 0)
                addView(view)
                dayCells += Triple(view, left, width)
                if (left > 0) dayStarts += left - dp(1) / 2f
                left += width + dp(1)
            }
        }
        line("時間", h, wholeTimeline = true) { timeline.forEach { addView(cell(it.start.substring(11, 13), w, h)) } }
        line("天氣", h) {
            periods.forEach { p ->
                addView(cell(weatherIcon(p.wx), w, h).apply { setOnClickListener { toast(p.wx) } })
            }
        }
        line("風速 m/s", h) {
            periods.forEach {
                val v = value(it.windSpeed)
                addView(cell(it.windSpeed, w, h, heat(windHue, (v ?: 0f) / 17f), bold = true))
            }
        }
        line("風級", h) {
            periods.forEach {
                val v = value(it.beaufort)
                addView(cell(it.beaufort.replace("<=", "≤").replace(" ", ""), w, h, heat(windHue, (v ?: 0f) / 8f)))
            }
        }
        line("風向", h) {
            periods.forEach { p ->
                addView(cell(windArrow(p.windDir), w, h).apply { setOnClickListener { toast(p.windDir) } })
            }
        }
        val waves = periods.map { p ->
            report.sea.firstOrNull { it.date == p.start.take(10) }
        }
        fun shortWave(sea: SeaDay) =
            sea.wave.replace("公尺", "").replace("再轉", "→").replace("轉", "→").replace("至", "-")
        val wavePaint = TextPaint(cell("", w, h).paint)
        val waveHeight = waves.filterNotNull().maxOfOrNull { sea ->
            val text = shortWave(sea)
            StaticLayout.Builder.obtain(text, 0, text.length, wavePaint, w).build().height + dp(12)
        }?.coerceAtLeast(h) ?: h
        line("浪高 m", waveHeight) {
            waves.forEach { sea ->
                if (sea == null) {
                    addView(cell("", w, waveHeight))
                } else {
                    val top = Regex("\\d+(\\.\\d+)?").findAll(sea.wave).maxOfOrNull { it.value.toFloat() } ?: 0f
                    addView(
                        cell(shortWave(sea), w, waveHeight, heat(waveHue, top / 6f)).apply {
                            setOnClickListener { toast("${sea.wave}，${sea.waveType}（當日海區預報）") }
                        },
                    )
                }
            }
        }
        val tideHeight = dp(112)
        val marks = report.tide.flatMap { it.events }.mapNotNull { e ->
            val cm = e.heightCm.toIntOrNull() ?: return@mapNotNull null
            val time = e.time.substringAfter('T').take(5)
            TideMark(OffsetDateTime.parse(e.time).toEpochSecond(), cm, e.tide == "滿潮", "$time  $cm")
        }.sortedBy { it.epochSecond }
        val horizontal = HorizontalScrollView(this)
        lateinit var tideCurve: TideCurveView
        line("潮汐 cm", tideHeight, wholeTimeline = true) {
            tideCurve = TideCurveView(
                context,
                timeline.map { OffsetDateTime.parse(it.start).toEpochSecond() to OffsetDateTime.parse(it.end).toEpochSecond() },
                marks,
                (w + dp(1)).toFloat(),
                horizontal,
                dayStarts,
                muted,
                rainHue,
                ink,
                cardColor,
            )
            addView(
                tideCurve,
                LinearLayout.LayoutParams((w + dp(1)) * timeline.size - dp(1), tideHeight).apply { bottomMargin = dp(1) },
            )
        }
        line("氣溫 ℃", h) {
            periods.forEach {
                val v = value(it.temp)
                addView(cell(it.temp, w, h, heat(tempHue, ((v ?: 10f) - 10f) / 25f)))
            }
        }
        line("體感 ℃", h) {
            periods.forEach {
                val v = value(it.feels)
                addView(cell(it.feels, w, h, heat(tempHue, ((v ?: 10f) - 10f) / 25f)))
            }
        }
        line("降雨 %", h) {
            periods.forEach {
                val v = value(it.pop)
                addView(cell(it.pop, w, h, heat(rainHue, (v ?: 0f) / 100f)))
            }
        }
        line("濕度 %", h) { periods.forEach { addView(cell(it.humidity, w, h)) } }

        content.addView(
            LinearLayout(this).apply {
                addView(labels)
                addView(
                    horizontal.apply {
                        addView(grid)
                        // Keeps each date readable while its merged cell is partly off screen.
                        setOnScrollChangeListener { _, scrollX, _, _, _ ->
                            dayCells.forEach { (view, left, width) ->
                                if (scrollX + this.width < left || scrollX > left + width) return@forEach
                                val room = width - view.paint.measureText(view.text.toString()).toInt() - 2 * dayInset
                                val shift = (scrollX - left).coerceIn(0, room.coerceAtLeast(0))
                                view.setPadding(dayInset + shift, 0, 0, 0)
                            }
                            tideCurve.invalidate()
                        }
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            },
        )
        report.notes.forEach { content.addView(label(it, 13f, muted).apply { setPadding(0, dp(10), 0, 0) }) }
        content.addView(
            label(
                "顏色越深數值越大；箭頭為風吹去的方向，點天氣、風向或浪高可看文字。\n" +
                    "風與天氣：${town.label}鄉鎮預報（陸地，非海上）。\n" +
                    "浪高：${town.zone}每日預報。風、天氣、浪只有未來幾天，之後留白；潮汐可往右滑到 30 天。\n" +
                    "潮汐：${town.label}，波峰為滿潮、波谷為乾潮，標示時間與潮高；峰谷之間為平滑推算。\n資料來源：中央氣象署",
                12f,
                muted,
            ).apply { setPadding(0, dp(12), 0, 0) },
        )
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}

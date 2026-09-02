package com.hlworld.kboalarm

import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.net.URL
import java.util.Locale

class ScheduleActivity : AppCompatActivity() {
    private val kst = ZoneId.of("Asia/Seoul")
    private val prefs by lazy { getSharedPreferences("kbo_alarm_prefs", MODE_PRIVATE) }
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy년 M월", Locale.KOREAN)
    private val dateFormatter = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
    private lateinit var calendarGrid: LinearLayout
    private lateinit var gamesContainer: LinearLayout
    private lateinit var monthTitle: TextView
    private lateinit var statusText: TextView
    private var displayedMonth = YearMonth.now(kst)
    private var selectedDate = LocalDate.now(kst)
    private var monthGames: Map<LocalDate, List<KboGame>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "내 팀 일정"
        setContentView(buildContent())
        loadMonth(displayedMonth, selectedDate)
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 248, 252))
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(10))
            addView(TextView(this@ScheduleActivity).apply {
                text = "내 팀 일정"
                textSize = 23f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(15, 23, 42))
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(TextView(this@ScheduleActivity).apply {
                text = "오늘"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = pill(Color.rgb(20, 58, 122))
                setOnClickListener {
                    selectedDate = LocalDate.now(kst)
                    displayedMonth = YearMonth.from(selectedDate)
                    loadMonth(displayedMonth, selectedDate)
                }
            }, LinearLayout.LayoutParams(dp(58), dp(38)))
        })
        root.addView(TextView(this).apply {
            val team = prefs.getString("favorite_team", null)?.let(::displayTeamName) ?: "내 팀 미선택"
            text = team
            textSize = 14f
            setTextColor(Color.rgb(86, 101, 125))
            setPadding(dp(20), 0, dp(20), dp(14))
        })
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
            addView(monthButton("‹") {
                displayedMonth = displayedMonth.minusMonths(1)
                selectedDate = displayedMonth.atDay(1)
                loadMonth(displayedMonth, selectedDate)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            monthTitle = TextView(this@ScheduleActivity).apply {
                textSize = 19f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(15, 23, 42))
            }
            addView(monthTitle, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(monthButton("›") {
                displayedMonth = displayedMonth.plusMonths(1)
                selectedDate = displayedMonth.atDay(1)
                loadMonth(displayedMonth, selectedDate)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        root.addView(buildWeekHeader())
        calendarGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        root.addView(calendarGrid)
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        root.addView(statusText)
        val scroll = ScrollView(this)
        gamesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(20))
        }
        scroll.addView(gamesContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun monthButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 32f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(15, 23, 42))
        setOnClickListener { action() }
    }

    private fun buildWeekHeader(): View = LinearLayout(this).apply {
        val labels = listOf("월", "화", "수", "목", "금", "토", "일")
        setPadding(dp(12), 0, dp(12), dp(4))
        labels.forEachIndexed { index, label ->
            addView(TextView(this@ScheduleActivity).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (index == 6) Color.rgb(220, 60, 76) else Color.rgb(100, 116, 139))
            }, LinearLayout.LayoutParams(0, dp(28), 1f))
        }
    }

    private fun loadMonth(month: YearMonth, focusDate: LocalDate) {
        monthTitle.text = month.format(monthFormatter)
        statusText.text = "${month.format(monthFormatter)} 경기 일정을 불러오는 중..."
        renderCalendar(month, emptyMap(), focusDate)
        gamesContainer.removeAllViews()
        lifecycleScope.launch {
            val favoriteTeam = prefs.getString("favorite_team", null)?.trim()?.uppercase(Locale.ROOT)
            val loaded = withContext(Dispatchers.IO) {
                coroutineScope {
                    month.atDay(1).datesUntil(month.plusMonths(1).atDay(1)).toList()
                        .map { date ->
                            async {
                                date to runCatching { KboRepository.fetchTodayGames(date) }
                                    .getOrDefault(emptyList())
                            }
                        }.awaitAll().toMap().filterValues { it.isNotEmpty() }
                }
            }
            if (month != displayedMonth) return@launch
            monthGames = loaded
            renderCalendar(month, loaded, focusDate)
            showGames(focusDate)
            statusText.text = if (favoriteTeam == null) "사이드바에서 내 팀을 먼저 선택해 주세요." else "경기가 있는 날짜를 눌러 전체 상세 일정을 확인하세요."
        }
    }

    private fun renderCalendar(month: YearMonth, games: Map<LocalDate, List<KboGame>>, focusDate: LocalDate) {
        calendarGrid.removeAllViews()
        val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        repeat(6) { rowIndex ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) { column ->
                val date = first.plusDays((rowIndex * 7 + column).toLong())
                val favoriteTeam = favoriteTeamCode()
                val dayGames = games[date].orEmpty().filter { game ->
                    favoriteTeam != null && game.containsTeam(favoriteTeam)
                }
                row.addView(buildDayCell(date, dayGames, date.month == month.month, date == focusDate, column), LinearLayout.LayoutParams(0, dp(58), 1f))
            }
            calendarGrid.addView(row)
        }
    }

    private fun buildDayCell(date: LocalDate, games: List<KboGame>, inMonth: Boolean, selected: Boolean, column: Int): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(3), dp(2), dp(3))
            background = when {
                selected -> pill(Color.rgb(20, 58, 122))
                date == LocalDate.now(kst) -> pill(Color.rgb(225, 235, 251))
                else -> null
            }
            setOnClickListener {
                if (inMonth) {
                    selectedDate = date
                    renderCalendar(displayedMonth, monthGames, selectedDate)
                    showGames(date)
                    showDateDialog(date)
                }
            }
        }
        cell.addView(TextView(this).apply {
            text = date.dayOfMonth.toString()
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(when {
                !inMonth -> Color.rgb(203, 213, 225)
                selected -> Color.WHITE
                column == 6 -> Color.rgb(220, 60, 76)
                else -> Color.rgb(15, 23, 42)
            })
        }, LinearLayout.LayoutParams(-1, dp(24)))
        val logoRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        games.take(2).forEach { game ->
            val favoriteTeam = favoriteTeamCode()
            val opponent = if (favoriteTeam != null && game.homeTeam.equals(favoriteTeam, true)) game.awayTeam else game.homeTeam
            val logoFrame = FrameLayout(this)
            val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val fallback = TextView(this).apply {
                text = logoCode(opponent)
                textSize = 7f
                gravity = Gravity.CENTER
                setTextColor(if (selected) Color.WHITE else Color.rgb(20, 58, 122))
            }
            logoFrame.addView(logo, FrameLayout.LayoutParams(dp(20), dp(20)))
            logoFrame.addView(fallback, FrameLayout.LayoutParams(-1, -1))
            logoRow.addView(logoFrame, LinearLayout.LayoutParams(dp(24), dp(20)))
            loadTeamLogo(logo, fallback, logoCode(opponent))
        }
        cell.addView(logoRow, LinearLayout.LayoutParams(-1, dp(18)))
        return cell
    }

    private fun showGames(date: LocalDate) {
        gamesContainer.removeAllViews()
        gamesContainer.addView(TextView(this).apply {
            text = date.format(dateFormatter)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(15, 23, 42))
            setPadding(0, dp(6), 0, dp(8))
        })
        gamesContainer.addView(TextView(this).apply {
            text = "전체 경기 상세"
            textSize = 12f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, 0, 0, dp(5))
        })
        val games = sortedGames(date)
        if (games.isEmpty()) gamesContainer.addView(TextView(this).apply {
            text = "이 날짜에는 경기가 없습니다."
            textSize = 14f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(8), 0, dp(12))
        }) else games.forEach { gamesContainer.addView(buildGameView(it)) }
    }

    private fun summaryText(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(Color.rgb(100, 116, 139))
        setPadding(0, 0, 0, dp(4))
    }

    private fun showDateDialog(date: LocalDate) {
        val games = sortedGames(date)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(4))
        }
        if (games.isEmpty()) {
            content.addView(summaryText("이 날짜에는 경기가 없습니다."))
        } else {
            games.forEach { game -> content.addView(buildGameView(game)) }
        }
        val scroll = ScrollView(this).apply {
            addView(content)
        }
        AlertDialog.Builder(this)
            .setTitle("${date.format(dateFormatter)} 상세 일정")
            .setView(scroll)
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun favoriteTeamCode(): String? = prefs.getString("favorite_team", null)
        ?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }

    private fun KboGame.containsTeam(code: String): Boolean =
        homeTeam.trim().uppercase(Locale.ROOT) == code || awayTeam.trim().uppercase(Locale.ROOT) == code

    private fun buildGameView(game: KboGame): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.rgb(226, 232, 240))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) }
        addView(LinearLayout(this@ScheduleActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(teamNameView(game.homeTeam, Gravity.START), LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@ScheduleActivity).apply {
                text = if (game.homeScore != null && game.awayScore != null) {
                    "${game.homeScore} - ${game.awayScore}"
                } else {
                    "VS"
                }
                textSize = if (game.homeScore != null && game.awayScore != null) 16f else 11f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(20, 58, 122))
            }, LinearLayout.LayoutParams(dp(76), -2))
            addView(teamNameView(game.awayTeam, Gravity.END), LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(TextView(this@ScheduleActivity).apply {
            text = "${game.time.ifBlank { "시간 미정" }}  ·  ${if (game.canceled) "경기 취소" else game.status}${game.venue.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""}"
            textSize = 13f
            setTextColor(if (game.canceled) Color.rgb(220, 60, 76) else Color.rgb(100, 116, 139))
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun displayTeamName(code: String): String = when (code.uppercase(Locale.ROOT)) {
        "LG" -> "LG 트윈스"
        "NC" -> "NC 다이노스"
        "KT" -> "KT 위즈"
        "LOTTE" -> "롯데 자이언츠"
        "KIA" -> "KIA 타이거즈"
        "DOOSAN" -> "두산 베어스"
        "KIWOOM" -> "키움 히어로즈"
        "HANWHA" -> "한화 이글스"
        "SSG" -> "SSG 랜더스"
        "SAMSUNG" -> "삼성 라이온즈"
        else -> code
    }

    private fun teamNameView(code: String, alignment: Int) = TextView(this).apply {
        text = displayTeamName(code)
        textSize = 14f
        gravity = alignment
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(15, 23, 42))
    }

    private fun sortedGames(date: LocalDate): List<KboGame> {
        val favorite = favoriteTeamCode()
        return monthGames[date].orEmpty().sortedWith(
            compareByDescending<KboGame> { favorite != null && it.containsTeam(favorite) }
                .thenBy { it.time.ifBlank { "99:99" } }
        )
    }

    private fun logoCode(team: String): String = when (team.trim().uppercase(Locale.ROOT)) {
        "HANWHA" -> "HH"
        "SSG" -> "SK"
        "SAMSUNG" -> "SS"
        "LOTTE" -> "LT"
        "KIA" -> "HT"
        "DOOSAN" -> "OB"
        "KIWOOM" -> "WO"
        else -> team.trim().uppercase(Locale.ROOT)
    }

    private fun loadTeamLogo(imageView: ImageView, fallback: TextView, code: String) {
        val url = "https://6ptotvmi5753.edge.naverncp.com/KBO_IMAGE/KBOHome/resources/images/weather/emb/$code.png"
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                fallback.visibility = View.GONE
            }
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

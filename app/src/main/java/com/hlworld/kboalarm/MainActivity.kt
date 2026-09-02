package com.hlworld.kboalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.drawerlayout.widget.DrawerLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.DayOfWeek
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val kst = ZoneId.of("Asia/Seoul")
    private val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd (E)", Locale.KOREAN)
    private val compactDateFormatter = DateTimeFormatter.ofPattern("MM.dd", Locale.KOREAN)
    private val weekdayFormatter = DateTimeFormatter.ofPattern("E", Locale.KOREAN)
    private val prefs by lazy { getSharedPreferences("kbo_alarm_prefs", MODE_PRIVATE) }

    private var selectedDate: LocalDate = LocalDate.now(kst)

    private fun mondayStart(date: LocalDate = LocalDate.now(kst)): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun weeklyBadgeIndex(teamCode: String, optionCount: Int): Int {
        if (optionCount <= 0) return 0
        val anchorMonday = LocalDate.of(2026, 1, 5)
        val currentMonday = mondayStart()
        val weeks = ChronoUnit.WEEKS.between(anchorMonday, currentMonday).toInt()
        return Math.floorMod(teamCode.hashCode() + weeks, optionCount)
    }

private fun weeklyTeamBadges(teamCode: String?): List<String> {
    val key = teamCode ?: "DEFAULT"
    val pools = when (key) {
        "LG" -> listOf(
            listOf("잠실", "타선", "클러치"),
            listOf("LG", "역전", "불펜"),
            listOf("수비", "연승", "응원"),
            listOf("끝내기", "추격", "기세")
        )
        "HANWHA" -> listOf(
            listOf("대전", "직구", "불펜"),
            listOf("한화", "장타", "승부처"),
            listOf("연승", "수비", "기세"),
            listOf("끝내기", "마운드", "추격")
        )
        "SSG" -> listOf(
            listOf("인천", "수비", "연승"),
            listOf("SSG", "불펜", "추격"),
            listOf("클러치", "장타", "기세"),
            listOf("끝내기", "역전", "응원")
        )
        "SAMSUNG" -> listOf(
            listOf("대구", "장타", "불펜"),
            listOf("삼성", "수비", "연승"),
            listOf("클러치", "추격", "기세"),
            listOf("끝내기", "마운드", "응원")
        )
        "NC" -> listOf(
            listOf("창원", "주루", "수비"),
            listOf("NC", "불펜", "역전"),
            listOf("연승", "클러치", "기세"),
            listOf("끝내기", "추격", "응원")
        )
        "KT" -> listOf(
            listOf("수원", "제구", "마무리"),
            listOf("KT", "장타", "수비"),
            listOf("승부처", "불펜", "연승"),
            listOf("끝내기", "추격", "기세")
        )
        "LOTTE" -> listOf(
            listOf("부산", "열기", "장타"),
            listOf("롯데", "응원", "불펜"),
            listOf("연승", "수비", "기세"),
            listOf("끝내기", "승부처", "추격")
        )
        "KIA" -> listOf(
            listOf("광주", "승부처", "클러치"),
            listOf("KIA", "장타", "불펜"),
            listOf("연승", "수비", "기세"),
            listOf("끝내기", "추격", "응원")
        )
        "DOOSAN" -> listOf(
            listOf("잠실", "역전", "불펜"),
            listOf("두산", "수비", "연승"),
            listOf("클러치", "장타", "기세"),
            listOf("끝내기", "승부처", "응원")
        )
        "KIWOOM" -> listOf(
            listOf("고척", "끝내기", "역전"),
            listOf("키움", "장타", "불펜"),
            listOf("수비", "연승", "기세"),
            listOf("클러치", "추격", "응원")
        )
        else -> listOf(
            listOf("주간 하이라이트", "임팩트", "팬픽"),
            listOf("경기장 분위기", "클러치", "수비"),
            listOf("역전", "끝내기", "연승")
        )
    }
    return pools[weeklyBadgeIndex(key, pools.size)]
}

    private lateinit var statusView: TextView
    private lateinit var summaryView: TextView
    private lateinit var errorView: TextView
    private lateinit var heroCard: LinearLayout
    private lateinit var teamIdentityCard: FrameLayout
    private lateinit var teamIdentityLogo: ImageView
    private lateinit var teamIdentityFallback: TextView
    private lateinit var teamIdentityTitle: TextView
    private lateinit var teamIdentitySubtitle: TextView
    private lateinit var teamIdentityChip: TextView
    private lateinit var teamIdentitySeal: TextView
    private lateinit var teamIdentityBadgeRow: LinearLayout
    private lateinit var teamIdentityFooter: TextView
    private lateinit var teamIdentityWatermark: TextView
    private lateinit var mainContent: LinearLayout
    private lateinit var dateRailCard: LinearLayout
    private lateinit var dateScrollView: HorizontalScrollView
    private lateinit var dateStrip: LinearLayout
    private lateinit var gamesContainer: LinearLayout
    private lateinit var notificationToggle: TextView
    private lateinit var menuToggle: TextView
    private lateinit var favoriteTeamItem: TextView
    private lateinit var drawerLayout: DrawerLayout
    private var latestGames: List<KboGame> = emptyList()

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            setAlertsEnabled(true)
        } else {
            statusView.text = "알림 권한이 없어 상단 알림을 띄울 수 없어요."
            setAlertsEnabled(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            val contentScroll = ScrollView(this)
            mainContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 40)
                setBackgroundColor(Color.parseColor("#EEF2F7"))
            }

            heroCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = 16
                }
                elevation = 10f
            }

            val brandRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            val kboMark = TextView(this).apply {
                text = "KBO"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(14, 8, 14, 8)
                background = pillDrawable(0x22FFFFFF, 0x44FFFFFF)
            }

            val brandSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }

            notificationToggle = TextView(this).apply {
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(12, 8, 12, 8)
                setOnClickListener { toggleAlerts() }
                background = pillDrawable(0x18FFFFFF, 0x22FFFFFF)
            }

            menuToggle = TextView(this).apply {
                text = "☰"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(12, 8, 12, 8)
                minimumWidth = dp(40)
                minimumHeight = dp(40)
                isClickable = true
                isFocusable = true
                setOnClickListener { openDrawer() }
                background = pillDrawable(0x18FFFFFF, 0x22FFFFFF)
            }

            brandRow.addView(kboMark)
            brandRow.addView(brandSpacer)
            brandRow.addView(notificationToggle)
            brandRow.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 1)
            })
            brandRow.addView(menuToggle)

            statusView = TextView(this).apply {
                text = "오늘 경기 데이터를 불러오는 중이에요."
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFFE8EEF9.toInt())
                setPadding(0, 14, 0, 0)
            }

            val heroNote = TextView(this).apply {
                text = "오늘 경기 점수, 선발투수, 구장 날씨를 한 화면에 정리했습니다."
                textSize = 12f
                setTextColor(0xFFDBE4FF.toInt())
                setPadding(0, 6, 0, 0)
            }

            summaryView = TextView(this).apply {
                text = "불러오는 중..."
                textSize = 13f
                setTextColor(0xFFB8C6E0.toInt())
                setPadding(0, 6, 0, 0)
            }

            errorView = TextView(this).apply {
                text = ""
                textSize = 12f
                setPadding(0, 10, 0, 0)
                setTextColor(0xFFFFB4B4.toInt())
                visibility = View.GONE
            }

            statusView.text = "오늘 경기 데이터를 불러오는 중이에요."
            summaryView.text = "불러오는 중..."
            errorView.visibility = View.GONE

            heroCard.addView(brandRow)
            heroCard.addView(statusView)
            heroCard.addView(heroNote)
            heroCard.addView(summaryView)
            heroCard.addView(errorView)

            mainContent.addView(heroCard)

            teamIdentityCard = buildTeamIdentityCard()
            mainContent.addView(teamIdentityCard)

            dateRailCard = buildDateRailCard()
            mainContent.addView(dateRailCard)

            gamesContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 18, 0, 0)
            }
            mainContent.addView(gamesContainer)

            contentScroll.addView(mainContent)

            drawerLayout = DrawerLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(
                    contentScroll,
                    DrawerLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                )
                addView(buildRightDrawer())
            }

            setContentView(drawerLayout)
            applyThemeToMainScreen()
            setAlertsEnabled(prefs.getBoolean(KEY_ALERTS_ENABLED, false), persist = false)
        } catch (e: Exception) {
            val fallback = TextView(this).apply {
                text = "화면을 여는 중 문제가 생겼어요.\n${e.message ?: e::class.java.simpleName}"
                setTextColor(Color.WHITE)
                setPadding(40, 60, 40, 60)
                textSize = 16f
                setBackgroundColor(0xFF0F172A.toInt())
            }
            setContentView(fallback)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::notificationToggle.isInitialized) {
            setAlertsEnabled(prefs.getBoolean(KEY_ALERTS_ENABLED, false), persist = false)
        }
        loadGames(selectedDate)
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        setAlertsEnabled(true)
    }

    private fun startScoreService() {
        val intent = Intent(this, KboScoreService::class.java)
        ContextCompat.startForegroundService(this, intent)
        statusView.text = "알림이 시작됐어요."
    }

    private fun stopScoreService() {
        stopService(Intent(this, KboScoreService::class.java))
        statusView.text = "알림을 멈췄어요."
    }

    private fun toggleAlerts() {
        if (prefs.getBoolean(KEY_ALERTS_ENABLED, false)) {
            setAlertsEnabled(false)
        } else {
            ensureNotificationPermissionThenStart()
        }
    }

    private fun setAlertsEnabled(enabled: Boolean, persist: Boolean = true) {
        if (persist) {
            prefs.edit().putBoolean(KEY_ALERTS_ENABLED, enabled).apply()
        }

        if (::notificationToggle.isInitialized) {
            notificationToggle.text = if (enabled) "🔔" else "🔕"
            notificationToggle.setTextColor(if (enabled) Color.WHITE else 0xFFBFD2FF.toInt())
            notificationToggle.background = pillDrawable(
                if (enabled) 0x30FFFFFF else 0x18FFFFFF,
                if (enabled) 0x66FFFFFF else 0x22FFFFFF,
            )
            notificationToggle.contentDescription = if (enabled) "알림 끄기" else "알림 켜기"
        }

        if (enabled) {
            startScoreService()
        } else {
            stopScoreService()
        }
    }

    private fun loadGames(date: LocalDate = selectedDate) {
        selectedDate = date
        refreshDateStrip(date)
        lifecycleScope.launch {
            try {
                statusView.text = "${describeDate(date)} 경기 불러오는 중..."
                val result = withContext(Dispatchers.IO) {
                    runCatching { KboRepository.fetchTodayGames(date) }
                }

                result.onSuccess { games ->
                    games.forEach { game ->
                        game.gameId?.let { id ->
                            game.weatherLabel()?.let { weather ->
                                prefs.edit().putString("weather_$id", weather).apply()
                            }
                        }
                    }
                    val hydratedGames = games.map { game ->
                        game.copy(weather = game.weather ?: game.gameId?.let { prefs.getString("weather_$it", null) })
                    }
                    latestGames = hydratedGames
                    renderGames(hydratedGames)
                    errorView.text = ""
                    errorView.visibility = View.GONE
                    statusView.text = if (games.isEmpty()) {
                        "${describeDate(date)} 경기 정보를 못 찾았어요."
                    } else {
                        "${describeDate(date)} 경기 ${hydratedGames.size}경기 확인됨"
                    }
                    summaryView.text = buildSummary(hydratedGames)
                }.onFailure { error ->
                    val message = error.message ?: error::class.java.simpleName
                    renderGames(emptyList())
                    statusView.text = "${describeDate(date)} 경기 정보를 불러오지 못했어요."
                    summaryView.text = "가져오기 실패"
                    errorView.text = "오류: $message"
                    errorView.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                errorView.text = "오류: ${e.message ?: e::class.java.simpleName}"
                errorView.visibility = View.VISIBLE
                statusView.text = "${describeDate(date)} 경기 정보를 불러오지 못했어요."
            }
        }
    }

    private fun buildDateRailCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 14)
            background = dateRailBackground()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = 14
            }
        }

        dateScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, 0)
        }

        dateStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        dateScrollView.addView(
            dateStrip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        card.addView(
            dateScrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        refreshDateStrip(selectedDate)
        return card
    }

    private fun refreshDateStrip(centerDate: LocalDate) {
        if (!::dateStrip.isInitialized || !::dateScrollView.isInitialized) return

        dateStrip.removeAllViews()
        val start = centerDate.minusDays(10)
        val end = centerDate.plusDays(10)
        val chips = linkedMapOf<LocalDate, TextView>()

        var day = start
        while (!day.isAfter(end)) {
            val chip = buildDateChip(day, day == centerDate)
            chips[day] = chip
            dateStrip.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    rightMargin = 10
                },
            )
            day = day.plusDays(1)
        }

        dateStrip.post {
            val selectedChip = chips[centerDate] ?: return@post
            val target = (selectedChip.left + selectedChip.width / 2) - (dateScrollView.width / 2)
            dateScrollView.smoothScrollTo(target.coerceAtLeast(0), 0)
        }
    }

    private fun buildDateChip(date: LocalDate, selected: Boolean): TextView {
        val brand = activeBrandStyle()
        return TextView(this).apply {
            text = buildString {
                if (date == LocalDate.now(kst)) {
                    append("오늘")
                    append('\n')
                }
                append(date.format(compactDateFormatter))
                append('\n')
                append(date.format(weekdayFormatter))
            }
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(16, 12, 16, 12)
            setTextColor(if (selected) Color.WHITE else 0xFF344054.toInt())
            background = pillDrawable(
                if (selected) brand.color else 0xFFF2F4F7.toInt(),
                if (selected) brand.color else 0xFFE4E7EC.toInt(),
            )
            setOnClickListener {
                if (selectedDate != date) {
                    loadGames(date)
                } else {
                    refreshDateStrip(date)
                }
            }
        }
    }

    private fun describeDate(date: LocalDate): String {
        val today = LocalDate.now(kst)
        return when {
            date == today -> "오늘"
            date == today.minusDays(1) -> "어제"
            date == today.plusDays(1) -> "내일"
            else -> date.format(formatter)
        }
    }

    private fun renderGames(games: List<KboGame>) {
        gamesContainer.removeAllViews()

        val orderedGames = prioritizeFavoriteTeam(games)

        if (orderedGames.isEmpty()) {
            val empty = TextView(this).apply {
                text = "오늘 경기가 없거나, 아직 데이터를 못 가져왔어요."
                textSize = 15f
                setPadding(4, 24, 4, 24)
                setTextColor(0xFF667085.toInt())
                gravity = Gravity.CENTER_HORIZONTAL
            }
            gamesContainer.addView(empty)
            return
        }

        orderedGames.forEach { game ->
            gamesContainer.addView(buildGameCard(game))
        }
    }

    private fun buildGameCard(game: KboGame): LinearLayout {
        val awayStyle = teamStyle(game.awayTeam)
        val homeStyle = teamStyle(game.homeTeam)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 16)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.bottomMargin = 14
            layoutParams = params
            background = gameCardBackground(homeStyle, awayStyle)
            elevation = 6f
            isClickable = true
            isFocusable = true
            setOnClickListener { openGameDetail(game) }
        }

        val topStrip = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                4,
            )
            background = stripDrawable(homeStyle.color)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(2, 0, 2, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val title = TextView(this).apply {
            text = game.titleLine()
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF101828.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val weather = game.weatherLabel()?.let { value ->
            TextView(this).apply {
                text = value
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.END
                setTextColor(homeStyle.color)
                setPadding(12, 6, 12, 6)
                background = pillDrawable(homeStyle.tint, homeStyle.color)
            }
        }

        headerRow.addView(title)
        weather?.let(headerRow::addView)

        val battleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val awayMascot = buildMascotPanel(
            teamName = displayTeamLabel(game.awayTeam),
            logoCode = awayStyle.logoCode,
            color = awayStyle.color,
            isAway = true,
        )

        val homeMascot = buildMascotPanel(
            teamName = displayTeamLabel(game.homeTeam),
            logoCode = homeStyle.logoCode,
            color = homeStyle.color,
            isAway = false,
        )

        val scorePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f)
            setPadding(0, 0, 0, 0)
        }

        val score = TextView(this).apply {
            text = game.scoreLine()
            textSize = 34f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (game.canceled) 0xFFB42318.toInt() else 0xFF101828.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 0)
        }

        val status = TextView(this).apply {
            text = when {
                game.canceled -> "경기 취소"
                game.status == "경기전" -> game.time
                game.status.matches(Regex("^[0-9\\s\\-–—·.,]+$")) -> game.status
                else -> game.status
            }
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF667085.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 0)
        }

        scorePanel.addView(score)
        scorePanel.addView(status)

        battleRow.addView(homeMascot)
        battleRow.addView(scorePanel)
        battleRow.addView(awayMascot)

        val meta = TextView(this).apply {
            text = listOfNotNull(
                if (game.venue.isNotBlank()) "구장 ${game.venue}" else null,
                game.time.takeIf { it.isNotBlank() }?.let { "시작 $it" },
            ).joinToString(" · ")
            textSize = 12f
            setTextColor(0xFF667085.toInt())
            setPadding(2, 6, 2, 0)
        }

        val pitcher = game.pitcherLine()?.let { line ->
            TextView(this).apply {
                text = line
                textSize = 13f
                setTextColor(0xFF344054.toInt())
                setPadding(2, 4, 2, 0)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }

        card.addView(topStrip)
        card.addView(headerRow)
        card.addView(battleRow)
        card.addView(meta)
        pitcher?.let(card::addView)
        return card
    }

    private fun buildTeamIdentityCard(): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(14)
            }
            setPadding(dp(18), dp(18), dp(18), dp(18))
            elevation = dp(8).toFloat()

            val topGlow = View(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(dp(178), dp(178), Gravity.END or Gravity.TOP).apply {
                    rightMargin = dp(-40)
                    topMargin = dp(-64)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(withAlpha(Color.WHITE, 0x12))
                }
            }

            val bottomGlow = View(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(dp(220), dp(220), Gravity.START or Gravity.BOTTOM).apply {
                    leftMargin = dp(-90)
                    bottomMargin = dp(-96)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(withAlpha(Color.WHITE, 0x0A))
                }
            }

            val ribbon = View(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(dp(220), dp(34), Gravity.END or Gravity.TOP).apply {
                    rightMargin = dp(-32)
                    topMargin = dp(34)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(withAlpha(Color.WHITE, 0x10))
                }
                rotation = -18f
            }

            val accentStrip = View(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(dp(6), dp(82), Gravity.START or Gravity.TOP).apply {
                    leftMargin = dp(14)
                    topMargin = dp(16)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(withAlpha(Color.WHITE, 0x30))
                }
            }

            teamIdentityWatermark = TextView(this@MainActivity).apply {
                textSize = 48f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(withAlpha(Color.WHITE, 0x22))
                alpha = 0.46f
                gravity = Gravity.END
                includeFontPadding = false
                rotation = -10f
            }
            addView(
                teamIdentityWatermark,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.BOTTOM,
                ).apply {
                    rightMargin = dp(10)
                    bottomMargin = dp(6)
                }
            )

            val content = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            teamIdentityChip = TextView(this@MainActivity).apply {
                textSize = 10.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }

            teamIdentitySeal = TextView(this@MainActivity).apply {
                textSize = 10.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }

            val heroRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
            }

            val logoFrame = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(116), dp(116)).apply {
                    rightMargin = dp(14)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(34).toFloat()
                    setColor(withAlpha(Color.WHITE, 0x14))
                    setStroke(dp(1), withAlpha(Color.WHITE, 0x26))
                }
            }

            teamIdentityLogo = ImageView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    setMargins(dp(14), dp(14), dp(14), dp(14))
                    gravity = Gravity.CENTER
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            teamIdentityFallback = TextView(this@MainActivity).apply {
                text = "KBO"
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }

            logoFrame.addView(teamIdentityLogo)
            logoFrame.addView(teamIdentityFallback)

            val textColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            }

            teamIdentityTitle = TextView(this@MainActivity).apply {
                textSize = 25f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                includeFontPadding = false
            }

            teamIdentitySubtitle = TextView(this@MainActivity).apply {
                textSize = 13.5f
                setTextColor(withAlpha(Color.WHITE, 0xE6))
                setLineSpacing(dp(2).toFloat(), 1.0f)
                setPadding(0, dp(6), 0, 0)
            }

            textColumn.addView(teamIdentityTitle)
            textColumn.addView(teamIdentitySubtitle)

            teamIdentityBadgeRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dp(12), 0, 0)
            }

            teamIdentityFooter = TextView(this@MainActivity).apply {
                textSize = 11.5f
                setTextColor(withAlpha(Color.WHITE, 0xD4))
                setLineSpacing(dp(2).toFloat(), 1.0f)
                setPadding(0, dp(12), 0, 0)
            }

            heroRow.addView(logoFrame)
            heroRow.addView(textColumn)

            content.addView(teamIdentityChip)
            content.addView(teamIdentitySeal)
            content.addView(heroRow)
            content.addView(teamIdentityBadgeRow)
            content.addView(teamIdentityFooter)

            addView(topGlow)
            addView(bottomGlow)
            addView(ribbon)
            addView(accentStrip)
            addView(content)

            updateTeamIdentityCard()
        }
    }

    private fun openGameDetail(game: KboGame) {
        val intent = Intent(this, GameDetailActivity::class.java).apply {
            putExtra(GameDetailActivity.EXTRA_GAME_ID, game.gameId)
            putExtra(GameDetailActivity.EXTRA_GAME_DATE, selectedDate.toString())
            putExtra(GameDetailActivity.EXTRA_AWAY_TEAM, game.awayTeam)
            putExtra(GameDetailActivity.EXTRA_HOME_TEAM, game.homeTeam)
            putExtra(GameDetailActivity.EXTRA_AWAY_SCORE, game.awayScore ?: -1)
            putExtra(GameDetailActivity.EXTRA_HOME_SCORE, game.homeScore ?: -1)
            putExtra(GameDetailActivity.EXTRA_TIME, game.time)
            putExtra(GameDetailActivity.EXTRA_VENUE, game.venue)
            putExtra(GameDetailActivity.EXTRA_CANCELED, game.canceled)
            putExtra(GameDetailActivity.EXTRA_STATUS, game.status)
            putExtra(GameDetailActivity.EXTRA_AWAY_PITCHER, game.awayPitcherName)
            putExtra(GameDetailActivity.EXTRA_HOME_PITCHER, game.homePitcherName)
            putExtra(GameDetailActivity.EXTRA_WEATHER, game.weather)
        }
        startActivity(intent)
    }

    private fun buildMascotPanel(
        teamName: String,
        logoCode: String,
        color: Int,
        isAway: Boolean,
    ): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val logoFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(82, 82).apply {
                bottomMargin = 0
            }
        }

        val logo = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                gravity = Gravity.CENTER
                setMargins(0, 0, 0, 0)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
        }

        val fallback = TextView(this).apply {
            text = teamName.take(2)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
        }

        logoFrame.addView(logo)
        logoFrame.addView(fallback)
        loadTeamLogo(logo, fallback, logoCode)

        val label = TextView(this).apply {
            text = if (isAway) "원정" else "홈"
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color)
            setPadding(0, 4, 0, 0)
        }

        val name = TextView(this).apply {
            text = teamName
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF101828.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 1, 0, 0)
        }

        panel.addView(logoFrame)
        panel.addView(label)
        panel.addView(name)
        return panel
    }

    private fun teamStyle(team: String): TeamVisual {
        return when (team.uppercase()) {
            "LG" -> TeamVisual(0xFFC3002F.toInt(), 0xFFFDE8EC.toInt(), "LG")
            "HANWHA" -> TeamVisual(0xFFFF7A00.toInt(), 0xFFFFF1E5.toInt(), "HH")
            "SSG" -> TeamVisual(0xFFCE1126.toInt(), 0xFFFDE8EA.toInt(), "SK")
            "SAMSUNG" -> TeamVisual(0xFF005BAC.toInt(), 0xFFE8F1FF.toInt(), "SS")
            "NC" -> TeamVisual(0xFF0B5CAB.toInt(), 0xFFE8F2FF.toInt(), "NC")
            "KT" -> TeamVisual(0xFF111111.toInt(), 0xFFF0F1F3.toInt(), "KT")
            "LOTTE" -> TeamVisual(0xFF0E2F6E.toInt(), 0xFFE8EEFF.toInt(), "LT")
            "KIA" -> TeamVisual(0xFFEA0029.toInt(), 0xFFFFE8EC.toInt(), "HT")
            "DOOSAN" -> TeamVisual(0xFF1A1A1A.toInt(), 0xFFF0F1F3.toInt(), "OB")
            "KIWOOM" -> TeamVisual(0xFF7A1E2C.toInt(), 0xFFFCE8EC.toInt(), "WO")
            else -> TeamVisual(0xFF64748B.toInt(), 0xFFF1F5F9.toInt(), team.uppercase())
        }
    }

    private fun displayTeamLabel(team: String): String {
        return when (team.uppercase()) {
            "LG" -> "LG 트윈스"
            "HANWHA" -> "한화 이글스"
            "SSG" -> "SSG 랜더스"
            "SAMSUNG" -> "삼성 라이온즈"
            "NC" -> "NC 다이노스"
            "KT" -> "KT 위즈"
            "LOTTE" -> "롯데 자이언츠"
            "KIA" -> "KIA 타이거즈"
            "DOOSAN" -> "두산 베어스"
            "KIWOOM" -> "키움 히어로즈"
            else -> team
        }
    }

    private fun heroBackground(): GradientDrawable {
        val brand = activeBrandStyle()
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                blendColor(brand.color, Color.BLACK, 0.22f),
                blendColor(brand.color, Color.BLACK, 0.10f),
                blendColor(brand.tint, Color.BLACK, 0.18f),
            )
        ).apply {
            cornerRadius = 30f
        }
    }

    private fun gameCardBackground(homeStyle: TeamVisual, awayStyle: TeamVisual): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                withAlpha(homeStyle.color, 72),
                withAlpha(homeStyle.tint, 20),
                Color.WHITE,
                withAlpha(awayStyle.tint, 20),
                withAlpha(awayStyle.color, 72),
            )
        ).apply {
            cornerRadius = 28f
            setStroke(1, 0xFFE7ECF3.toInt())
        }
    }

    private fun stripDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 999f
            setColor(color)
        }
    }

    private fun pillDrawable(backgroundColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 999f
            setColor(backgroundColor)
            setStroke(1, strokeColor)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun loadTeamLogo(imageView: ImageView, fallback: TextView, code: String) {
        val url = "https://6ptotvmi5753.edge.naverncp.com/KBO_IMAGE/KBOHome/resources/images/weather/emb/$code.png"
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    URL(url).openStream().use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }.getOrNull()
            }

            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                fallback.visibility = View.GONE
            } else {
                imageView.setImageDrawable(null)
                fallback.visibility = View.VISIBLE
            }
        }
    }

    private fun toggleDrawer() {
        if (!::drawerLayout.isInitialized) return
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
            drawerLayout.closeDrawer(GravityCompat.END)
        } else {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    private fun openDrawer() {
        if (!::drawerLayout.isInitialized) return
        if (!drawerLayout.isDrawerOpen(GravityCompat.END)) {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    private fun buildRightDrawer(): LinearLayout {
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(24), dp(20), dp(20))
            layoutParams = DrawerLayout.LayoutParams(
                dp(300),
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                gravity = GravityCompat.END
            }
        }

        drawer.addView(TextView(this).apply {
            text = "야구야호"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0F172A.toInt())
        })

        drawer.addView(TextView(this).apply {
            text = "야구 커뮤니티"
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, dp(4), 0, dp(18))
        })

        drawer.addView(buildDrawerItem("순위").apply {
            setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.END)
                openRecordsSection(RecordsActivity.SECTION_RANK)
            }
        })
        drawer.addView(buildDrawerItem("팀기록실").apply {
            setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.END)
                openRecordsSection(RecordsActivity.SECTION_TEAM)
            }
        })
        drawer.addView(buildDrawerItem("선수기록실").apply {
            setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.END)
                openRecordsSection(RecordsActivity.SECTION_PLAYER)
            }
        })
        drawer.addView(buildDrawerItem("일정").apply {
    setOnClickListener {
        drawerLayout.closeDrawer(GravityCompat.END)
        startActivity(Intent(this@MainActivity, ScheduleActivity::class.java))
    }
})

        drawer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        })

        favoriteTeamItem = buildDrawerItem(favoriteTeamLabel(), highlighted = true).apply {
            setOnClickListener { showFavoriteTeamPicker() }
        }
        drawer.addView(favoriteTeamItem)
        return drawer
    }

    private fun openRecordsSection(section: String) {
        startActivity(Intent(this, RecordsActivity::class.java).apply {
            putExtra(RecordsActivity.EXTRA_RECORD_SECTION, section)
        })
    }

    private fun buildDrawerItem(title: String, highlighted: Boolean = false): TextView {
        return TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (highlighted) Color.WHITE else 0xFF0F172A.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = pillDrawable(
                if (highlighted) 0xFF1D4ED8.toInt() else 0xFFF1F5F9.toInt(),
                if (highlighted) 0xFF1D4ED8.toInt() else 0xFFE2E8F0.toInt(),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun showFavoriteTeamPicker() {
        val currentCode = favoriteTeamCode()
        var dialogRef: AlertDialog? = null

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
        }

        container.addView(TextView(this).apply {
            text = "내팀 선택"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0F172A.toInt())
        })

        container.addView(TextView(this).apply {
            text = "로고 버튼을 눌러 내 팀을 골라주세요."
            textSize = 13f
            setTextColor(0xFF667085.toInt())
            setPadding(0, dp(6), 0, 0)
        })

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }

        TEAM_OPTIONS.chunked(2).forEach { rowOptions ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            rowOptions.forEachIndexed { index, option ->
                val selected = option.code == currentCode
                val button = buildTeamLogoButton(option, selected) {
                    saveFavoriteTeam(it.code)
                    dialogRef?.dismiss()
                }

                row.addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index == 0) {
                            rightMargin = dp(10)
                        }
                    },
                )
            }

            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(10)
                },
            )
        }

        container.addView(grid)
        container.addView(TextView(this).apply {
            text = "팀 선택 안 함"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF667085.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(0xFFF2F4F7.toInt())
                setStroke(dp(1), 0xFFE5E7EB.toInt())
            }
            setOnClickListener {
                clearFavoriteTeam()
                dialogRef?.dismiss()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(2)
        })
        scroll.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        dialogRef = AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun buildTeamLogoButton(
        option: TeamOption,
        selected: Boolean,
        onClick: (TeamOption) -> Unit,
    ): LinearLayout {
        val style = teamStyle(option.code)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            minimumHeight = dp(154)
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                val surface = if (selected) {
                    blendColor(style.tint, Color.WHITE, 0.04f)
                } else {
                    Color.WHITE
                }
                setColor(surface)
                setStroke(dp(2), if (selected) style.color else 0xFFE5E7EB.toInt())
            }
            elevation = if (selected) 10f else 3f
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(option) }

            val logoFrame = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(78), dp(78))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (selected) blendColor(style.tint, Color.WHITE, 0.82f) else 0xFFF8FAFC.toInt())
                    setStroke(dp(1), if (selected) withAlpha(style.color, 90) else 0xFFE5E7EB.toInt())
                }
                foregroundGravity = Gravity.CENTER
            }

            val logo = ImageView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    dp(54),
                    dp(54),
                    Gravity.CENTER,
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }

            val fallback = TextView(this@MainActivity).apply {
                text = option.label.take(2)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(style.color)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(withAlpha(style.color, 20))
                }
            }

            logoFrame.addView(logo)
            logoFrame.addView(fallback)
            loadTeamLogo(logo, fallback, style.logoCode)

            val label = TextView(this@MainActivity).apply {
                text = option.label
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (selected) style.color else 0xFF101828.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            }

            val selectedLabel = TextView(this@MainActivity).apply {
                text = if (selected) "선택됨" else "탭해서 선택"
                textSize = 10.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (selected) Color.WHITE else 0xFF667085.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(if (selected) style.color else 0xFFF2F4F7.toInt())
                    setStroke(dp(1), if (selected) style.color else 0xFFE5E7EB.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(10)
                }
            }

            addView(logoFrame)
            addView(label)
            addView(selectedLabel)
        }
    }

    private fun saveFavoriteTeam(teamCode: String) {
        prefs.edit().putString(KEY_FAVORITE_TEAM, teamCode).apply()
        applyThemeToMainScreen()
        updateFavoriteTeamUi()
        renderGames(latestGames)
    }

    private fun clearFavoriteTeam() {
        prefs.edit().remove(KEY_FAVORITE_TEAM).apply()
        applyThemeToMainScreen()
        updateFavoriteTeamUi()
        renderGames(latestGames)
    }

    private fun updateFavoriteTeamUi() {
        if (::favoriteTeamItem.isInitialized) {
            favoriteTeamItem.text = favoriteTeamLabel()
            val style = activeBrandStyle()
            favoriteTeamItem.setTextColor(Color.WHITE)
            favoriteTeamItem.background = pillDrawable(
                blendColor(style.color, Color.WHITE, 0.10f),
                blendColor(style.color, Color.WHITE, 0.10f),
            )
        }
    }

    private fun favoriteTeamCode(): String? {
        return prefs.getString(KEY_FAVORITE_TEAM, null)
            ?.trim()
            ?.uppercase(Locale.KOREA)
            ?.takeIf { it.isNotBlank() }
    }

    private fun favoriteTeamLabel(): String {
        val teamCode = favoriteTeamCode()
        return if (teamCode == null) {
            "내팀 선택"
        } else {
            "내팀: ${displayTeamLabel(teamCode)}"
        }
    }

    private fun activeBrandStyle(): TeamVisual {
        val teamCode = favoriteTeamCode() ?: return TeamVisual(
            0xFF123A7A.toInt(),
            0xFFE8F0FF.toInt(),
            "KBO",
        )
        return teamStyle(teamCode)
    }

    private fun applyThemeToMainScreen() {
        if (::mainContent.isInitialized) {
            val brand = activeBrandStyle()
            mainContent.background = mainSurfaceBackground(brand)
            heroCard.background = heroBackground()
            if (::teamIdentityCard.isInitialized) {
                updateTeamIdentityCard()
            }
            if (::dateRailCard.isInitialized) {
                dateRailCard.background = dateRailBackground()
            }
            statusView.setTextColor(0xFFF1F5FF.toInt())
            summaryView.setTextColor(0xFFE2E8F7.toInt())
            errorView.setTextColor(0xFFFFD7D7.toInt())
            notificationToggle.setTextColor(Color.WHITE)
            notificationToggle.background = pillDrawable(
                withAlpha(Color.WHITE, 0x30),
                withAlpha(Color.WHITE, 0x44),
            )
            menuToggle.setTextColor(Color.WHITE)
            menuToggle.background = pillDrawable(
                withAlpha(Color.WHITE, 0x18),
                withAlpha(Color.WHITE, 0x22),
            )
            refreshDateStrip(selectedDate)
        }
    }

    private fun updateTeamIdentityCard() {
        if (!::teamIdentityCard.isInitialized) return

        val teamCode = favoriteTeamCode()
        val brand = activeBrandStyle()
        val copy = teamIdentityCopy(teamCode)
        val borderColor = blendColor(brand.color, Color.WHITE, 0.76f)

        teamIdentityCard.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                blendColor(brand.color, Color.BLACK, 0.16f),
                blendColor(brand.color, Color.BLACK, 0.06f),
                blendColor(brand.tint, Color.WHITE, 0.24f),
            )
        ).apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), borderColor)
        }

        teamIdentityChip.text = copy.chip
        teamIdentitySeal.text = copy.seal
        teamIdentityTitle.text = copy.title
        teamIdentitySubtitle.text = copy.subtitle
        teamIdentityWatermark.text = copy.watermark
        teamIdentityFooter.text = copy.footer

        teamIdentityBadgeRow.removeAllViews()
        copy.badges.forEachIndexed { index, badge ->
            if (index > 0) {
                teamIdentityBadgeRow.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(8), 1)
                })
            }
            teamIdentityBadgeRow.addView(TextView(this).apply {
                text = badge
                textSize = 10.2f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(blendColor(brand.color, Color.WHITE, 0.18f))
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = pillDrawable(
                    blendColor(Color.WHITE, brand.tint, 0.86f),
                    blendColor(brand.color, Color.WHITE, 0.62f),
                )
            })
        }

        teamIdentityChip.setTextColor(Color.WHITE)
        teamIdentitySeal.setTextColor(Color.WHITE)
        teamIdentityChip.background = pillDrawable(
            blendColor(brand.color, Color.WHITE, 0.12f),
            blendColor(brand.color, Color.WHITE, 0.20f),
        )
        teamIdentitySeal.background = pillDrawable(
            withAlpha(Color.WHITE, 0x10),
            withAlpha(Color.WHITE, 0x28),
        )
        teamIdentityTitle.setTextColor(Color.WHITE)
        teamIdentitySubtitle.setTextColor(withAlpha(Color.WHITE, 0xE9))
        teamIdentityWatermark.setTextColor(withAlpha(Color.WHITE, 0x2A))
        teamIdentityFooter.setTextColor(withAlpha(Color.WHITE, 0xD7))

        if (teamCode == null) {
            teamIdentityLogo.setImageDrawable(null)
            teamIdentityLogo.visibility = View.INVISIBLE
            teamIdentityFallback.text = copy.watermark
            teamIdentityFallback.setTextColor(Color.WHITE)
            teamIdentityFallback.visibility = View.VISIBLE
        } else {
            teamIdentityLogo.visibility = View.VISIBLE
            loadTeamLogo(teamIdentityLogo, teamIdentityFallback, brand.logoCode)
        }
    }

    private fun teamIdentityCopy(teamCode: String?): TeamIdentityCopy {
        return when (teamCode?.uppercase(Locale.KOREA)) {
    "LG" -> TeamIdentityCopy(
        title = "LG 트윈스",
        subtitle = "승리를 향해, 하나의 트윈스!",
        chip = "내 팀 · LG",
        seal = "서울 · 잠실",
        watermark = "LG",
        badges = weeklyTeamBadges(teamCode),
        footer = "한국시리즈 4회 우승, 정규시즌 5회 우승",
    )
    "HANWHA" -> TeamIdentityCopy(
        title = "한화 이글스",
        subtitle = "이글스여 비상하라!",
        chip = "내 팀 · 한화",
        seal = "대전 · 직진",
        watermark = "HANWHA",
        badges = weeklyTeamBadges(teamCode),
        footer = "한국시리즈 1회 우승, 정규시즌 2회 우승",
    )
    "SSG" -> TeamIdentityCopy(
        title = "SSG 랜더스",
        subtitle = "NO LIMITS AMAING LANDERS",
        chip = "내 팀 · SSG",
        seal = "인천 · 압박",
        watermark = "SSG",
        badges = weeklyTeamBadges(teamCode),
        footer = "한국시리즈 5회 우승, 정규시즌 4회 우승",
    )
    "SAMSUNG" -> TeamIdentityCopy(
        title = "삼성 라이온즈",
        subtitle = "We Are The Lions!",
        chip = "내 팀 · 삼성",
        seal = "대구 · 디테일",
        watermark = "SAMSUNG",
        badges = weeklyTeamBadges(teamCode),
        footer = "한국시리즈 8회 우승, 정규시즌 12회 우승",
    )
    "NC" -> TeamIdentityCopy(
        title = "NC 다이노스",
        subtitle = "Never ending Challenge",
        chip = "내 팀 · NC",
        seal = "창원 · 속도",
        watermark = "NC",
        badges = weeklyTeamBadges(teamCode),
        footer = "한국시리즈 1회 우승, 정규시즌 1회 우승",
    )
    "KT" -> TeamIdentityCopy(
        title = "KT 위즈",
        subtitle = "The Biginning : 마법의 시작, 위대한 도약",
        chip = "내 팀 · KT",
        seal = "수원 · 정교",
        watermark = "KT",
        badges = weeklyTeamBadges(teamCode),
        footer = "한국시리즈 1회 우승, 정규시즌 1회 우승",
    )
    "LOTTE" -> TeamIdentityCopy(
        title = "롯데 자이언츠",
        subtitle = "Win the Moment",
        chip = "내 팀 · 롯데",
        seal = "부산 · 열기",
        watermark = "LOTTE",
        badges = weeklyTeamBadges(teamCode),
        footer = "한국시리즈 2회 우승",
    )
    "KIA" -> TeamIdentityCopy(
        title = "KIA 타이거즈",
        subtitle = "다시, 뜨겁게_Always KIA TIGERS",
        chip = "내 팀 · KIA",
        seal = "광주 · 승부",
        watermark = "KIA",
        badges = weeklyTeamBadges(teamCode),
        footer = "한국시리즈 12회 우승, 정규시즌 8회 우승",
    )
    "DOOSAN" -> TeamIdentityCopy(
        title = "두산 베어스",
        subtitle = "Time to MOVE ON",
        chip = "내 팀 · 두산",
        seal = "잠실 · 끈기",
        watermark = "DOOSAN",
        badges = weeklyTeamBadges(teamCode),
        footer = "한국시리즈 6회 우승, 정규시즌 7회 우승",
    )
    "KIWOOM" -> TeamIdentityCopy(
        title = "키움 히어로즈",
        subtitle = "영웅, 도전, 승리.",
        chip = "내 팀 · 키움",
        seal = "고척 · 반전",
        watermark = "KIWOOM",
        badges = weeklyTeamBadges(teamCode),
        footer = "한국시리즈 진출과 도전의 서사로 존재감을 만들어온 팀",
    )
    else -> TeamIdentityCopy(
        title = "오늘의 KBO",
        subtitle = "내 팀을 고르면 메인 화면의 분위기와 카드 순서가 함께 바뀝니다.",
        chip = "야구야호 · KBO",
        seal = "KBO · 야구야호",
        watermark = "야구야호",
        badges = weeklyTeamBadges(teamCode),
        footer = "구단을 고르면 카드 순서와 분위기가 함께 바뀝니다.",
    )
        }
    }

    private fun mainSurfaceBackground(style: TeamVisual): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                blendColor(style.tint, Color.WHITE, 0.92f),
                blendColor(style.tint, Color.WHITE, 0.98f),
                Color.WHITE,
            )
        ).apply {
            cornerRadius = 0f
        }
    }

    private fun dateRailBackground(): GradientDrawable {
        val brand = activeBrandStyle()
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                blendColor(brand.tint, Color.WHITE, 0.88f),
                Color.WHITE,
                blendColor(brand.tint, Color.WHITE, 0.90f),
            )
        ).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), blendColor(brand.color, Color.WHITE, 0.80f))
        }
    }

    private fun blendColor(baseColor: Int, overlayColor: Int, overlayRatio: Float): Int {
        val ratio = overlayRatio.coerceIn(0f, 1f)
        val inverse = 1f - ratio
        return Color.argb(
            0xFF,
            (Color.red(baseColor) * inverse + Color.red(overlayColor) * ratio).toInt().coerceIn(0, 255),
            (Color.green(baseColor) * inverse + Color.green(overlayColor) * ratio).toInt().coerceIn(0, 255),
            (Color.blue(baseColor) * inverse + Color.blue(overlayColor) * ratio).toInt().coerceIn(0, 255),
        )
    }

    private fun prioritizeFavoriteTeam(games: List<KboGame>): List<KboGame> {
        val teamCode = favoriteTeamCode() ?: return games
        return games.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<KboGame>> { indexed ->
                    indexed.value.containsTeam(teamCode)
                }.thenBy { it.index },
            )
            .map { it.value }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class TeamVisual(
        val color: Int,
        val tint: Int,
        val logoCode: String,
    )

    private data class TeamIdentityCopy(
        val title: String,
        val subtitle: String,
        val chip: String,
        val seal: String,
        val watermark: String,
        val badges: List<String>,
        val footer: String,
    )

    private data class TeamOption(
        val code: String,
        val label: String,
    )

    companion object {
        private const val KEY_ALERTS_ENABLED = "alerts_enabled"
        private const val KEY_FAVORITE_TEAM = "favorite_team"

        private val TEAM_OPTIONS = listOf(
            TeamOption("LG", "LG 트윈스"),
            TeamOption("HANWHA", "한화 이글스"),
            TeamOption("SSG", "SSG 랜더스"),
            TeamOption("SAMSUNG", "삼성 라이온즈"),
            TeamOption("NC", "NC 다이노스"),
            TeamOption("KT", "KT 위즈"),
            TeamOption("LOTTE", "롯데 자이언츠"),
            TeamOption("KIA", "KIA 타이거즈"),
            TeamOption("DOOSAN", "두산 베어스"),
            TeamOption("KIWOOM", "키움 히어로즈"),
        )
    }

    private fun buildSummary(games: List<KboGame>): String {
        if (games.isEmpty()) return "총 0경기"
        val canceled = games.count { it.canceled }
        val live = games.count { !it.canceled && it.status != "경기전" && it.status != "경기종료" }
        val done = games.count { !it.canceled && it.status == "경기종료" }
        val pregame = games.count { !it.canceled && it.status == "경기전" }
        return "총 ${games.size}경기 · 실시간 $live · 종료 $done · 경기전 $pregame · 취소 $canceled"
    }

    private fun KboGame.containsTeam(teamCode: String): Boolean {
        return awayTeam.equals(teamCode, ignoreCase = true) || homeTeam.equals(teamCode, ignoreCase = true)
    }
}

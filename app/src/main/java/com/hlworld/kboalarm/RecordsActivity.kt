package com.hlworld.kboalarm

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

class RecordsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("kbo_alarm_prefs", MODE_PRIVATE) }
    private lateinit var contentContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var searchInput: EditText
    private val recordSection by lazy {
        intent.getStringExtra(EXTRA_RECORD_SECTION) ?: SECTION_TEAM
    }
    private var currentSnapshot: KboRepository.RecordsSnapshot? = null
    private var searchQuery: String = ""
    private var battingVisibleCount: Int = 10
    private var pitchingVisibleCount: Int = 10
    private var battingSortMetric: String = "AVG"
    private var battingSortAsc: Boolean = false
    private var pitchingSortMetric: String = "ERA"
    private var pitchingSortAsc: Boolean = true
    private var playerBattingSortMetric: String = "AVG"
    private var playerBattingSortAsc: Boolean = false
    private var playerPitchingSortMetric: String = "ERA"
    private var playerPitchingSortAsc: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = when (recordSection) {
            SECTION_RANK -> "순위"
            SECTION_PLAYER -> "선수기록실"
            else -> "팀기록실"
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F7FB"))
        }

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }

        scrollView.addView(contentContainer)
        setContentView(scrollView)

        renderLoading()
        loadRecords()
    }

    private fun loadRecords() {
        lifecycleScope.launch {
            try {
                val favoriteTeam = prefs.getString(KEY_FAVORITE_TEAM, null)
                val snapshot = withContext(Dispatchers.IO) {
                    KboRepository.fetchRecordsSnapshot(favoriteTeam)
                }
                renderSnapshot(snapshot)
            } catch (t: Throwable) {
                renderError(t.message ?: "기록 데이터를 불러오지 못했어요.")
            }
        }
    }

    private fun renderLoading() {
        contentContainer.removeAllViews()
        contentContainer.addView(buildHeroCard(
            title = when (recordSection) {
                SECTION_RANK -> "리그 순위"
                SECTION_PLAYER -> "선수기록실"
                else -> "팀기록실"
            },
            subtitle = "실제 KBO 시즌 데이터를 불러오는 중이에요.",
            accent = "#0D3B78",
        ))
        contentContainer.addView(space(14))
        contentContainer.addView(buildMessageCard(
            title = "데이터 준비 중",
            body = when (recordSection) {
                SECTION_RANK -> "공식 KBO 팀 순위표를 읽어오고 있어요."
                SECTION_PLAYER -> "공식 KBO 리더보드를 읽어와서 선수기록을 채우고 있어요."
                else -> "공식 KBO 팀 타격/투구 지표를 읽어와서 팀기록을 채우고 있어요."
            },
        ))
        statusText = TextView(this).apply {
            text = "불러오는 중..."
            setTextColor(Color.parseColor("#6C7A89"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(4), dp(12), dp(4), 0)
        }
        contentContainer.addView(statusText)
    }

    private fun renderError(message: String) {
        contentContainer.removeAllViews()
        contentContainer.addView(buildHeroCard(
            title = when (recordSection) {
                SECTION_RANK -> "리그 순위"
                SECTION_PLAYER -> "선수기록실"
                else -> "팀기록실"
            },
            subtitle = "데이터를 가져오는 데 문제가 생겼어요.",
            accent = "#7A1E2B",
        ))
        contentContainer.addView(space(14))
        contentContainer.addView(buildMessageCard(
            title = "불러오기 실패",
            body = message,
            emphasize = true,
        ))
        contentContainer.addView(space(12))
        contentContainer.addView(buildActionButton("다시 불러오기") {
            loadRecords()
        })
    }

    private fun renderSnapshot(snapshot: KboRepository.RecordsSnapshot) {
        currentSnapshot = snapshot
        battingVisibleCount = 10
        pitchingVisibleCount = 10
        searchQuery = if (::searchInput.isInitialized) {
            searchInput.text?.toString().orEmpty().trim()
        } else {
            ""
        }
        rebuildSnapshotUi()
    }

    private fun rebuildSnapshotUi() {
        val snapshot = currentSnapshot ?: return
        contentContainer.removeAllViews()

        contentContainer.addView(buildHeroCard(
            title = when (recordSection) {
                SECTION_RANK -> "리그 순위"
                SECTION_PLAYER -> "선수기록실"
                else -> "팀기록실"
            },
            subtitle = "공식 KBO 시즌 기록을 실시간에 가깝게 반영합니다. ${snapshot.capturedAt.format(dateFormatter)} 기준",
            accent = "#0D3B78",
        ))
        contentContainer.addView(space(12))
        contentContainer.addView(buildSearchBar())
        when (recordSection) {
            SECTION_RANK -> renderRankRecords(snapshot)
            SECTION_PLAYER -> renderPlayerRecords(snapshot)
            else -> renderTeamRecords(snapshot)
        }
    }

    private fun renderRankRecords(snapshot: KboRepository.RecordsSnapshot) {
        val rankBlocks = mutableListOf<GroupBlock>()
        snapshot.favoriteTeamCode?.let { favoriteTeam ->
            val standing = snapshot.standings.firstOrNull { it.teamCode == favoriteTeam }
            if (standing != null) {
                val summaryRows = listOf(
                    RecordRow(
                        label = displayTeamName(favoriteTeam),
                        value = "${standing.rank}위 · ${standing.wins}승 ${standing.losses}패 ${standing.draws}무 (${standing.pct})",
                        detail = "게임차 ${standing.gb} · 최근 ${standing.streak} · 홈 ${standing.home} / 원정 ${standing.away}",
                    )
                )
                if (matchesSearch(rowSearchText(displayTeamName(favoriteTeam), summaryRows.first().value, summaryRows.first().detail))) {
                    rankBlocks += GroupBlock(
                        title = "내팀 순위",
                        rows = summaryRows,
                    )
                }
            }
        }
        rankBlocks += GroupBlock(
            title = "KBO 전체 순위",
            rows = ensureRows(
                snapshot.standings
                .filter { matchesSearch(rowSearchText(it.rank, it.teamName, it.wins, it.losses, it.draws, it.pct, it.gb, it.streak, it.home, it.away)) }
                .take(10)
                .map { row ->
                    RecordRow(
                        label = "${row.rank}위 ${row.teamName}",
                        value = "${row.wins}승 ${row.losses}패 ${row.draws}무 · 승률 ${row.pct}",
                        detail = "게임차 ${row.gb} · ${row.streak} · 홈 ${row.home} / 원정 ${row.away}",
                    )
                },
            ),
        )

        contentContainer.addView(space(14))
        contentContainer.addView(
            buildGroupedCard(
                title = "리그 순위표",
                subtitle = "2026 KBO 정규시즌 구단 순위입니다.",
                groups = rankBlocks,
            ),
        )
    }

    private fun renderTeamRecords(snapshot: KboRepository.RecordsSnapshot) {
        val teamBlocks = mutableListOf<GroupBlock>()
        snapshot.favoriteTeamCode?.let { favoriteTeam ->
            val batting = snapshot.teamBatting.firstOrNull { it.teamCode == favoriteTeam }
            val pitching = snapshot.teamPitching.firstOrNull { it.teamCode == favoriteTeam }
            val summaryRows = listOf(
                RecordRow(
                    label = displayTeamName(favoriteTeam),
                    value = listOfNotNull(
                        batting?.let { "타율 ${it.avg} · 홈런 ${it.hr}" },
                        pitching?.let { "평균자책 ${it.era}" },
                    ).joinToString(" · ").ifEmpty { "-" },
                    detail = listOfNotNull(
                        batting?.let { "타점 ${it.rbi} · 안타 ${it.hits} · OPS ${it.ops}" },
                        pitching?.let { "승 ${it.wins} · 세이브 ${it.saves} · 홀드 ${it.holds}" },
                    ).joinToString(" · "),
                )
            )
            if (matchesSearch(rowSearchText(displayTeamName(favoriteTeam), summaryRows.first().value, summaryRows.first().detail))) {
                teamBlocks += GroupBlock(
                    title = "내팀 지표 요약",
                    rows = summaryRows,
                )
            }
        }

        // 1. Team Batting with Sorting
        val battingMetrics = listOf(
            "타율" to "AVG",
            "홈런" to "HR",
            "타점" to "RBI",
            "안타" to "H",
            "득점" to "R",
            "출루율" to "OBP",
            "장타율" to "SLG",
            "OPS" to "OPS",
        )
        val sortedBatting = snapshot.teamBatting.sortedWith(Comparator { a, b ->
            val valA = when (battingSortMetric) {
                "AVG" -> a.avg.toDoubleOrNull() ?: 0.0
                "HR" -> a.hr.toDoubleOrNull() ?: 0.0
                "RBI" -> a.rbi.toDoubleOrNull() ?: 0.0
                "H" -> a.hits.toDoubleOrNull() ?: 0.0
                "R" -> a.runs.toDoubleOrNull() ?: 0.0
                "OBP" -> a.obp.toDoubleOrNull() ?: 0.0
                "SLG" -> a.slg.toDoubleOrNull() ?: 0.0
                "OPS" -> a.ops.toDoubleOrNull() ?: 0.0
                else -> a.avg.toDoubleOrNull() ?: 0.0
            }
            val valB = when (battingSortMetric) {
                "AVG" -> b.avg.toDoubleOrNull() ?: 0.0
                "HR" -> b.hr.toDoubleOrNull() ?: 0.0
                "RBI" -> b.rbi.toDoubleOrNull() ?: 0.0
                "H" -> b.hits.toDoubleOrNull() ?: 0.0
                "R" -> b.runs.toDoubleOrNull() ?: 0.0
                "OBP" -> b.obp.toDoubleOrNull() ?: 0.0
                "SLG" -> b.slg.toDoubleOrNull() ?: 0.0
                "OPS" -> b.ops.toDoubleOrNull() ?: 0.0
                else -> b.avg.toDoubleOrNull() ?: 0.0
            }
            if (battingSortAsc) valA.compareTo(valB) else valB.compareTo(valA)
        })

        teamBlocks += GroupBlock(
            title = "팀 타격 순위",
            headerView = buildSortChipBar(battingMetrics, battingSortMetric, battingSortAsc) { key ->
                if (battingSortMetric == key) {
                    battingSortAsc = !battingSortAsc
                } else {
                    battingSortMetric = key
                    battingSortAsc = false
                }
                rebuildSnapshotUi()
            },
            rows = ensureRows(
                sortedBatting
                .filter { matchesSearch(rowSearchText(it.teamName, it.avg, it.hr, it.rbi, it.runs, it.hits, it.ops)) }
                .take(10)
                .mapIndexed { index, row ->
                    val mainVal = when (battingSortMetric) {
                        "AVG" -> "타율 ${row.avg}"
                        "HR" -> "홈런 ${row.hr}개"
                        "RBI" -> "타점 ${row.rbi}점"
                        "H" -> "안타 ${row.hits}개"
                        "R" -> "득점 ${row.runs}점"
                        "OBP" -> "출루율 ${row.obp}"
                        "SLG" -> "장타율 ${row.slg}"
                        "OPS" -> "OPS ${row.ops}"
                        else -> "타율 ${row.avg}"
                    }
                    RecordRow(
                        label = "${index + 1}위 ${row.teamName}",
                        value = mainVal,
                        detail = "AVG ${row.avg} · OBP ${row.obp} · SLG ${row.slg} · OPS ${row.ops} · HR ${row.hr} · RBI ${row.rbi}",
                    )
                },
            ),
        )

        // 2. Team Pitching with Sorting
        val pitchingMetrics = listOf(
            "평균자책" to "ERA",
            "다승" to "W",
            "패전" to "L",
            "세이브" to "SV",
            "홀드" to "HLD",
            "탈삼진" to "SO",
            "피홈런" to "HR",
            "승률" to "WPCT",
            "WHIP" to "WHIP",
        )
        val sortedPitching = snapshot.teamPitching.sortedWith(Comparator { a, b ->
            val valA = when (pitchingSortMetric) {
                "ERA" -> a.era.toDoubleOrNull() ?: 99.0
                "W" -> a.wins.toDoubleOrNull() ?: 0.0
                "L" -> a.losses.toDoubleOrNull() ?: 0.0
                "SV" -> a.saves.toDoubleOrNull() ?: 0.0
                "HLD" -> a.holds.toDoubleOrNull() ?: 0.0
                "SO" -> a.strikeouts.toDoubleOrNull() ?: 0.0
                "HR" -> a.hrAllowed.toDoubleOrNull() ?: 0.0
                "WPCT" -> a.pct.toDoubleOrNull() ?: 0.0
                "WHIP" -> a.whip.toDoubleOrNull() ?: 99.0
                else -> a.era.toDoubleOrNull() ?: 99.0
            }
            val valB = when (pitchingSortMetric) {
                "ERA" -> b.era.toDoubleOrNull() ?: 99.0
                "W" -> b.wins.toDoubleOrNull() ?: 0.0
                "L" -> b.losses.toDoubleOrNull() ?: 0.0
                "SV" -> b.saves.toDoubleOrNull() ?: 0.0
                "HLD" -> b.holds.toDoubleOrNull() ?: 0.0
                "SO" -> b.strikeouts.toDoubleOrNull() ?: 0.0
                "HR" -> b.hrAllowed.toDoubleOrNull() ?: 0.0
                "WPCT" -> b.pct.toDoubleOrNull() ?: 0.0
                "WHIP" -> b.whip.toDoubleOrNull() ?: 99.0
                else -> b.era.toDoubleOrNull() ?: 99.0
            }
            if (pitchingSortAsc) valA.compareTo(valB) else valB.compareTo(valA)
        })

        teamBlocks += GroupBlock(
            title = "팀 투구 순위",
            headerView = buildSortChipBar(pitchingMetrics, pitchingSortMetric, pitchingSortAsc) { key ->
                if (pitchingSortMetric == key) {
                    pitchingSortAsc = !pitchingSortAsc
                } else {
                    pitchingSortMetric = key
                    pitchingSortAsc = (key == "ERA" || key == "WHIP" || key == "L" || key == "HR")
                }
                rebuildSnapshotUi()
            },
            rows = ensureRows(
                sortedPitching
                .filter { matchesSearch(rowSearchText(it.teamName, it.era, it.wins, it.saves, it.holds, it.hrAllowed)) }
                .take(10)
                .mapIndexed { index, row ->
                    val mainVal = when (pitchingSortMetric) {
                        "ERA" -> "ERA ${row.era}"
                        "W" -> "다승 ${row.wins}승"
                        "L" -> "패전 ${row.losses}패"
                        "SV" -> "세이브 ${row.saves}개"
                        "HLD" -> "홀드 ${row.holds}개"
                        "SO" -> "탈삼진 ${row.strikeouts}개"
                        "HR" -> "피홈런 ${row.hrAllowed}개"
                        "WPCT" -> "승률 ${row.pct}"
                        "WHIP" -> "WHIP ${row.whip}"
                        else -> "ERA ${row.era}"
                    }
                    RecordRow(
                        label = "${index + 1}위 ${row.teamName}",
                        value = mainVal,
                        detail = "ERA ${row.era} · ${row.wins}승 ${row.losses}패 · SV ${row.saves} · HLD ${row.holds} · SO ${row.strikeouts}",
                    )
                },
            ),
        )

        contentContainer.addView(space(14))
        contentContainer.addView(
            buildGroupedCard(
                title = "팀기록실",
                subtitle = "각 지표를 클릭하여 오름차순/내림차순 정렬할 수 있습니다.",
                groups = teamBlocks,
            ),
        )
    }

    private fun renderPlayerRecords(snapshot: KboRepository.RecordsSnapshot) {
        contentContainer.addView(space(14))

        // 1. Player Batting Sorting
        val battingMetrics = listOf(
            "타율" to "AVG",
            "안타" to "H",
            "2루타" to "2B",
            "3루타" to "3B",
            "홈런" to "HR",
            "타점" to "RBI",
            "득점" to "R",
            "도루" to "SB",
            "출루율" to "OBP",
            "OPS" to "OPS",
            "WAR" to "WAR",
            "wRC+" to "WRC+",
            "삼진" to "SO",
            "사구" to "HBP",
            "볼넷" to "BB",
            "경기수" to "G",
        )
        val sortedBatting = snapshot.battingLeaders.sortedWith(Comparator { a, b ->
            val valA = when (playerBattingSortMetric) {
                "AVG" -> a.avg.toDoubleOrNull() ?: 0.0
                "H" -> a.hits.toDoubleOrNull() ?: 0.0
                "2B" -> a.b2.toDoubleOrNull() ?: 0.0
                "3B" -> a.b3.toDoubleOrNull() ?: 0.0
                "HR" -> a.hr.toDoubleOrNull() ?: 0.0
                "RBI" -> a.rbi.toDoubleOrNull() ?: 0.0
                "R" -> a.runs.toDoubleOrNull() ?: 0.0
                "SB" -> a.sb.toDoubleOrNull() ?: 0.0
                "OBP" -> a.obp.toDoubleOrNull() ?: 0.0
                "OPS" -> a.ops.toDoubleOrNull() ?: 0.0
                "WAR" -> a.war.toDoubleOrNull() ?: 0.0
                "WRC+" -> a.wrcPlus.toDoubleOrNull() ?: 0.0
                "SO" -> a.so.toDoubleOrNull() ?: 0.0
                "HBP" -> a.hbp.toDoubleOrNull() ?: 0.0
                "BB" -> a.bb.toDoubleOrNull() ?: 0.0
                "G" -> a.games.toDoubleOrNull() ?: 0.0
                else -> a.avg.toDoubleOrNull() ?: 0.0
            }
            val valB = when (playerBattingSortMetric) {
                "AVG" -> b.avg.toDoubleOrNull() ?: 0.0
                "H" -> b.hits.toDoubleOrNull() ?: 0.0
                "2B" -> b.b2.toDoubleOrNull() ?: 0.0
                "3B" -> b.b3.toDoubleOrNull() ?: 0.0
                "HR" -> b.hr.toDoubleOrNull() ?: 0.0
                "RBI" -> b.rbi.toDoubleOrNull() ?: 0.0
                "R" -> b.runs.toDoubleOrNull() ?: 0.0
                "SB" -> b.sb.toDoubleOrNull() ?: 0.0
                "OBP" -> b.obp.toDoubleOrNull() ?: 0.0
                "OPS" -> b.ops.toDoubleOrNull() ?: 0.0
                "WAR" -> b.war.toDoubleOrNull() ?: 0.0
                "WRC+" -> b.wrcPlus.toDoubleOrNull() ?: 0.0
                "SO" -> b.so.toDoubleOrNull() ?: 0.0
                "HBP" -> b.hbp.toDoubleOrNull() ?: 0.0
                "BB" -> b.bb.toDoubleOrNull() ?: 0.0
                "G" -> b.games.toDoubleOrNull() ?: 0.0
                else -> b.avg.toDoubleOrNull() ?: 0.0
            }
            if (playerBattingSortAsc) valA.compareTo(valB) else valB.compareTo(valA)
        })
        val filteredBatting = sortedBatting
            .filter { matchesSearch(rowSearchText(displayPlayerName(it.playerName), it.teamName, it.avg, it.hr, it.rbi, it.games, it.hits, it.runs, it.b2, it.b3, it.sb, it.obp, it.ops, it.war, it.wrcPlus, it.so, it.hbp, it.bb)) }

        // 2. Player Pitching Sorting
        val pitchingMetrics = listOf(
            "평균자책" to "ERA",
            "다승" to "W",
            "탈삼진" to "SO",
            "세이브" to "SV",
            "홀드" to "HLD",
            "패전" to "L",
        )
        val sortedPitching = snapshot.pitchingLeaders.sortedWith(Comparator { a, b ->
            val valA = when (playerPitchingSortMetric) {
                "ERA" -> a.era.toDoubleOrNull() ?: 99.0
                "W" -> a.wins.toDoubleOrNull() ?: 0.0
                "SO" -> a.strikeouts.toDoubleOrNull() ?: 0.0
                "SV" -> a.saves.toDoubleOrNull() ?: 0.0
                "HLD" -> a.holds.toDoubleOrNull() ?: 0.0
                "L" -> a.losses.toDoubleOrNull() ?: 0.0
                else -> a.era.toDoubleOrNull() ?: 99.0
            }
            val valB = when (playerPitchingSortMetric) {
                "ERA" -> b.era.toDoubleOrNull() ?: 99.0
                "W" -> b.wins.toDoubleOrNull() ?: 0.0
                "SO" -> b.strikeouts.toDoubleOrNull() ?: 0.0
                "SV" -> b.saves.toDoubleOrNull() ?: 0.0
                "HLD" -> b.holds.toDoubleOrNull() ?: 0.0
                "L" -> b.losses.toDoubleOrNull() ?: 0.0
                else -> b.era.toDoubleOrNull() ?: 99.0
            }
            if (playerPitchingSortAsc) valA.compareTo(valB) else valB.compareTo(valA)
        })
        val filteredPitching = sortedPitching
            .filter { matchesSearch(rowSearchText(displayPlayerName(it.playerName), it.teamName, it.era, it.wins, it.saves, it.losses, it.holds, it.strikeouts)) }

        contentContainer.addView(
            buildGroupedCard(
                title = "선수기록실",
                subtitle = "각 지표를 클릭하여 오름차순/내림차순 정렬할 수 있습니다.",
                groups = listOf(
                    GroupBlock(
                        title = "타자 기록",
                        headerView = buildSortChipBar(battingMetrics, playerBattingSortMetric, playerBattingSortAsc) { key ->
                            if (playerBattingSortMetric == key) {
                                playerBattingSortAsc = !playerBattingSortAsc
                            } else {
                                playerBattingSortMetric = key
                                playerBattingSortAsc = false
                            }
                            rebuildSnapshotUi()
                        },
                        rows = ensureRows(
                            filteredBatting.take(battingVisibleCount).mapIndexed { index, row ->
                                val mainVal = when (playerBattingSortMetric) {
                                    "AVG" -> "타율 ${row.avg}"
                                    "H" -> "안타 ${row.hits}개"
                                    "2B" -> "2루타 ${row.b2}개"
                                    "3B" -> "3루타 ${row.b3}개"
                                    "HR" -> "홈런 ${row.hr}개"
                                    "RBI" -> "타점 ${row.rbi}점"
                                    "R" -> "득점 ${row.runs}점"
                                    "SB" -> "도루 ${row.sb}개"
                                    "OBP" -> "출루율 ${row.obp}"
                                    "OPS" -> "OPS ${row.ops}"
                                    "WAR" -> "WAR ${row.war}"
                                    "WRC+" -> "wRC+ ${row.wrcPlus}"
                                    "SO" -> "삼진 ${row.so}개"
                                    "HBP" -> "사구 ${row.hbp}개"
                                    "BB" -> "볼넷 ${row.bb}개"
                                    "G" -> "경기수 ${row.games}경기"
                                    else -> "타율 ${row.avg}"
                                }
                                RecordRow(
                                    label = "${index + 1}위 ${displayPlayerName(row.playerName)} (${row.teamName})",
                                    value = mainVal,
                                    detail = "AVG ${row.avg} · 안타 ${row.hits} · 2루타 ${row.b2} · 3루타 ${row.b3} · HR ${row.hr} · 타점 ${row.rbi} · 득점 ${row.runs} · 도루 ${row.sb}",
                                )
                            },
                        ),
                        actionLabel = if (filteredBatting.size > battingVisibleCount) "더보기 ${minOf(10, filteredBatting.size - battingVisibleCount)}개" else null,
                        onAction = if (filteredBatting.size > battingVisibleCount) {
                            {
                                battingVisibleCount = minOf(battingVisibleCount + 10, filteredBatting.size)
                                rebuildSnapshotUi()
                            }
                        } else null,
                    ),
                    GroupBlock(
                        title = "투구 순위",
                        headerView = buildSortChipBar(pitchingMetrics, playerPitchingSortMetric, playerPitchingSortAsc) { key ->
                            if (playerPitchingSortMetric == key) {
                                playerPitchingSortAsc = !playerPitchingSortAsc
                            } else {
                                playerPitchingSortMetric = key
                                playerPitchingSortAsc = (key == "ERA" || key == "L")
                            }
                            rebuildSnapshotUi()
                        },
                        rows = ensureRows(
                            filteredPitching.take(pitchingVisibleCount).mapIndexed { index, row ->
                                val mainVal = when (playerPitchingSortMetric) {
                                    "ERA" -> "ERA ${row.era}"
                                    "W" -> "다승 ${row.wins}승"
                                    "SO" -> "탈삼진 ${row.strikeouts}개"
                                    "SV" -> "세이브 ${row.saves}개"
                                    "HLD" -> "홀드 ${row.holds}개"
                                    "L" -> "패전 ${row.losses}패"
                                    else -> "ERA ${row.era}"
                                }
                                RecordRow(
                                    label = "${index + 1}위 ${displayPlayerName(row.playerName)} (${row.teamName})",
                                    value = mainVal,
                                    detail = "ERA ${row.era} · ${row.wins}승 ${row.losses}패 · K ${row.strikeouts} · SV ${row.saves} · HLD ${row.holds}",
                                )
                            },
                        ),
                        actionLabel = if (filteredPitching.size > pitchingVisibleCount) "더보기 ${minOf(10, filteredPitching.size - pitchingVisibleCount)}개" else null,
                        onAction = if (filteredPitching.size > pitchingVisibleCount) {
                            {
                                pitchingVisibleCount = minOf(pitchingVisibleCount + 10, filteredPitching.size)
                                rebuildSnapshotUi()
                            }
                        } else null,
                    ),
                ),
            ),
        )
    }

    private fun buildHeroCard(title: String, subtitle: String, accent: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundRect(accent, corner = 24)
            elevation = dp(8).toFloat()

            addView(TextView(this@RecordsActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            })
            addView(space(8))
            addView(TextView(this@RecordsActivity).apply {
                text = subtitle
                setTextColor(Color.parseColor("#E6EEF9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(0f, 1.15f)
            })
        }
    }

    private fun buildFavoriteTeamCard(
        teamCode: String,
        standing: KboRepository.TeamStandingRecord?,
        batting: KboRepository.TeamBattingRecord?,
        pitching: KboRepository.TeamPitchingRecord?,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundRect("#FFFFFF", corner = 22)
            elevation = dp(3).toFloat()

            addView(TextView(this@RecordsActivity).apply {
                text = "내팀 기록"
                setTextColor(Color.parseColor("#10233F"))
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            addView(space(4))
            addView(TextView(this@RecordsActivity).apply {
                text = displayTeamName(teamCode)
                setTextColor(Color.parseColor("#0D3B78"))
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            addView(space(12))

            val metrics = listOf(
                "순위" to (standing?.let { "${it.rank}위" } ?: "-"),
                "승률" to (standing?.pct ?: "-"),
                "득점" to (batting?.runs ?: "-"),
                "ERA" to (pitching?.era ?: "-"),
            )

            metrics.forEachIndexed { index, pair ->
                addView(metricPill(pair.first, pair.second))
                if (index != metrics.lastIndex) addView(space(8))
            }

            standing?.let {
                addView(space(12))
                addView(TextView(this@RecordsActivity).apply {
                    text = "전적 ${it.wins}승 ${it.losses}패 ${it.draws}무 · GB ${it.gb} · ${it.streak}"
                    setTextColor(Color.parseColor("#516273"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                })
            }
        }
    }

    private fun buildGroupedCard(
        title: String,
        subtitle: String,
        groups: List<GroupBlock>,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundRect("#FFFFFF", corner = 22)
            elevation = dp(3).toFloat()

            addView(TextView(this@RecordsActivity).apply {
                text = title
                setTextColor(Color.parseColor("#10233F"))
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            addView(space(4))
            addView(TextView(this@RecordsActivity).apply {
                text = subtitle
                setTextColor(Color.parseColor("#6C7A89"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            addView(space(14))

            groups.forEachIndexed { index, group ->
                addView(TextView(this@RecordsActivity).apply {
                    text = group.title
                    setTextColor(Color.parseColor("#10233F"))
                    setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                })
                addView(space(10))
                group.headerView?.let { customHeader ->
                    addView(customHeader)
                    addView(space(10))
                }
                group.rows.forEachIndexed { rowIndex, row ->
                    addView(buildRow(row))
                    if (rowIndex != group.rows.lastIndex) addView(space(8))
                }
                group.actionLabel?.takeIf { group.onAction != null }?.let { actionText ->
                    addView(space(10))
                    addView(buildActionButton(actionText, group.onAction!!))
                }
                if (index != groups.lastIndex) addView(space(14))
            }
        }
    }

    private fun buildSortChipBar(
        metrics: List<Pair<String, String>>,
        currentMetric: String,
        isAsc: Boolean,
        onSelect: (String) -> Unit,
    ): View {
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        metrics.forEachIndexed { idx, (label, key) ->
            val isSelected = currentMetric == key
            val arrow = if (isSelected) (if (isAsc) " ▲" else " ▼") else ""
            val chip = TextView(this).apply {
                text = "$label$arrow"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#334155"))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = roundRect(if (isSelected) "#0D3B78" else "#E2E8F0", corner = 14)
                setOnClickListener { onSelect(key) }
            }
            container.addView(chip)
            if (idx != metrics.lastIndex) {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT)
                })
            }
        }
        scroll.addView(container)
        return scroll
    }

    private fun buildRow(row: RecordRow): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundRect("#F2F5FA", corner = 18)

            val topRow = LinearLayout(this@RecordsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            topRow.addView(TextView(this@RecordsActivity).apply {
                text = row.label
                setTextColor(Color.parseColor("#2A3642"))
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            topRow.addView(TextView(this@RecordsActivity).apply {
                text = row.value
                setTextColor(Color.parseColor("#0D3B78"))
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            })

            addView(topRow)

            row.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                addView(TextView(this@RecordsActivity).apply {
                    text = detail
                    setTextColor(Color.parseColor("#647386"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }
    }

    private fun buildMessageCard(title: String, body: String, emphasize: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundRect("#FFFFFF", corner = 20)
            elevation = dp(2).toFloat()

            addView(TextView(this@RecordsActivity).apply {
                text = title
                setTextColor(Color.parseColor("#10233F"))
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            addView(space(8))
            addView(TextView(this@RecordsActivity).apply {
                text = body
                setTextColor(if (emphasize) Color.parseColor("#7A1E2B") else Color.parseColor("#6C7A89"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setLineSpacing(0f, 1.15f)
            })
        }
    }

    private fun buildActionButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundRect("#0D3B78", corner = 18)
            setOnClickListener { onClick() }
        }
    }

    private fun buildSearchBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundRect("#FFFFFF", corner = 20)
            elevation = dp(2).toFloat()

            addView(TextView(this@RecordsActivity).apply {
                text = "검색"
                setTextColor(Color.parseColor("#10233F"))
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            addView(space(8))
            searchInput = EditText(this@RecordsActivity).apply {
                hint = "팀, 선수 이름을 검색해보세요"
                setTextColor(Color.parseColor("#10233F"))
                setHintTextColor(Color.parseColor("#9AA7B5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundRect("#F4F7FD", corner = 16)
                setSingleLine(true)
                setText(searchQuery)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        searchQuery = s?.toString().orEmpty().trim()
                        currentSnapshot?.let { rebuildSnapshotUi() }
                    }
                })
            }
            addView(searchInput)
        }
    }

    private fun metricPill(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundRect("#F4F7FD", corner = 16)

            addView(TextView(this@RecordsActivity).apply {
                text = label
                setTextColor(Color.parseColor("#556573"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@RecordsActivity).apply {
                text = value
                setTextColor(Color.parseColor("#0D3B78"))
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
        }
    }

    private fun displayTeamName(teamCode: String): String {
        return when (teamCode.uppercase(Locale.US)) {
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
            else -> teamCode
        }
    }

    private fun displayPlayerName(playerName: String): String {
        return playerName
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun rowSearchText(vararg values: Any?): String {
        return values.joinToString(" ") { it?.toString().orEmpty() }
    }

    private fun matchesSearch(rowSearchText: String): Boolean {
        val query = searchQuery.trim()
        if (query.isBlank()) return true
        return rowSearchText.contains(query, ignoreCase = true)
    }

    private fun ensureRows(rows: List<RecordRow>): List<RecordRow> {
        return if (rows.isNotEmpty()) {
            rows
        } else {
            listOf(
                RecordRow(
                    label = "검색 결과가 없어요",
                    value = "다른 팀명이나 선수명으로 다시 찾아보세요",
                )
            )
        }
    }

    private fun space(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp),
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundRect(colorHex: String, corner: Int) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(corner).toFloat()
        setColor(Color.parseColor(colorHex))
    }

    private data class RecordRow(
        val label: String,
        val value: String,
        val detail: String? = null,
    )

    private data class GroupBlock(
        val title: String,
        val rows: List<RecordRow>,
        val headerView: View? = null,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
    )

    companion object {
        const val EXTRA_RECORD_SECTION = "record_section"
        const val SECTION_RANK = "rank"
        const val SECTION_TEAM = "team"
        const val SECTION_PLAYER = "player"
        private const val KEY_FAVORITE_TEAM = "favorite_team"
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.KOREAN)
    }
}

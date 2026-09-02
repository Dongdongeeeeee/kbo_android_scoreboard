package com.hlworld.kboalarm

import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object KboRepository {
    private val kst: ZoneId = ZoneId.of("Asia/Seoul")
    private val teamSet = setOf("LG", "HANWHA", "SSG", "SAMSUNG", "NC", "KT", "LOTTE", "KIA", "DOOSAN", "KIWOOM")
    private val datePattern = Regex("^\\d{2}\\.\\d{2}\\([A-Z]{3}\\)$")
    private val timePattern = Regex("^\\d{1,2}:\\d{2}$")
    private val dateFormatter = DateTimeFormatter.ofPattern("MM.dd(EEE)", Locale.ENGLISH)

    fun fetchTodayGames(date: LocalDate = LocalDate.now(kst)): List<KboGame> {
        val official = fetchGameList(date)
        val scoreboardUrl = "https://eng.koreabaseball.com/Schedule/Scoreboard.aspx?searchDate=$date"
        val scoreboardGames = runCatching { downloadGet(scoreboardUrl).let(::parseScoreboard) }
            .getOrDefault(emptyList())
        val weatherGames = runCatching { fetchTodayWeather(date) }.getOrDefault(emptyList())
        val weatherByGameId = weatherGames.associateBy { it.gameId }
        if (official.isNotEmpty()) {
            val scoreboardByKey = scoreboardGames
                .groupBy { it.matchKey() }
                .mapValues { entry -> entry.value.toMutableList() }
            return official.mapIndexed { index, game ->
                val scoreboardCandidate = scoreboardByKey[game.matchKey()]?.let { candidates ->
                    if (candidates.isNotEmpty()) candidates.removeAt(0) else null
                }
                game.toGame(weatherByGameId[game.gameId] ?: weatherGames.getOrNull(index))
                    .mergeWith(scoreboardCandidate)
            }
        }

        val dateParam = date.toString()
        val dailyUrl = "https://eng.koreabaseball.com/Schedule/DailySchedule.aspx?searchDate=$dateParam"

        val dailyHtml = runCatching { downloadGet(dailyUrl) }.getOrNull() ?: return emptyList()
        val scoreboardHtml = runCatching { downloadGet(scoreboardUrl) }.getOrNull().orEmpty()

        val scheduleGames = parseDailySchedule(dailyHtml, date)
        return if (scheduleGames.isNotEmpty()) scheduleGames else parseScoreboard(scoreboardHtml)
    }

    fun gameIdFor(date: LocalDate, awayTeam: String, homeTeam: String): String {
        return date.format(DateTimeFormatter.BASIC_ISO_DATE) +
            officialTeamCode(awayTeam) + officialTeamCode(homeTeam) + "0"
    }

    fun naverGameId(gameId: String): String {
        if (gameId.length > 13) return gameId
        return gameId + gameId.take(4)
    }

    fun fetchGameLineups(gameId: String): GameLineups {
        val naver = fetchNaverLineups(gameId)
        if (naver.away.isNotEmpty() || naver.home.isNotEmpty()) return naver
        return fetchKboLineups(gameId)
    }

    private fun fetchNaverLineups(gameId: String): GameLineups {
        val raw = runCatching {
            downloadGet("https://api-gw.sports.naver.com/schedule/games/${naverGameId(gameId)}/lineup")
        }.getOrDefault("")
        val root = runCatching { parseJsonValue(raw) as? JSONObject }.getOrNull() ?: return GameLineups(emptyList(), emptyList())
        val data = root.optJSONObject("result")?.opt("lineUpData") ?: return GameLineups(emptyList(), emptyList())
        return parseNaverLineups(data)
    }

    private fun parseNaverLineups(value: Any?): GameLineups {
        val root = when (value) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: return GameLineups(emptyList(), emptyList())
        val away = parseNaverLineupTeam(root.opt("away") ?: root.opt("awayLineUp") ?: root.opt("awayLineup"))
        val home = parseNaverLineupTeam(root.opt("home") ?: root.opt("homeLineUp") ?: root.opt("homeLineup"))
        return GameLineups(away = away, home = home)
    }

    private fun parseNaverLineupTeam(value: Any?): List<LineupEntry> {
        val array = when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("players") ?: value.optJSONArray("lineup") ?: value.optJSONArray("list")
            else -> null
        } ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val player = array.optJSONObject(index) ?: return@mapNotNull null
            val order = player.optStringAny("order", "battingOrder", "seq", "no").ifBlank { (index + 1).toString() }
            val name = player.optStringAny("name", "playerName", "playerNm", "pName")
            val position = player.optStringAny("position", "positionName", "pos", "pPosition")
            if (name.isBlank()) null else LineupEntry(order, name, position)
        }
    }

    private fun fetchKboLineups(gameId: String): GameLineups {
        val gameDate = gameId.take(8)
        val referer = "https://www.koreabaseball.com/Schedule/GameCenter/Preview/LineUp.aspx?gameDate=$gameDate&gameId=$gameId"
        val raw = listOf("0", "0,1,2,3,4,5,6,7,8,9").asSequence()
            .mapNotNull { srId ->
                runCatching {
                    Jsoup.connect("https://www.koreabaseball.com/ws/Schedule.asmx/GetLineUpAnalysis")
                        .method(org.jsoup.Connection.Method.POST)
                        .data("leId", "1")
                        .data("srId", srId)
                        .data("seasonId", gameDate.take(4))
                        .data("gameId", gameId)
                        .header("Accept", "application/json, text/javascript, */*; q=0.01")
                        .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                        .header("Origin", "https://www.koreabaseball.com")
                        .header("Referer", referer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36")
                        .ignoreContentType(true)
                        .timeout(15_000)
                        .execute()
                        .body()
                }.getOrNull()
            }
            .firstOrNull { it.contains("\"rows\"") }
            .orEmpty()
        val root = runCatching { parseJsonValue(raw) as? JSONArray }.getOrNull()
            ?: return GameLineups(emptyList(), emptyList())
        val indexedAway = parseLineupRows(root.opt(4))
        val indexedHome = parseLineupRows(root.opt(3))
        if (indexedAway.isNotEmpty() || indexedHome.isNotEmpty()) {
            return GameLineups(away = indexedAway, home = indexedHome)
        }
        val groups = (0 until root.length()).mapNotNull { parseLineupRows(root.opt(it)).takeIf { rows -> rows.isNotEmpty() } }
        val result = GameLineups(
            away = groups.getOrNull(1).orEmpty(),
            home = groups.getOrNull(0).orEmpty(),
        )
        return result
    }

    private fun parseLineupRows(value: Any?): List<LineupEntry> {
        val payload = when (value) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val rows = payload.optJSONArray("rows") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index)?.optJSONArray("row") ?: return@mapNotNull null
            val cells = (0 until row.length()).map { cellIndex ->
                row.optJSONObject(cellIndex)?.optString("Text").orEmpty().trim()
            }
            val order = cells.firstOrNull { it.toIntOrNull() != null } ?: return@mapNotNull null
            val orderIndex = cells.indexOf(order)
            val values = cells.filterIndexed { cellIndex, _ -> cellIndex != orderIndex && cells[cellIndex].isNotBlank() }
            if (values.size < 2) return@mapNotNull null
            LineupEntry(order, values.getOrNull(1).orEmpty(), values.getOrNull(0).orEmpty())
        }
    }

    fun fetchRecordsSnapshot(favoriteTeamCode: String? = null): RecordsSnapshot {
        val standingsHtml = runCatching {
            downloadGet("https://www.koreabaseball.com/Record/TeamRank/TeamRank.aspx")
        }.getOrDefault("")
        val teamBatting1Html = runCatching {
            downloadGet("https://www.koreabaseball.com/Record/Team/Hitter/Basic1.aspx")
        }.getOrDefault("")
        val teamBatting2Html = runCatching {
            downloadGet("https://www.koreabaseball.com/Record/Team/Hitter/Basic2.aspx")
        }.getOrDefault("")
        val teamPitching1Html = runCatching {
            downloadGet("https://www.koreabaseball.com/Record/Team/Pitcher/Basic1.aspx")
        }.getOrDefault("")
        val teamPitching2Html = runCatching {
            downloadGet("https://www.koreabaseball.com/Record/Team/Pitcher/Basic2.aspx")
        }.getOrDefault("")

        return RecordsSnapshot(
            capturedAt = LocalDate.now(kst),
            favoriteTeamCode = favoriteTeamCode?.let { normalizeTeamCode(it) },
            standings = parseTeamStandings(standingsHtml)
                .sortedBy { it.rank }
                .mapIndexed { index, record -> record.copy(rank = index + 1) },
            teamBatting = parseTeamBattingStats(teamBatting1Html, teamBatting2Html),
            teamPitching = parseTeamPitchingStats(teamPitching1Html, teamPitching2Html),
            battingLeaders = fetchAllBattingLeaders(),
            pitchingLeaders = fetchAllPitchingLeaders(),
        )
    }

    private fun fetchAllBattingLeaders(): List<PlayerBattingRecord> {
        val basic1List = mutableListOf<ParsedBatting1>()
        for (page in 1..4) {
            val html = runCatching {
                downloadGet("https://www.koreabaseball.com/Record/Player/HitterBasic/Basic$page.aspx")
            }.getOrDefault("")
            val pageRecords = parseBattingBasic1Page(html)
            if (pageRecords.isEmpty()) break
            basic1List += pageRecords
            if (pageRecords.size < 10) break
        }

        val basic2Map = mutableMapOf<String, ParsedBatting2>()
        for (page in 1..4) {
            val html = runCatching {
                downloadGet("https://www.koreabaseball.com/Record/Player/HitterBasic/Basic2.aspx?page=$page")
            }.getOrDefault("")
            val pageRecords = parseBattingBasic2Page(html)
            if (pageRecords.isEmpty()) break
            pageRecords.forEach { basic2Map[it.key()] = it }
            if (pageRecords.size < 10) break
        }

        val runnerMap = mutableMapOf<String, String>()
        val runnerHtml = runCatching {
            downloadGet("https://www.koreabaseball.com/Record/Player/Runner/Basic.aspx")
        }.getOrDefault("")
        parseRunnerPage(runnerHtml).forEach { (k, v) -> runnerMap[k] = v }

        return basic1List
            .distinctBy { it.playerName.trim() to it.teamCode.trim().uppercase() }
            .mapIndexed { index, b1 ->
                val key = "${b1.playerName.trim()}|${b1.teamCode.trim().uppercase()}"
                val b2 = basic2Map[key]
                val sb = runnerMap[key] ?: "0"

                val bb = b2?.bb ?: "0"
                val hbp = b2?.hbp ?: "0"
                val so = b2?.so ?: "0"
                val obp = b2?.obp?.takeIf { it.isNotBlank() && it != ".000" } ?: run {
                    val h = b1.hits.toDoubleOrNull() ?: 0.0
                    val b = bb.toDoubleOrNull() ?: 0.0
                    val hp = hbp.toDoubleOrNull() ?: 0.0
                    val ab = b1.ab.toDoubleOrNull() ?: 0.0
                    val denom = ab + b + hp
                    if (denom > 0) String.format(Locale.US, "%.3f", (h + b + hp) / denom) else ".000"
                }
                val slg = b2?.slg?.takeIf { it.isNotBlank() && it != ".000" } ?: run {
                    val tb = b1.tb.toDoubleOrNull() ?: 0.0
                    val ab = b1.ab.toDoubleOrNull() ?: 0.0
                    if (ab > 0) String.format(Locale.US, "%.3f", tb / ab) else ".000"
                }
                val ops = b2?.ops?.takeIf { it.isNotBlank() && it != ".000" } ?: run {
                    val o = obp.toDoubleOrNull() ?: 0.0
                    val s = slg.toDoubleOrNull() ?: 0.0
                    String.format(Locale.US, "%.3f", o + s)
                }

                val pa = b1.pa.toDoubleOrNull() ?: (b1.ab.toDoubleOrNull() ?: 0.0)
                val h = b1.hits.toDoubleOrNull() ?: 0.0
                val hr = b1.hr.toDoubleOrNull() ?: 0.0
                val bVal = bb.toDoubleOrNull() ?: 0.0
                val hpVal = hbp.toDoubleOrNull() ?: 0.0
                val b2B = b1.b2.toDoubleOrNull() ?: 0.0
                val b3B = b1.b3.toDoubleOrNull() ?: 0.0
                val b1B = maxOf(0.0, h - hr - b2B - b3B)

                val wOBA = if (pa > 0) {
                    (0.69 * bVal + 0.72 * hpVal + 0.88 * b1B + 1.24 * b2B + 1.56 * b3B + 1.95 * hr) / pa
                } else 0.330

                val wRAA = if (pa > 0) ((wOBA - 0.330) / 1.15) * pa else 0.0
                val wrcPlusVal = if (pa > 0) {
                    (((wRAA / pa) + 0.115) / 0.115) * 100.0
                } else 100.0

                val sbVal = sb.toDoubleOrNull() ?: 0.0
                val warVal = if (pa > 0) {
                    val battingRuns = wRAA
                    val baseRunning = sbVal * 0.2
                    val posAndRep = (pa / 600.0) * 20.0
                    (battingRuns + baseRunning + posAndRep) / 10.0
                } else 0.0

                PlayerBattingRecord(
                    rank = index + 1,
                    playerName = b1.playerName,
                    teamCode = b1.teamCode,
                    teamName = displayTeamName(b1.teamCode),
                    avg = b1.avg,
                    games = b1.games,
                    hr = b1.hr,
                    rbi = b1.rbi,
                    hits = b1.hits,
                    runs = b1.runs,
                    b2 = b1.b2,
                    b3 = b1.b3,
                    sb = sb,
                    obp = obp,
                    slg = slg,
                    ops = ops,
                    war = String.format(Locale.US, "%.2f", warVal),
                    wrcPlus = String.format(Locale.US, "%.1f", maxOf(0.0, wrcPlusVal)),
                    so = so,
                    hbp = hbp,
                    bb = bb,
                )
            }
    }

    private fun fetchAllPitchingLeaders(): List<PlayerPitchingRecord> {
        val records = mutableListOf<PlayerPitchingRecord>()
        for (page in 1..10) {
            val html = runCatching {
                downloadGet("https://www.koreabaseball.com/Record/Player/PitcherBasic/Basic$page.aspx")
            }.getOrDefault("")
            val pageRecords = parsePitchingLeaders(html, (page - 1) * 10)
            if (pageRecords.isEmpty()) break
            records += pageRecords
            if (pageRecords.size < 10) break
        }
        return records
            // A player can reappear with a different source rank across pages.
            // Use the player/team identity for deduplication, then assign ranks below.
            .distinctBy { it.playerName.trim() to it.teamCode.trim().uppercase() }
            .sortedBy { it.rank }
            .mapIndexed { index, record -> record.copy(rank = index + 1) }
    }

    private fun fetchTodayWeather(date: LocalDate): List<TodayGameWeather> {
        val body = buildForm(
            "gameDate" to date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
            "leId" to "1",
            "srId" to "0,1,2,3,4,5,6,7,8,9",
            "headerCk" to "1",
        )

        val raw = downloadPost(
            "https://www.koreabaseball.com/ws/Schedule.asmx/GetTodayGames",
            body,
            "https://www.koreabaseball.com/Schedule/Weather.aspx?leId=1",
        )

        val root = parseJsonValue(raw)
        val gamesArray = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("gameList")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("result")
                ?: JSONArray()
            else -> JSONArray()
        }

        val games = mutableListOf<TodayGameWeather>()
        for (index in 0 until gamesArray.length()) {
            val obj = gamesArray.optJSONObject(index) ?: continue
            val gameId = obj.optStringAny("gameId", "G_ID", "GAME_ID").blankToNull() ?: continue
            games += TodayGameWeather(
                gameId = gameId,
                stadiumCode = obj.optStringAny("stadium", "STADIUM", "stadiumCode"),
                stadiumName = obj.optStringAny("stadiumFullName", "STADIUM_NM", "stadiumName"),
                weatherName = obj.optStringAny("gameIconName", "iconName", "WEATHER_NM", "weatherName"),
                weatherTemp = obj.optDoubleAny("gameTemp", "temp", "WEATHER_TEMP", "weatherTemp"),
            )
        }
        return games
    }

    private fun fetchGameList(date: LocalDate): List<MutableGame> {
        val body = buildForm(
            "leId" to "1",
            "srId" to "0,1,3,4,5,6,7,9",
            "date" to date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
        )

        val raw = downloadPost(
            "https://www.koreabaseball.com/ws/Main.asmx/GetKboGameList",
            body,
            "https://www.koreabaseball.com/Schedule/DailySchedule.aspx",
        )

        val root = parseJsonValue(raw)
        val gamesArray = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("d")
                ?: root.optJSONArray("game")
                ?: root.optJSONArray("Data")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("result")
                ?: JSONArray()
            else -> JSONArray()
        }

        val games = mutableListOf<MutableGame>()
        for (index in 0 until gamesArray.length()) {
            val item = gamesArray.opt(index) ?: continue
            val obj = item.toJsonObjectOrNull() ?: continue
            val game = parseGameObject(obj) ?: continue
            games += game
        }
        return games
    }

    private fun parseGameObject(obj: JSONObject): MutableGame? {
        val awayTeam = normalizeTeam(
            obj.optStringAny("T_ID", "AWAY_ID", "AWAY_TEAM_ID", "AWAY_TEAM", "t_id", "awayTeam"),
        ) ?: normalizeTeam(obj.optStringAny("T_NM", "AWAY_NM", "awayName")) ?: return null
        val homeTeam = normalizeTeam(
            obj.optStringAny("B_ID", "HOME_ID", "HOME_TEAM_ID", "HOME_TEAM", "b_id", "homeTeam"),
        ) ?: normalizeTeam(obj.optStringAny("B_NM", "HOME_NM", "homeName")) ?: return null

        val game = MutableGame(
            gameId = obj.optStringAny("G_ID", "GAME_ID", "gameId").blankToNull(),
            srId = obj.optStringAny("SR_ID", "SRID", "srId").blankToNull(),
            awayTeam = awayTeam,
            homeTeam = homeTeam,
            time = obj.optGameTime(),
            venue = cleanCell(
                obj.optStringAny(
                    "STADIUM_NM",
                    "STADIUM",
                    "GROUND_NM",
                    "stadium",
                ),
            ),
        )

        val cancelId = obj.optStringAny("CANCEL_SC_ID", "CANCEL_SC", "GAME_CANCEL_SC", "cancel").trim()
        val cancelName = obj.optStringAny("CANCEL_SC_NM", "GAME_CANCEL_SC_NM", "cancelName").trim()
        val state = obj.optStringAny("GAME_STATE_SC", "GAME_STATE", "STATE_SC", "state").trim()
        val stateName = obj.optStringAny("GAME_STATE_NM", "GAME_STATE_NM_KO", "stateName")
        val inningNo = obj.optStringAny("GAME_INN_NO", "INN_NO", "inning")
        val tbName = obj.optStringAny("GAME_TB_SC_NM", "TB_SC_NM", "TB", "half")

        game.canceled = isCanceledGame(cancelId, cancelName)
        game.status = when {
            game.canceled -> "취소"
            state == "1" -> "경기전"
            state == "3" -> "경기종료"
            state == "4" -> if (stateName.isNotBlank()) stateName else "서스펜디드"
            state == "2" || state == "5" -> buildLiveStatus(inningNo, tbName)
            stateName.isNotBlank() -> stateName
            else -> "경기전"
        }

        game.awayScore = obj.optIntAny("T_SCORE", "AWAY_SCORE", "tScore", "awayScore")
        game.homeScore = obj.optIntAny("B_SCORE", "HOME_SCORE", "bScore", "homeScore")

        game.awayPitcherName = choosePitcherName(obj, away = true)
        game.homePitcherName = choosePitcherName(obj, away = false)

        return game
    }

    private fun JSONObject.optGameTime(): String {
        val timePattern = Regex("\\b\\d{1,2}:\\d{2}\\b")
        val preferredKeys = listOf(
            "GAME_TIME", "GAME_STA_TM", "GAME_START_TIME", "GAME_START_TM",
            "GAME_BEGN_DT", "GAME_TM", "G_TIME", "G_TM", "START_TIME", "time",
        )
        val keys = (preferredKeys + keys().asSequence().toList()).distinct()
        for (key in keys) {
            val value = cleanCell(optString(key))
            timePattern.find(value)?.value?.let { return it }
        }
        return ""
    }

    private fun isCanceledGame(cancelId: String, cancelName: String): Boolean {
        val normalizedId = cancelId.trim().uppercase(Locale.US)
        if (normalizedId.isBlank() || normalizedId == "0" || normalizedId == "N") return false

        val normalizedName = cancelName.trim()
        if (normalizedName.isBlank()) return true

        return normalizedName.contains("취소") ||
            normalizedName.contains("연기") ||
            normalizedName.contains("우천") ||
            normalizedName.contains("서스펜") ||
            normalizedName.contains("중지") ||
            normalizedName == "CANCELED" ||
            normalizedName == "POSTPONED"
    }

    private fun choosePitcherName(obj: JSONObject, away: Boolean): String? {
        val candidates = if (away) {
            arrayOf(
                "T_PIT_P_NM",
                "T_PIT_NM",
                "T_P_NM",
                "AWAY_PITCHER_NM",
                "AWAY_PROB_PITCHER_NM",
                "AWAY_PIT_P_NM",
                "AWAY_PIT_NM",
                "T_PITCHER_NM",
            )
        } else {
            arrayOf(
                "B_PIT_P_NM",
                "B_PIT_NM",
                "B_P_NM",
                "HOME_PITCHER_NM",
                "HOME_PROB_PITCHER_NM",
                "HOME_PIT_P_NM",
                "HOME_PIT_NM",
                "B_PITCHER_NM",
            )
        }

        for (key in candidates) {
            val value = obj.optStringFlexible(key).blankToNull()
            if (value != null) return value
        }

        val keywordMatches = if (away) {
            arrayOf("T_PIT", "T_PITCH", "AWAY_PIT", "AWAY_PITCH", "START_PIT")
        } else {
            arrayOf("B_PIT", "B_PITCH", "HOME_PIT", "HOME_PITCH", "START_PIT")
        }
        return findStringByKeyKeywords(obj, keywordMatches)
    }

    private fun buildLiveStatus(inningNo: String, tbName: String): String {
        val inning = inningNo.trim().toIntOrNull()
        val half = when (tbName.trim()) {
            "T", "TOP", "초" -> "초"
            "B", "BOT", "BOTTON", "BOTTOM", "말" -> "말"
            else -> ""
        }
        return if (inning != null && half.isNotBlank()) {
            "${inning}회$half"
        } else if (inning != null) {
            "${inning}회"
        } else {
            "경기전"
        }
    }

    private fun downloadGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
            )
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
        }

        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }

        return stream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
    }

    private fun parseTeamStandings(html: String): List<TeamStandingRecord> {
        val rows = extractTableRows(
            html,
            listOf("순위", "팀명", "승률"),
        )

        return rows.mapIndexedNotNull { index, cells ->
            val rank = index + 1
            val teamCode = normalizeTeamCode(cells.getOrNull(1) ?: return@mapIndexedNotNull null) ?: return@mapIndexedNotNull null
            TeamStandingRecord(
                rank = rank,
                teamCode = teamCode,
                teamName = displayTeamName(teamCode),
                games = cells.getOrNull(2).orEmpty(),
                wins = cells.getOrNull(3).orEmpty(),
                losses = cells.getOrNull(4).orEmpty(),
                draws = cells.getOrNull(5).orEmpty(),
                pct = cells.getOrNull(6).orEmpty(),
                gb = cells.getOrNull(7).orEmpty(),
                streak = cells.getOrNull(8).orEmpty(),
                home = cells.getOrNull(9).orEmpty(),
                away = cells.getOrNull(10).orEmpty(),
            )
        }
    }

    private fun parseTeamBattingStats(html1: String, html2: String): List<TeamBattingRecord> {
        val rows1 = extractTableRows(html1, listOf("팀명", "AVG"))
        val rows2 = extractTableRows(html2, listOf("팀명", "AVG"))

        val extraByTeam = mutableMapOf<String, Triple<String, String, String>>() // teamCode -> (OBP, SLG, OPS)
        for (cells in rows2) {
            val teamCode = cells.mapNotNull { normalizeTeamCode(it) }.firstOrNull() ?: continue
            val offset = cells.indexOfFirst { normalizeTeamCode(it) != null }
            if (offset < 0) continue
            val slg = cells.getOrNull(offset + 7).orEmpty().ifEmpty { cells.getOrNull(8).orEmpty() }
            val obp = cells.getOrNull(offset + 8).orEmpty().ifEmpty { cells.getOrNull(9).orEmpty() }
            val ops = cells.getOrNull(offset + 9).orEmpty().ifEmpty { cells.getOrNull(10).orEmpty() }
            extraByTeam[teamCode] = Triple(obp, slg, ops)
        }

        return rows1.mapNotNull { cells ->
            val teamCode = cells.mapNotNull { normalizeTeamCode(it) }.firstOrNull() ?: return@mapNotNull null
            val offset = cells.indexOfFirst { normalizeTeamCode(it) != null }
            if (offset < 0) return@mapNotNull null

            val avg = cells.getOrNull(offset + 1).orEmpty()
            val games = cells.getOrNull(offset + 2).orEmpty()
            val runs = cells.getOrNull(offset + 5).orEmpty()
            val hits = cells.getOrNull(offset + 6).orEmpty()
            val hr = cells.getOrNull(offset + 9).orEmpty()
            val rbi = cells.getOrNull(offset + 11).orEmpty()

            val extra = extraByTeam[teamCode]
            val obp = extra?.first.orEmpty()
            val slg = extra?.second.orEmpty()
            val ops = extra?.third.orEmpty()

            TeamBattingRecord(
                teamCode = teamCode,
                teamName = displayTeamName(teamCode),
                avg = avg,
                games = games,
                runs = runs,
                hits = hits,
                hr = hr,
                rbi = rbi,
                slg = slg,
                obp = obp,
                ops = ops,
            )
        }
    }

    private fun parseTeamPitchingStats(html1: String, html2: String): List<TeamPitchingRecord> {
        val rows1 = extractTableRows(html1, listOf("팀명", "ERA"))

        return rows1.mapNotNull { cells ->
            val teamCode = cells.mapNotNull { normalizeTeamCode(it) }.firstOrNull() ?: return@mapNotNull null
            val offset = cells.indexOfFirst { normalizeTeamCode(it) != null }
            if (offset < 0) return@mapNotNull null

            val era = cells.getOrNull(offset + 1).orEmpty()
            val wins = cells.getOrNull(offset + 3).orEmpty()
            val losses = cells.getOrNull(offset + 4).orEmpty()
            val saves = cells.getOrNull(offset + 5).orEmpty()
            val holds = cells.getOrNull(offset + 6).orEmpty()
            val pct = cells.getOrNull(offset + 7).orEmpty()
            val hrAllowed = cells.getOrNull(offset + 10).orEmpty()
            val strikeouts = cells.getOrNull(offset + 13).orEmpty()
            val whip = cells.getOrNull(offset + 16).orEmpty()

            TeamPitchingRecord(
                teamCode = teamCode,
                teamName = displayTeamName(teamCode),
                era = era,
                wins = wins,
                losses = losses,
                saves = saves,
                holds = holds,
                pct = pct,
                hrAllowed = hrAllowed,
                strikeouts = strikeouts,
                whip = whip,
            )
        }
    }

    private data class ParsedBatting1(
        val playerName: String,
        val teamCode: String,
        val avg: String,
        val games: String,
        val pa: String,
        val ab: String,
        val runs: String,
        val hits: String,
        val b2: String,
        val b3: String,
        val hr: String,
        val tb: String,
        val rbi: String,
    ) {
        fun key(): String = "${playerName.trim()}|${teamCode.trim().uppercase(Locale.US)}"
    }

    private data class ParsedBatting2(
        val playerName: String,
        val teamCode: String,
        val bb: String,
        val hbp: String,
        val so: String,
        val slg: String,
        val obp: String,
        val ops: String,
    ) {
        fun key(): String = "${playerName.trim()}|${teamCode.trim().uppercase(Locale.US)}"
    }

    private fun parseBattingBasic1Page(html: String): List<ParsedBatting1> {
        val rows = extractTableRows(html, listOf("선수명", "AVG"))
        return rows.mapNotNull { cells ->
            val playerName = cells.getOrNull(1)?.trim().orEmpty()
            val teamCode = normalizeTeamCode(cells.getOrNull(2) ?: return@mapNotNull null) ?: return@mapNotNull null
            if (playerName.isBlank()) return@mapNotNull null
            ParsedBatting1(
                playerName = playerName,
                teamCode = teamCode,
                avg = cells.getOrNull(3).orEmpty(),
                games = cells.getOrNull(4).orEmpty(),
                pa = cells.getOrNull(5).orEmpty(),
                ab = cells.getOrNull(6).orEmpty(),
                runs = cells.getOrNull(7).orEmpty(),
                hits = cells.getOrNull(8).orEmpty(),
                b2 = cells.getOrNull(9).orEmpty(),
                b3 = cells.getOrNull(10).orEmpty(),
                hr = cells.getOrNull(11).orEmpty(),
                tb = cells.getOrNull(12).orEmpty(),
                rbi = cells.getOrNull(13).orEmpty(),
            )
        }
    }

    private fun parseBattingBasic2Page(html: String): List<ParsedBatting2> {
        val rows = extractTableRows(html, listOf("선수명", "BB"))
        return rows.mapNotNull { cells ->
            val playerName = cells.getOrNull(1)?.trim().orEmpty()
            val teamCode = normalizeTeamCode(cells.getOrNull(2) ?: return@mapNotNull null) ?: return@mapNotNull null
            if (playerName.isBlank()) return@mapNotNull null
            ParsedBatting2(
                playerName = playerName,
                teamCode = teamCode,
                bb = cells.getOrNull(4).orEmpty(),
                hbp = cells.getOrNull(6).orEmpty(),
                so = cells.getOrNull(7).orEmpty(),
                slg = cells.getOrNull(9).orEmpty(),
                obp = cells.getOrNull(10).orEmpty(),
                ops = cells.getOrNull(11).orEmpty(),
            )
        }
    }

    private fun parseRunnerPage(html: String): Map<String, String> {
        val rows = extractTableRows(html, listOf("선수명", "SB"))
        val map = mutableMapOf<String, String>()
        for (cells in rows) {
            val playerName = cells.getOrNull(1)?.trim().orEmpty()
            val teamCode = normalizeTeamCode(cells.getOrNull(2) ?: continue) ?: continue
            val sb = cells.getOrNull(5).orEmpty().ifBlank { "0" }
            if (playerName.isNotBlank()) {
                map["${playerName}|${teamCode.uppercase(Locale.US)}"] = sb
            }
        }
        return map
    }

    private fun parsePitchingLeaders(html: String, rankOffset: Int = 0): List<PlayerPitchingRecord> {
        val rows = extractTableRows(
            html,
            listOf("순위", "선수명", "팀명", "ERA"),
        )

        return rows.mapIndexedNotNull { index, cells ->
            val rank = rankOffset + index + 1
            val playerName = cells.getOrNull(1).orEmpty()
            val teamCode = normalizeTeamCode(cells.getOrNull(2) ?: return@mapIndexedNotNull null) ?: return@mapIndexedNotNull null
            PlayerPitchingRecord(
                rank = rank,
                playerName = playerName,
                teamCode = teamCode,
                teamName = displayTeamName(teamCode),
                era = cells.getOrNull(3).orEmpty(),
                wins = cells.getOrNull(7).orEmpty(),
                losses = cells.getOrNull(8).orEmpty(),
                saves = cells.getOrNull(9).orEmpty(),
                holds = cells.getOrNull(10).orEmpty(),
                innings = cells.getOrNull(13).orEmpty(),
                strikeouts = cells.getOrNull(14).orEmpty(),
            )
        }
    }

    private fun extractTableRows(html: String, requiredHeaders: List<String>): List<List<String>> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        val tables = doc.select("table")

        for (table in tables) {
            val rows = table.select("tr")
                .map { row ->
                    row.select("th, td")
                        .map { cleanCell(it.text()) }
                        .filter { it.isNotBlank() }
                }
                .filter { it.isNotEmpty() }

            if (rows.isEmpty()) continue

            val headerIndex = rows.indexOfFirst { row ->
                requiredHeaders.all { required ->
                    row.any { cell -> cell.contains(required, ignoreCase = true) }
                }
            }
            if (headerIndex < 0) continue

            return rows.drop(headerIndex + 1)
                .filter { row -> row.any { it.isNotBlank() } }
        }

        return emptyList()
    }

    private fun parseDailySchedule(html: String, targetDate: LocalDate): List<KboGame> {
        val doc = Jsoup.parse(html)
        val rows = doc.select("tr")
        val games = mutableListOf<MutableGame>()
        val targetLabel = dateFormatter.format(targetDate).uppercase(Locale.ENGLISH)
        var inTargetDateSection = false
        var pending: MutableGame? = null

        for (row in rows) {
            val cells = row.select("th, td")
                .map { cleanCell(it.text()) }
                .filter { it.isNotBlank() }

            if (cells.isEmpty()) continue
            if (cells[0].equals("DATE", ignoreCase = true) || cells[0].equals("TEAM", ignoreCase = true)) continue

            val first = cells[0]

            if (datePattern.matches(first)) {
                inTargetDateSection = first.equals(targetLabel, ignoreCase = true)
                pending = null
                if (!inTargetDateSection && games.isNotEmpty()) {
                    break
                }
                if (inTargetDateSection && cells.size >= 6 && timePattern.matches(cells[2])) {
                    parseDailyGame(cells, targetLabel)?.let {
                        games += it
                        pending = it
                    }
                }
                continue
            }

            if (!inTargetDateSection) continue

            if (timePattern.matches(first) && cells.size >= 4) {
                parseTimeGame(cells, targetLabel)?.let {
                    games += it
                    pending = it
                }
                continue
            }

            val currentPending = pending
            if (currentPending != null) {
                applyDetailRow(currentPending, cells)
                pending = null
            }
        }

        return games.map { it.toGame() }
    }

    private fun parseDailyGame(cells: List<String>, currentDateLabel: String): MutableGame? {
        if (cells.size < 6) return null
        val away = normalizeTeam(cells.getOrNull(3) ?: return null) ?: return null
        val home = normalizeTeam(cells.getOrNull(5) ?: return null) ?: return null
        val scoreText = cells.getOrNull(4) ?: ":"
        val time = cells.getOrNull(2) ?: ""
        return buildGame(away, home, time, scoreText, currentDateLabel)
    }

    private fun parseTimeGame(cells: List<String>, currentDateLabel: String?): MutableGame? {
        if (currentDateLabel == null || cells.size < 4) return null
        val time = cells[0]
        val away = normalizeTeam(cells[1]) ?: return null
        val scoreText = cells[2]
        val home = normalizeTeam(cells[3]) ?: return null
        return buildGame(away, home, time, scoreText, currentDateLabel)
    }

    private fun buildGame(
        away: String,
        home: String,
        time: String,
        scoreText: String,
        currentDateLabel: String,
    ): MutableGame {
        val game = MutableGame(
            gameId = null,
            srId = null,
            awayTeam = away,
            homeTeam = home,
            time = time,
        )

        val score = scoreText.replace(" ", "")
        when {
            score == ":" || score.isBlank() -> game.status = "경기전"
            score.contains(":") -> {
                val parts = score.split(":", limit = 2)
                game.awayScore = parts.getOrNull(0)?.toIntOrNull()
                game.homeScore = parts.getOrNull(1)?.toIntOrNull()
                game.status = "경기전"
            }
            else -> game.status = scoreText
        }

        return game
    }

    private fun applyDetailRow(game: MutableGame, cells: List<String>) {
        val upper = cells.map { it.uppercase(Locale.US) }
        if (upper.any { it.contains("POSTPONED") || it.contains("CANCELED") || it.contains("취소") }) {
            game.canceled = true
            game.status = "취소"
        }

        val venue = cells.firstOrNull {
            it.isNotBlank() &&
                it != "-" &&
                !timePattern.matches(it) &&
                normalizeTeam(it) == null &&
                !it.equals("POSTPONED", ignoreCase = true) &&
                !it.equals("CANCELED", ignoreCase = true)
        }

        if (!venue.isNullOrBlank()) {
            game.venue = venue
        }
    }

    private fun parseScoreboard(html: String): List<KboGame> {
        val doc = Jsoup.parse(html)
        val text = doc.text()
        if (text.contains("No Data Available", ignoreCase = true)) return emptyList()

        // KBO 스코어보드 HTML은 보통 "팀명 + 점수 + 상태 + 팀명" 형태의 한 줄 텍스트로 더 안정적으로 읽힌다.
        // 우선 텍스트 기반 파서를 사용하고, 실패할 때만 표 기반 파서를 시도한다.
        val textGames = parseScoreboardTextFallback(doc)
        if (textGames.isNotEmpty()) return textGames

        val pairedGames = parseScoreboardPairedRows(doc)
        if (pairedGames.isNotEmpty()) return pairedGames

        val rows = doc.select("tr")
        val games = mutableListOf<KboGame>()

        for (row in rows) {
            val cells = row.select("th, td")
                .map { cleanCell(it.text()) }
                .filter { it.isNotBlank() }

            if (cells.size < 4) continue

            val teamIndexes = cells.mapIndexedNotNull { index, cell ->
                if (normalizeTeam(cell) != null) index else null
            }
            if (teamIndexes.size < 2) continue

            val awayIndex = teamIndexes.first()
            val homeIndex = teamIndexes.last()
            if (homeIndex <= awayIndex) continue

            val away = normalizeTeam(cells[awayIndex]) ?: continue
            val home = normalizeTeam(cells[homeIndex]) ?: continue
            val middle = cells.subList(awayIndex + 1, homeIndex)
            val numeric = middle.firstOrNull { it.matches(Regex("^\\d+$")) } ?: continue

            val awayScore = numeric.toIntOrNull()
            val homeScore = middle.dropWhile { !it.matches(Regex("^\\d+$")) }.drop(1).firstOrNull()?.toIntOrNull()
            val status = when {
                middle.any { it.equals("FINAL", ignoreCase = true) } -> "경기종료"
                middle.any { it.equals("POSTPONED", ignoreCase = true) } -> "취소"
                middle.any { it.matches(timePattern) } -> "경기전"
                else -> "경기전"
            }

            games += KboGame(
                awayTeam = away,
                homeTeam = home,
                awayScore = awayScore,
                homeScore = homeScore,
                time = "",
                venue = "",
                canceled = status == "취소",
                status = status,
            )
        }

        return games
    }

    private fun parseScoreboardPairedRows(doc: org.jsoup.nodes.Document): List<KboGame> {
        val rows = doc.select("tr")
        val games = mutableListOf<KboGame>()
        var rIndex = -1
        var pending: ParsedScoreboardRow? = null

        for (row in rows) {
            val cells = row.select("th, td").map { cleanCell(it.text()) }
            if (cells.isEmpty()) continue

            if (cells.any { it.equals("TEAM", ignoreCase = true) } && cells.any { it.equals("R", ignoreCase = true) }) {
                rIndex = cells.indexOfFirst { it.equals("R", ignoreCase = true) }
                pending = null
                continue
            }

            if (rIndex < 0 || cells.size <= rIndex) continue

            val teamIndex = cells.indexOfFirst { normalizeTeam(it) != null }
            if (teamIndex < 0) continue

            val team = normalizeTeam(cells[teamIndex]) ?: continue
            val score = cells.getOrNull(rIndex)?.takeIf { it.isNotBlank() && it.matches(Regex("^\\d+$")) }?.toIntOrNull()
                ?: continue

            val middleText = cells.subList(teamIndex + 1, rIndex).joinToString(" ")
            val status = parseScoreboardStatus(middleText)
            val teamRow = ParsedScoreboardRow(team = team, score = score, status = status)

            val first = pending
            if (first == null) {
                pending = teamRow
            } else {
                games += KboGame(
                    awayTeam = first.team,
                    homeTeam = teamRow.team,
                    awayScore = first.score,
                    homeScore = teamRow.score,
                    time = "",
                    venue = "",
                    canceled = first.status == "취소" || teamRow.status == "취소",
                    status = when {
                        first.status == "경기종료" || teamRow.status == "경기종료" -> "경기종료"
                        first.status == "취소" || teamRow.status == "취소" -> "취소"
                        first.status.isNotBlank() && first.status != "경기전" -> first.status
                        teamRow.status.isNotBlank() && teamRow.status != "경기전" -> teamRow.status
                        else -> "경기전"
                    },
                )
                pending = null
            }
        }

        return games
    }

    private fun parseScoreboardTextFallback(doc: org.jsoup.nodes.Document): List<KboGame> {
        val rawLines = runCatching { doc.body()?.wholeText() }.getOrNull()
            ?.lines()
            .orEmpty()

        val games = mutableListOf<KboGame>()

        for (rawLine in rawLines) {
            val normalized = cleanCell(rawLine)
                .replace(Regex("Image:\\s*[A-Za-z]+"), " ")

            if (normalized.isBlank()) continue
            if (normalized.equals("TEAM", ignoreCase = true)) continue
            if (normalized.contains("No Data Available", ignoreCase = true)) continue

            val tokens = normalized
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val teamPositions = tokens.mapIndexedNotNull { index, token ->
                if (normalizeTeam(token) != null) index else null
            }
            if (teamPositions.size < 2) continue

            val awayIndex = teamPositions.first()
            val homeIndex = teamPositions.last()
            if (homeIndex <= awayIndex) continue

            val away = normalizeTeam(tokens[awayIndex]) ?: continue
            val home = normalizeTeam(tokens[homeIndex]) ?: continue
            val middle = tokens.subList(awayIndex + 1, homeIndex)
            val numericTokens = middle.filter { it.matches(Regex("^\\d+$")) }
            if (numericTokens.isEmpty()) continue

            val awayScore = numericTokens.firstOrNull()?.toIntOrNull()
            val homeScore = numericTokens.lastOrNull()?.toIntOrNull() ?: awayScore
            val middleText = middle.joinToString(" ")
            val status = parseScoreboardStatus(middleText)

            games += KboGame(
                awayTeam = away,
                homeTeam = home,
                awayScore = awayScore,
                homeScore = homeScore,
                time = "",
                venue = "",
                canceled = status == "취소",
                status = status,
            )
        }

        return games
    }

    private fun parseScoreboardStatus(rawText: String): String {
        val middleText = cleanCell(rawText)
        val upperMiddle = middleText.uppercase(Locale.US)
        return when {
            upperMiddle.contains("FINAL") || middleText.contains("경기종료") -> "경기종료"
            upperMiddle.contains("POSTPONED") || middleText.contains("취소") -> "취소"
            Regex("\\d+회[초말]?").containsMatchIn(middleText) -> {
                Regex("\\d+회[초말]?").find(middleText)?.value ?: "경기전"
            }
            upperMiddle.contains("LIVE") || upperMiddle.contains("IN PROGRESS") || upperMiddle.contains("PLAYING") -> "경기전"
            else -> "경기전"
        }
    }

    private fun downloadPost(url: String, body: String, referer: String): String {
        val payload = body.toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
            )
            setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
            setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("Origin", "https://www.koreabaseball.com")
            setRequestProperty("Referer", referer)
            setFixedLengthStreamingMode(payload.size)
        }

        connection.outputStream.use { output ->
            output.write(payload)
        }

        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }

        return stream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
    }

    private fun buildForm(vararg fields: Pair<String, String>): String {
        return fields.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun parseJsonValue(raw: String): Any {
        // KBO responses may begin with a UTF-8 BOM, which prevents JSON detection.
        val trimmed = raw.trim().removePrefix("\uFEFF").trim()
        return when {
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                when {
                    obj.has("d") -> unwrapJsonValue(obj.get("d"))
                    obj.has("Data") -> unwrapJsonValue(obj.get("Data"))
                    obj.has("data") -> unwrapJsonValue(obj.get("data"))
                    else -> obj
                }
            }
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("\"[") || trimmed.startsWith("\"{") -> unwrapJsonValue(JSONObject("{\"d\":$trimmed}").get("d"))
            else -> trimmed
        }
    }

    private fun unwrapJsonValue(value: Any?): Any {
        return when (value) {
            is String -> {
                val trimmed = value.trim()
                when {
                    trimmed.startsWith("{") -> JSONObject(trimmed)
                    trimmed.startsWith("[") -> JSONArray(trimmed)
                    else -> value
                }
            }
            is JSONObject, is JSONArray -> value
            else -> value ?: ""
        }
    }

    private fun Any?.toJsonObjectOrNull(): JSONObject? {
        return when (this) {
            is JSONObject -> this
            is String -> {
                val trimmed = trim()
                if (trimmed.startsWith("{")) JSONObject(trimmed) else null
            }
            else -> null
        }
    }

    private fun JSONObject.optStringAny(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun JSONObject.optStringFlexible(key: String): String {
        val direct = optString(key)
        if (direct.isNotBlank()) return direct

        val normalizedTarget = key.replace("_", "").uppercase(Locale.US)
        val names = keys()
        while (names.hasNext()) {
            val actual = names.next()
            val normalizedActual = actual.replace("_", "").uppercase(Locale.US)
            if (normalizedActual == normalizedTarget) {
                val value = optString(actual)
                if (value.isNotBlank()) return value
            }
        }
        return ""
    }

    private fun findStringByKeyKeywords(value: Any?, keywords: Array<String>): String? {
        when (value) {
            is JSONObject -> {
                val names = value.keys()
                while (names.hasNext()) {
                    val key = names.next()
                    val child = value.opt(key)
                    val upperKey = key.uppercase(Locale.US)
                    if (keywords.any { upperKey.contains(it) }) {
                        when (child) {
                            is String -> child.blankToNull()?.let { return it }
                            is JSONArray -> {
                                val nested = findStringByKeyKeywords(child, keywords)
                                if (nested != null) return nested
                            }
                            is JSONObject -> {
                                val nested = findStringByKeyKeywords(child, keywords)
                                if (nested != null) return nested
                            }
                        }
                    } else {
                        val nested = findStringByKeyKeywords(child, keywords)
                        if (nested != null) return nested
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val nested = findStringByKeyKeywords(value.opt(index), keywords)
                    if (nested != null) return nested
                }
            }
            is String -> {
                val trimmed = value.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                    return trimmed
                }
                if (trimmed.startsWith("{")) {
                    return findStringByKeyKeywords(JSONObject(trimmed), keywords)
                }
                if (trimmed.startsWith("[")) {
                    return findStringByKeyKeywords(JSONArray(trimmed), keywords)
                }
            }
        }
        return null
    }

    private fun JSONObject.optIntAny(vararg keys: String): Int? {
        for (key in keys) {
            val value = optString(key)
            if (value.isNotBlank()) return value.trim().toIntOrNull()
            if (has(key)) {
                val intValue = optInt(key, Int.MIN_VALUE)
                if (intValue != Int.MIN_VALUE) return intValue
            }
        }
        return null
    }

    private fun cleanCell(value: String): String {
        return value
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeTeamCode(value: String): String? {
        val upper = cleanCell(value).uppercase(Locale.US)
        return when (upper) {
            "LT" -> "LOTTE"
            "OB" -> "DOOSAN"
            "HH" -> "HANWHA"
            "HT" -> "KIA"
            "WO" -> "KIWOOM"
            "SK" -> "SSG"
            "SS" -> "SAMSUNG"
            "삼성" -> "SAMSUNG"
            "두산" -> "DOOSAN"
            "롯데" -> "LOTTE"
            "한화" -> "HANWHA"
            "키움" -> "KIWOOM"
            "LG 트윈스", "LG TWINS", "LG" -> "LG"
            "NC 다이노스", "NC DINOS", "NC" -> "NC"
            "KT 위즈", "KT WIZ", "KT" -> "KT"
            "롯데 자이언츠", "LOTTE GIANTS", "LOTTE" -> "LOTTE"
            "KIA 타이거즈", "KIA TIGERS", "KIA" -> "KIA"
            "두산 베어스", "DOOSAN BEARS", "DOOSAN" -> "DOOSAN"
            "키움 히어로즈", "KIWOOM HEROES", "KIWOOM" -> "KIWOOM"
            "한화 이글스", "HANWHA EAGLES", "HANWHA" -> "HANWHA"
            "SSG 랜더스", "SSG LANDERS", "SSG" -> "SSG"
            "삼성 라이온즈", "SAMSUNG LIONS", "SAMSUNG" -> "SAMSUNG"
            else -> null
        }
    }

    private fun officialTeamCode(value: String): String {
        return when (normalizeTeamCode(value)) {
            "DOOSAN" -> "OB"
            "KIA" -> "HT"
            "SAMSUNG" -> "SS"
            "KIWOOM" -> "WO"
            "LOTTE" -> "LT"
            "HANWHA" -> "HH"
            else -> normalizeTeamCode(value).orEmpty()
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

    private fun normalizeTeam(value: String): String? = normalizeTeamCode(value)

    private fun String.blankToNull(): String? = trim().takeIf { it.isNotBlank() }

    private fun MutableGame.matchKey(): String {
        return "${awayTeam.uppercase(Locale.US)}|${homeTeam.uppercase(Locale.US)}"
    }

    private fun KboGame.matchKey(): String {
        return "${awayTeam.uppercase(Locale.US)}|${homeTeam.uppercase(Locale.US)}"
    }

    private fun KboGame.mergeWith(scoreboard: KboGame?): KboGame {
        if (scoreboard == null) return this

        return copy(
            time = time.ifBlank { scoreboard.time },
            venue = venue.ifBlank { scoreboard.venue },
            weather = weather ?: scoreboard.weather,
            awayScore = scoreboard.awayScore ?: awayScore,
            homeScore = scoreboard.homeScore ?: homeScore,
            canceled = canceled || scoreboard.canceled,
            status = when {
                canceled || scoreboard.canceled -> "취소"
                scoreboard.status == "경기종료" -> "경기종료"
                scoreboard.status == "취소" -> "취소"
                scoreboard.status.matches(Regex("^\\d+회[초말]?$")) -> scoreboard.status
                status.isNotBlank() -> status
                else -> scoreboard.status
            },
        )
    }

    private data class ParsedScoreboardRow(
        val team: String,
        val score: Int,
        val status: String,
    )

    data class RecordsSnapshot(
        val capturedAt: LocalDate,
        val favoriteTeamCode: String?,
        val standings: List<TeamStandingRecord>,
        val teamBatting: List<TeamBattingRecord>,
        val teamPitching: List<TeamPitchingRecord>,
        val battingLeaders: List<PlayerBattingRecord>,
        val pitchingLeaders: List<PlayerPitchingRecord>,
    )

    data class LineupEntry(
        val order: String,
        val position: String,
        val playerName: String,
    )

    data class GameLineups(
        val away: List<LineupEntry>,
        val home: List<LineupEntry>,
    )

    data class TeamStandingRecord(
        val rank: Int,
        val teamCode: String,
        val teamName: String,
        val games: String,
        val wins: String,
        val losses: String,
        val draws: String,
        val pct: String,
        val gb: String,
        val streak: String,
        val home: String,
        val away: String,
    )

    data class TeamBattingRecord(
        val teamCode: String,
        val teamName: String,
        val avg: String,
        val games: String,
        val runs: String,
        val hits: String,
        val hr: String,
        val rbi: String,
        val slg: String,
        val obp: String,
        val ops: String,
    )

    data class TeamPitchingRecord(
        val teamCode: String,
        val teamName: String,
        val era: String,
        val wins: String,
        val losses: String,
        val saves: String,
        val holds: String,
        val pct: String,
        val hrAllowed: String,
        val strikeouts: String = "",
        val whip: String = "",
    )

    data class PlayerBattingRecord(
        val rank: Int,
        val playerName: String,
        val teamCode: String,
        val teamName: String,
        val avg: String,
        val games: String,
        val hr: String,
        val rbi: String,
        val hits: String,
        val runs: String = "0",
        val b2: String = "0",
        val b3: String = "0",
        val sb: String = "0",
        val obp: String = ".000",
        val slg: String = ".000",
        val ops: String = ".000",
        val war: String = "0.00",
        val wrcPlus: String = "100.0",
        val so: String = "0",
        val hbp: String = "0",
        val bb: String = "0",
    )

    data class PlayerPitchingRecord(
        val rank: Int,
        val playerName: String,
        val teamCode: String,
        val teamName: String,
        val era: String,
        val wins: String,
        val losses: String,
        val saves: String,
        val holds: String,
        val innings: String,
        val strikeouts: String,
    )

    private data class MutableGame(
        var gameId: String?,
        var srId: String?,
        var awayTeam: String,
        var homeTeam: String,
        var time: String,
        var venue: String = "",
        var awayScore: Int? = null,
        var homeScore: Int? = null,
        var status: String = "경기전",
        var canceled: Boolean = false,
        var awayPitcherName: String? = null,
        var homePitcherName: String? = null,
        var weather: String? = null,
    ) {
        fun toGame(weatherInfo: TodayGameWeather? = null): KboGame {
            return KboGame(
                gameId = gameId,
                awayTeam = awayTeam,
                homeTeam = homeTeam,
                awayScore = awayScore,
                homeScore = homeScore,
                time = time,
                venue = venue.ifBlank { defaultVenue(homeTeam) },
                canceled = canceled,
                status = status,
                awayPitcherName = awayPitcherName,
                homePitcherName = homePitcherName,
                weather = weatherInfo?.toLabel() ?: weather,
            )
        }
    }

    private data class TodayGameWeather(
        val gameId: String,
        val stadiumCode: String,
        val stadiumName: String,
        val weatherName: String,
        val weatherTemp: Double?,
    ) {
        fun toLabel(): String? {
            val name = weatherName.trim()
            val temp = weatherTemp?.let { "${kotlin.math.round(it).toInt()}℃" }.orEmpty()
            return listOf(name, temp).filter { it.isNotBlank() }.joinToString(" ").trim().takeIf { it.isNotBlank() }
        }
    }

    private fun defaultVenue(homeTeam: String): String = when (normalizeTeamCode(homeTeam)) {
        "LG", "DOOSAN" -> "잠실야구장"
        "HANWHA" -> "대전 한화생명 볼파크"
        "SSG" -> "인천 SSG랜더스필드"
        "SAMSUNG" -> "대구 삼성라이온즈파크"
        "NC" -> "창원NC파크"
        "KT" -> "수원KT위즈파크"
        "LOTTE" -> "사직야구장"
        "KIA" -> "광주-KIA 챔피언스필드"
        "KIWOOM" -> "고척스카이돔"
        else -> "구장 정보 없음"
    }

    private fun JSONObject.optDoubleAny(vararg keys: String): Double? {
        for (key in keys) {
            val value = opt(key)
            when (value) {
                is Number -> return value.toDouble()
                is String -> value.trim().toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }
}

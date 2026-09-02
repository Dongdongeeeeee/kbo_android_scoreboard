package com.hlworld.kboalarm

data class KboGame(
    val gameId: String? = null,
    val awayTeam: String,
    val homeTeam: String,
    val awayScore: Int? = null,
    val homeScore: Int? = null,
    val time: String,
    val venue: String,
    val canceled: Boolean,
    val status: String,
    val awayPitcherName: String? = null,
    val homePitcherName: String? = null,
    val weather: String? = null,
) {
    fun titleLine(): String = "${displayTeamName(homeTeam)} vs ${displayTeamName(awayTeam)}"

    fun scoreLine(): String = when {
        canceled -> "취소"
        homeScore != null && awayScore != null -> "$homeScore:$awayScore"
        else -> "0:0"
    }

    fun pitcherLine(): String? {
        val home = homePitcherName?.trim().orEmpty()
        val away = awayPitcherName?.trim().orEmpty()
        return when {
            away.isBlank() && home.isBlank() -> null
            home.isNotBlank() && away.isNotBlank() -> "선발: $home / $away"
            home.isNotBlank() -> "선발: $home"
            else -> "선발: $away"
        }
    }

    fun weatherLabel(): String? = weather?.trim()?.takeIf { it.isNotBlank() }

    fun notificationLine(): String {
        val firstLine = "${titleLine()} ${scoreLine()}"
        val pitcher = pitcherLine()
        return buildList {
            add(firstLine)
            pitcher?.let { add(it) }
        }.joinToString("\n")
    }

    private fun displayTeamName(value: String): String {
        return when (value.uppercase()) {
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
            else -> value
        }
    }
}

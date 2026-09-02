package com.hlworld.kboalarm

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.time.LocalDate

class GameDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gameDate = intent.getStringExtra(EXTRA_GAME_DATE)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        } ?: LocalDate.now()
        val awayTeam = intent.getStringExtra(EXTRA_AWAY_TEAM).orEmpty()
        val homeTeam = intent.getStringExtra(EXTRA_HOME_TEAM).orEmpty()
        val gameId = intent.getStringExtra(EXTRA_GAME_ID).orEmpty().ifBlank {
            KboRepository.gameIdFor(gameDate, awayTeam, homeTeam)
        }
        val game = KboGame(
            gameId = gameId,
            awayTeam = awayTeam,
            homeTeam = homeTeam,
            awayScore = intent.getIntExtra(EXTRA_AWAY_SCORE, -1).takeIf { it >= 0 },
            homeScore = intent.getIntExtra(EXTRA_HOME_SCORE, -1).takeIf { it >= 0 },
            time = intent.getStringExtra(EXTRA_TIME).orEmpty(),
            venue = intent.getStringExtra(EXTRA_VENUE).orEmpty(),
            canceled = intent.getBooleanExtra(EXTRA_CANCELED, false),
            status = intent.getStringExtra(EXTRA_STATUS).orEmpty(),
            awayPitcherName = intent.getStringExtra(EXTRA_AWAY_PITCHER),
            homePitcherName = intent.getStringExtra(EXTRA_HOME_PITCHER),
            weather = intent.getStringExtra(EXTRA_WEATHER),
        )

        val awayStyle = teamStyle(game.awayTeam)
        val homeStyle = teamStyle(game.homeTeam)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 48)
        }

        root.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(buildDetailHeader(game))
        content.addView(buildScoreboardPanel(game, homeStyle, awayStyle))
        content.addView(buildSnapshotPanel(game))
        content.addView(buildStarterPanel(game, homeStyle, awayStyle))
        val lineupPanel = buildLineupPanel()
        content.addView(lineupPanel)
        lifecycleScope.launch {
            val lineups = withContext(Dispatchers.IO) {
                val candidateIds = listOf(
                    game.gameId,
                    KboRepository.gameIdFor(gameDate, game.awayTeam, game.homeTeam),
                ).filterNotNull().filter { it.isNotBlank() }.distinct()
                candidateIds
                    .asSequence()
                    .map { KboRepository.fetchGameLineups(it) }
                    .firstOrNull { it.away.isNotEmpty() || it.home.isNotEmpty() }
                    ?: KboRepository.GameLineups(emptyList(), emptyList())
            }
            renderLineupPanel(lineupPanel, game, lineups)
        }
        content.addView(buildFooterNote())

        setContentView(root)
    }

    private fun buildDetailHeader(game: KboGame): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 6)

            addView(
                LinearLayout(this@GameDetailActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@GameDetailActivity).apply {
                            text = "KBO DETAIL"
                            textSize = 12f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(Color.WHITE)
                            setPadding(12, 7, 12, 7)
                            background = pillDrawable(0x1EFFFFFF, 0x33FFFFFF)
                        }
                    )
                    addView(
                        View(this@GameDetailActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                        }
                    )
                    addView(
                        TextView(this@GameDetailActivity).apply {
                            text = game.weatherLabel()?.let { "날씨 $it" } ?: "경기 당시 날씨 정보 없음"
                            textSize = 12f
                            setTextColor(0xFFE2E7F3.toInt())
                            setPadding(0, 0, 0, 0)
                        }
                    )
                }
            )

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = "${displayTeamLabel(game.homeTeam)} · ${displayTeamLabel(game.awayTeam)}"
                    textSize = 27f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(0, 16, 0, 2)
                }
            )

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = if (game.canceled) "오늘 경기는 취소됐습니다." else "점수와 선발투수, 구장 상황을 한 번에 볼 수 있어요."
                    textSize = 14f
                    setTextColor(0xFFB9C1D0.toInt())
                    setPadding(0, 4, 0, 0)
                    setLineSpacing(2f, 1f)
                }
            )
        }
    }

    private fun buildScoreboardPanel(game: KboGame, homeStyle: TeamVisual, awayStyle: TeamVisual): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 10, 18, 8)

            addView(
                LinearLayout(this@GameDetailActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(18, 18, 18, 18)
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            0xFF121826.toInt(),
                            withAlpha(homeStyle.color, 38),
                            withAlpha(awayStyle.color, 38),
                            0xFF0C0F16.toInt(),
                        )
                    ).apply {
                        cornerRadius = 30f
                        setStroke(1, 0x223E4A66)
                    }

                    addView(
                        LinearLayout(this@GameDetailActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                compactTeamNode(
                                    label = "홈",
                                    teamName = displayTeamLabel(game.homeTeam),
                                    logoCode = homeStyle.logoCode,
                                    accent = homeStyle.color,
                                )
                            )
                            addView(
                                LinearLayout(this@GameDetailActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                    gravity = Gravity.CENTER
                                    addView(
                                        TextView(this@GameDetailActivity).apply {
                                            text = game.scoreLine()
                                            textSize = 52f
                                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                                            setTextColor(Color.WHITE)
                                            gravity = Gravity.CENTER
                                        }
                                    )
                                    addView(
                                        TextView(this@GameDetailActivity).apply {
                                            text = when {
                                                game.canceled -> "취소"
                                                game.status.isNotBlank() -> game.status
                                                else -> "경기 정보"
                                            }
                                            textSize = 15f
                                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                                            setTextColor(0xFFE7EBF4.toInt())
                                            gravity = Gravity.CENTER
                                            setPadding(0, 4, 0, 0)
                                        }
                                    )
                                }
                            )
                            addView(
                                compactTeamNode(
                                    label = "원정",
                                    teamName = displayTeamLabel(game.awayTeam),
                                    logoCode = awayStyle.logoCode,
                                    accent = awayStyle.color,
                                )
                            )
                        }
                    )

                    addView(
                        TextView(this@GameDetailActivity).apply {
                            text = listOfNotNull(
                                if (game.time.isNotBlank()) game.time else null,
                                if (game.venue.isNotBlank()) game.venue else null,
                            ).joinToString(" · ").ifBlank { "시간 정보 없음" }
                            textSize = 13f
                            setTextColor(0xFFB9C1D0.toInt())
                            setPadding(0, 16, 0, 0)
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
                    )
                }
            )
        }
    }

    private fun compactTeamNode(
        label: String,
        teamName: String,
        logoCode: String,
        accent: Int,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = label
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(0xFFAAB2C3.toInt())
                }
            )

            val logoFrame = FrameLayout(this@GameDetailActivity).apply {
                layoutParams = LinearLayout.LayoutParams(96, 96).apply {
                    topMargin = 8
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(withAlpha(accent, 20))
                    setStroke(1, withAlpha(accent, 70))
                }
            }

            val logo = ImageView(this@GameDetailActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    gravity = Gravity.CENTER
                    leftMargin = 14
                    topMargin = 14
                    rightMargin = 14
                    bottomMargin = 14
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val fallback = TextView(this@GameDetailActivity).apply {
                text = teamName.take(2)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            logoFrame.addView(logo)
            logoFrame.addView(fallback)
            loadTeamLogo(logo, fallback, logoCode)

            addView(logoFrame)

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = teamName
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(0, 10, 0, 0)
                }
            )
        }
    }

    private fun buildSnapshotPanel(game: KboGame): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 4)

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = "경기 한눈에 보기"
                    textSize = 19f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(4, 0, 0, 12)
                }
            )

            addView(
                LinearLayout(this@GameDetailActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        miniInfoCard("구장", game.venue.ifBlank { "구장 정보 없음" }, 0xFF252A35.toInt())
                    )
                    addView(
                        miniInfoCard("시간", game.time.ifBlank { "정보 없음" }, 0xFF202530.toInt())
                    )
                }
            )
            addView(
                LinearLayout(this@GameDetailActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        miniInfoCard("상태", if (game.canceled) "취소" else game.status.ifBlank { "정보 없음" }, 0xFF1C2230.toInt())
                    )
                    addView(
                        miniInfoCard("날씨", game.weatherLabel() ?: "경기 당시 정보 없음", 0xFF23202D.toInt())
                    )
                }
            )
        }
    }

    private fun miniInfoCard(title: String, value: String, tint: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 10
                bottomMargin = 10
            }
            setPadding(16, 16, 16, 16)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(tint)
                setStroke(1, 0x223E4A66)
            }
            addView(
                TextView(this@GameDetailActivity).apply {
                    text = title
                    textSize = 12f
                    setTextColor(0xFF98A0B3.toInt())
                }
            )
            addView(
                TextView(this@GameDetailActivity).apply {
                    text = value
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(0, 8, 0, 0)
                }
            )
        }
    }

    private fun buildStarterPanel(game: KboGame, homeStyle: TeamVisual, awayStyle: TeamVisual): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 10, 18, 0)

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = "선발 매치업"
                    textSize = 19f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(4, 0, 0, 12)
                }
            )

            addView(
                LinearLayout(this@GameDetailActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, 0)

                    addView(
                        starterCard(
                            side = "홈",
                            teamName = displayTeamLabel(game.homeTeam),
                            pitcher = game.homePitcherName,
                            logoCode = homeStyle.logoCode,
                            accent = homeStyle.color,
                        )
                    )

                    addView(
                        TextView(this@GameDetailActivity).apply {
                            text = "VS"
                            textSize = 36f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(0x336C7483.toInt())
                            gravity = Gravity.CENTER
                            setPadding(10, 0, 10, 0)
                        }
                    )

                    addView(
                        starterCard(
                            side = "원정",
                            teamName = displayTeamLabel(game.awayTeam),
                            pitcher = game.awayPitcherName,
                            logoCode = awayStyle.logoCode,
                            accent = awayStyle.color,
                        )
                    )
                }
            )
        }
    }

    private fun buildLineupPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 0)
            addView(TextView(this@GameDetailActivity).apply {
                text = "라인업"
                textSize = 19f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(4, 0, 0, 12)
            })
            addView(TextView(this@GameDetailActivity).apply {
                text = "라인업을 불러오는 중..."
                textSize = 13f
                setTextColor(0xFF9EA7B8.toInt())
                setPadding(4, 0, 0, 8)
            })
        }
    }

    private fun renderLineupPanel(
        panel: LinearLayout,
        game: KboGame,
        lineups: KboRepository.GameLineups?,
    ) {
        if (isFinishing || isDestroyed) return
        while (panel.childCount > 1) panel.removeViewAt(1)
        val hasLineup = lineups != null && (lineups.home.isNotEmpty() || lineups.away.isNotEmpty())
        if (!hasLineup) {
            panel.addView(TextView(this).apply {
                text = "공식 라인업이 아직 발표되지 않았습니다."
                textSize = 13f
                setTextColor(0xFF9EA7B8.toInt())
                setPadding(4, 0, 4, 8)
            })
            return
        }
        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        columns.addView(buildLineupColumn("홈 · ${displayTeamLabel(game.homeTeam)}", lineups!!.home), LinearLayout.LayoutParams(0, -2, 1f))
        columns.addView(buildLineupColumn("원정 · ${displayTeamLabel(game.awayTeam)}", lineups.away), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 10 })
        panel.addView(columns)
    }

    private fun buildLineupColumn(title: String, entries: List<KboRepository.LineupEntry>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            background = GradientDrawable().apply {
                setColor(0xFF10131A.toInt())
                cornerRadius = 22f
                setStroke(1, 0x223E4A66)
            }
            addView(TextView(this@GameDetailActivity).apply {
                text = title
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFFDCE3F0.toInt())
                setPadding(0, 0, 0, 8)
            })
            entries.forEach { entry ->
                addView(TextView(this@GameDetailActivity).apply {
                    text = "${entry.order}. ${entry.playerName}  ${entry.position}"
                    textSize = 12f
                    setTextColor(0xFFB9C1D0.toInt())
                    setPadding(0, 4, 0, 4)
                })
            }
        }
    }

    private fun starterCard(
        side: String,
        teamName: String,
        pitcher: String?,
        logoCode: String,
        accent: Int,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 18, 16, 18)
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(0xFF10131A.toInt())
                setStroke(1, withAlpha(accent, 58))
            }

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = side
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(withAlpha(accent, 210))
                }
            )

            val logoFrame = FrameLayout(this@GameDetailActivity).apply {
                layoutParams = LinearLayout.LayoutParams(140, 140).apply {
                    topMargin = 12
                }
                background = GradientDrawable().apply {
                    cornerRadius = 36f
                    setColor(withAlpha(accent, 18))
                }
            }

            val logo = ImageView(this@GameDetailActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    gravity = Gravity.CENTER
                    leftMargin = 18
                    topMargin = 18
                    rightMargin = 18
                    bottomMargin = 18
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val fallback = TextView(this@GameDetailActivity).apply {
                text = teamName.take(2)
                textSize = 24f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            logoFrame.addView(logo)
            logoFrame.addView(fallback)
            loadTeamLogo(logo, fallback, logoCode)

            addView(logoFrame)

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = teamName
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(0, 12, 0, 0)
                }
            )

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = pitcher?.takeIf { it.isNotBlank() } ?: "선발 정보 없음"
                    textSize = 15f
                    setTextColor(0xFFBBC4D4.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, 6, 0, 0)
                }
            )
        }
    }

    private fun buildFooterNote(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 24)

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = "상세 화면은 오늘 경기 중심으로만 보여줍니다. 점수는 30초마다 반영됩니다."
                    textSize = 13f
                    setTextColor(0xFF9EA7B8.toInt())
                    setLineSpacing(2f, 1f)
                    setPadding(8, 6, 8, 0)
                }
            )
        }
    }

    private fun buildTeamHeroBlock(
        teamName: String,
        logoCode: String,
        pitcher: String?,
        accent: Int,
        isHome: Boolean,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(8, 0, 8, 0)

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = if (isHome) "홈" else "원정"
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(accent)
                    setPadding(10, 6, 10, 6)
                    background = pillDrawable(withAlpha(accent, 24), withAlpha(accent, 92))
                }
            )

            val logoFrame = FrameLayout(this@GameDetailActivity).apply {
                layoutParams = LinearLayout.LayoutParams(130, 130).apply {
                    topMargin = 12
                }
                background = surfaceDrawable(accent)
            }

            val logo = ImageView(this@GameDetailActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    gravity = Gravity.CENTER
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            val fallback = TextView(this@GameDetailActivity).apply {
                text = teamName.take(2)
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }

            logoFrame.addView(logo)
            logoFrame.addView(fallback)
            loadTeamLogo(logo, fallback, logoCode)

            addView(logoFrame)

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = teamName
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(0, 14, 0, 0)
                }
            )

            addView(
                TextView(this@GameDetailActivity).apply {
                    text = pitcher?.takeIf { it.isNotBlank() } ?: "선발 정보 없음"
                    textSize = 15f
                    setTextColor(0xFFC9CFDB.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, 8, 0, 0)
                }
            )
        }
    }

    private fun buildTabLabel(label: String, selected: Boolean): TextView {
        return TextView(this).apply {
            text = label
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (selected) Color.WHITE else 0xFF9499A7.toInt())
            setPadding(18, 8, 18, 12)
            if (selected) {
                background = pillDrawable(0x1AFFFFFF, 0x22FFFFFF)
            }
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(24, 28, 24, 14)
        }
    }

    private fun buildInfoGrid(items: List<Pair<String, String>>): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 20, 0)
        }

        val rows = items.chunked(2)
        rows.forEach { row ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.forEach { (label, value) ->
                line.addView(
                    buildInfoCard(
                        label = label,
                        value = value,
                        accent = Color.WHITE,
                    )
                )
            }
            container.addView(line)
        }

        return container
    }

    private fun buildInfoCard(label: String, value: String, accent: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
                bottomMargin = 12
            }
            setPadding(16, 16, 16, 16)
            background = surfaceDrawable(accent)
            addView(
                TextView(this@GameDetailActivity).apply {
                    text = label
                    textSize = 12f
                    setTextColor(0xFF98A0B3.toInt())
                }
            )
            addView(
                TextView(this@GameDetailActivity).apply {
                    text = value
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(0, 6, 0, 0)
                }
            )
        }
    }

    private fun buildPitcherCompare(
        homeTeam: String,
        awayTeam: String,
        homeLogo: String,
        awayLogo: String,
        homePitcher: String?,
        awayPitcher: String?,
        homeAccent: Int,
        awayAccent: Int,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 0, 20, 0)
            addView(
                buildPitcherCard(
                    sideLabel = "홈",
                    teamName = homeTeam,
                    pitcher = homePitcher,
                    logoCode = homeLogo,
                    accent = homeAccent,
                )
            )
            addView(
                LinearLayout(this@GameDetailActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.2f)
                    addView(
                        TextView(this@GameDetailActivity).apply {
                            text = "VS"
                            textSize = 34f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(0x336C7483.toInt())
                            gravity = Gravity.CENTER
                            setPadding(0, 140, 0, 0)
                        }
                    )
                }
            )
            addView(
                buildPitcherCard(
                    sideLabel = "원정",
                    teamName = awayTeam,
                    pitcher = awayPitcher,
                    logoCode = awayLogo,
                    accent = awayAccent,
                )
            )
        }
    }

    private fun buildPitcherCard(
        sideLabel: String,
        teamName: String,
        pitcher: String?,
        logoCode: String,
        accent: Int,
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                bottomMargin = 16
                marginEnd = 12
            }
            setPadding(16, 16, 16, 18)
            background = surfaceDrawable(accent)
        }

        card.addView(
            TextView(this).apply {
                text = sideLabel
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF9AA3B6.toInt())
                gravity = Gravity.CENTER
            }
        )

        val logoFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(150, 150).apply {
                topMargin = 18
            }
        }
        val logo = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val fallback = TextView(this).apply {
            text = teamName.take(2)
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        logoFrame.addView(logo)
        logoFrame.addView(fallback)
        loadTeamLogo(logo, fallback, logoCode)

        card.addView(logoFrame)
        card.addView(
            TextView(this).apply {
                text = teamName
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 0)
            }
        )
        card.addView(
            TextView(this).apply {
                text = pitcher?.takeIf { it.isNotBlank() } ?: "선발 정보 없음"
                textSize = 16f
                setTextColor(0xFFCBD1DE.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 0)
            }
        )
        return card
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

    private fun heroBackground(homeColor: Int, awayColor: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                0xFF050505.toInt(),
                withAlpha(homeColor, 72),
                0xFF0B0B0F.toInt(),
                withAlpha(awayColor, 62),
            )
        ).apply {
            cornerRadius = 36f
        }
    }

    private fun surfaceDrawable(accent: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                0xFF232325.toInt(),
                0xFF17181B.toInt(),
            )
        ).apply {
            cornerRadius = 28f
            setStroke(1, withAlpha(accent, 52))
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

    private data class TeamVisual(
        val color: Int,
        val tint: Int,
        val logoCode: String,
    )

    companion object {
        const val EXTRA_AWAY_TEAM = "away_team"
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_GAME_DATE = "game_date"
        const val EXTRA_HOME_TEAM = "home_team"
        const val EXTRA_AWAY_SCORE = "away_score"
        const val EXTRA_HOME_SCORE = "home_score"
        const val EXTRA_TIME = "time"
        const val EXTRA_VENUE = "venue"
        const val EXTRA_CANCELED = "canceled"
        const val EXTRA_STATUS = "status"
        const val EXTRA_AWAY_PITCHER = "away_pitcher"
        const val EXTRA_HOME_PITCHER = "home_pitcher"
        const val EXTRA_WEATHER = "weather"
    }
}

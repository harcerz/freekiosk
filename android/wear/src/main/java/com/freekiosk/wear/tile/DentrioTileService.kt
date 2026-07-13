package com.freekiosk.wear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.freekiosk.wear.R
import com.freekiosk.wear.data.WatchStateHolder
import com.freekiosk.wear.model.WatchSummary
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking

private const val RESOURCES_VERSION = "1"
private const val ACCENT_BLUE = 0xFF41BDF5.toInt()
private const val ALERT_RED = 0xFFEF5350.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val GRAY = 0xFF9E9E9E.toInt()

/**
 * Glanceable tile — one swipe from the watchface: room name, the current /
 * next visit and a red waiting alert. Tapping anywhere opens the app.
 * Refreshed by the system (freshness interval) and pushed from
 * WatchDataListenerService whenever a new summary arrives.
 */
class DentrioTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        // Seed from the persisted DataItem when the process is fresh.
        val summary = WatchStateHolder.summary.value ?: runBlocking {
            WatchStateHolder.loadInitial(applicationContext)
            WatchStateHolder.summary.value
        }

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(layout(this, summary)),
            )
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build(),
        )
}

private fun openAppClickable(): ModifiersBuilders.Clickable =
    ModifiersBuilders.Clickable.Builder()
        .setId("open")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName("com.freekiosk")
                        .setClassName("com.freekiosk.wear.MainActivity")
                        .build(),
                )
                .build(),
        )
        .build()

private fun line(
    context: Context,
    text: String,
    typography: Int,
    color: Int,
): LayoutElementBuilders.LayoutElement =
    Text.Builder(context, text)
        .setTypography(typography)
        .setColor(argb(color))
        .setMaxLines(2)
        .build()

/** #rrggbb from the summary → color int; null on garbage. */
private fun parseAccentColor(hex: String?): Int? = hex?.let {
    try {
        android.graphics.Color.parseColor(it)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun layout(
    context: Context,
    summary: WatchSummary?,
): LayoutElementBuilders.LayoutElement {
    val column = LayoutElementBuilders.Column.Builder()
        .setWidth(expand())

    val waiting = summary?.nextAppointment?.takeIf { it.isWaiting }
    val current = summary?.currentVisit
    val next = summary?.nextAppointment

    column.addContent(
        line(
            context,
            summary?.roomName ?: context.getString(R.string.app_name),
            Typography.TYPOGRAPHY_CAPTION1,
            ACCENT_BLUE,
        ),
    )

    when {
        summary == null -> column.addContent(
            line(
                context,
                context.getString(R.string.tile_no_data),
                Typography.TYPOGRAPHY_BODY2,
                GRAY,
            ),
        )

        // The current visit stays on top even when someone already waits —
        // an early arrival must not hide the ongoing visit.
        current != null -> {
            column.addContent(
                line(
                    context,
                    context.getString(R.string.now_label) + " " + current.patientName,
                    Typography.TYPOGRAPHY_TITLE3,
                    WHITE,
                ),
            )
            if (waiting != null) {
                val minutes = waiting.minutesWaiting
                column.addContent(
                    line(
                        context,
                        if (minutes != null) {
                            context.getString(
                                R.string.waiting_inline_minutes, waiting.patientName, minutes,
                            )
                        } else {
                            context.getString(R.string.waiting_inline, waiting.patientName)
                        },
                        Typography.TYPOGRAPHY_CAPTION1,
                        ALERT_RED,
                    ),
                )
            } else if (current.minutesOverrun > 0) {
                column.addContent(
                    line(
                        context,
                        context.getString(R.string.overrun_format, current.minutesOverrun),
                        Typography.TYPOGRAPHY_CAPTION1,
                        ALERT_RED,
                    ),
                )
            } else if (next != null) {
                column.addContent(
                    line(
                        context,
                        "→ ${next.startTime} ${next.patientName}",
                        Typography.TYPOGRAPHY_CAPTION1,
                        GRAY,
                    ),
                )
            }
        }

        waiting != null -> {
            column.addContent(
                line(
                    context,
                    waiting.patientName,
                    Typography.TYPOGRAPHY_TITLE3,
                    WHITE,
                ),
            )
            val minutes = waiting.minutesWaiting
            column.addContent(
                line(
                    context,
                    if (minutes != null) {
                        context.getString(R.string.waiting_minutes, minutes)
                    } else {
                        context.getString(R.string.waiting_badge_tile)
                    },
                    Typography.TYPOGRAPHY_TITLE3,
                    ALERT_RED,
                ),
            )
        }

        next != null -> {
            // Label carries the visit-type color when the summary provides one.
            column.addContent(
                line(
                    context,
                    context.getString(R.string.next_label, next.startTime),
                    Typography.TYPOGRAPHY_CAPTION1,
                    parseAccentColor(next.categoryColor) ?: GRAY,
                ),
            )
            column.addContent(
                line(context, next.patientName, Typography.TYPOGRAPHY_TITLE3, WHITE),
            )
        }

        else -> column.addContent(
            line(
                context,
                context.getString(R.string.no_visits),
                Typography.TYPOGRAPHY_BODY2,
                GRAY,
            ),
        )
    }

    return LayoutElementBuilders.Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(openAppClickable())
                .build(),
        )
        .addContent(column.build())
        .build()
}

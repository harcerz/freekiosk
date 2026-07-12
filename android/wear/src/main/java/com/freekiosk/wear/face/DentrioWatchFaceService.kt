package com.freekiosk.wear.face

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.TapType
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.style.CurrentUserStyleRepository
import com.freekiosk.wear.MainActivity
import com.freekiosk.wear.R
import com.freekiosk.wear.data.WatchStateHolder
import com.freekiosk.wear.model.WatchSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * DenTRIO watchface: the room dashboard lives directly on the clock. Big
 * time, date, room name and the current/next visit; a waiting patient turns
 * the info block red. Any tap opens the app. Ambient mode drops to a dim
 * clock (AMOLED-friendly), interactive mode refreshes once a minute and
 * immediately when a new summary arrives over the Data Layer.
 */
class DentrioWatchFaceService : WatchFaceService() {

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        val renderer = DentrioRenderer(
            service = this,
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            currentUserStyleRepository = currentUserStyleRepository,
        )
        return WatchFace(WatchFaceType.DIGITAL, renderer)
            .setTapListener(object : WatchFace.TapListener {
                override fun onTapEvent(
                    tapType: Int,
                    tapEvent: TapEvent,
                    complicationSlot: ComplicationSlot?,
                ) {
                    if (tapType == TapType.UP) {
                        startActivity(
                            Intent(this@DentrioWatchFaceService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            })
    }
}

private class DentrioRenderer(
    private val service: WatchFaceService,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    currentUserStyleRepository: CurrentUserStyleRepository,
) : Renderer.CanvasRenderer2<DentrioRenderer.DentrioSharedAssets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    CanvasType.HARDWARE,
    interactiveDrawModeUpdateDelayMillis = 60_000L,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false,
) {

    class DentrioSharedAssets : SharedAssets {
        override fun onDestroy() {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormat = DateTimeFormatter.ofPattern("EEE, d MMM", Locale("pl"))

    private val timePaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD,
        )
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    init {
        // Seed after process start and repaint the face on every summary push.
        scope.launch {
            WatchStateHolder.loadInitial(service.applicationContext)
            WatchStateHolder.summary.collect { invalidate() }
        }
    }

    override suspend fun createSharedAssets(): DentrioSharedAssets = DentrioSharedAssets()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun renderHighlightLayer(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: DentrioSharedAssets,
    ) {
        canvas.drawColor(Color.TRANSPARENT)
    }

    override fun render(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: DentrioSharedAssets,
    ) {
        val ambient = renderParameters.drawMode == androidx.wear.watchface.DrawMode.AMBIENT
        canvas.drawColor(Color.BLACK)

        val cx = bounds.exactCenterX()
        val w = bounds.width().toFloat()
        val summary = WatchStateHolder.summary.value

        // Clock — always the hero.
        timePaint.textSize = w * 0.24f
        timePaint.color = if (ambient) Color.LTGRAY else Color.WHITE
        canvas.drawText(timeFormat.format(zonedDateTime), cx, bounds.height() * 0.32f, timePaint)

        textPaint.textSize = w * 0.055f
        textPaint.color = if (ambient) Color.DKGRAY else Color.GRAY
        canvas.drawText(dateFormat.format(zonedDateTime), cx, bounds.height() * 0.40f, textPaint)

        // Room name.
        textPaint.textSize = w * 0.055f
        textPaint.color = if (ambient) Color.DKGRAY else 0xFF41BDF5.toInt()
        canvas.drawText(
            summary?.roomName ?: service.getString(R.string.app_name),
            cx,
            bounds.height() * 0.50f,
            textPaint,
        )

        // In ambient we stop at the clock + room (battery, burn-in).
        if (ambient) return

        val waiting = summary?.nextAppointment?.takeIf { it.isWaiting }
        val current = summary?.currentVisit
        val next = summary?.nextAppointment

        val line1Y = bounds.height() * 0.62f
        val line2Y = bounds.height() * 0.70f

        when {
            summary == null -> {
                textPaint.textSize = w * 0.05f
                textPaint.color = Color.GRAY
                canvas.drawText(
                    service.getString(R.string.tile_no_data), cx, line1Y, textPaint,
                )
            }

            waiting != null -> {
                textPaint.textSize = w * 0.065f
                textPaint.color = 0xFFEF5350.toInt()
                canvas.drawText(waiting.patientName, cx, line1Y, textPaint)
                val minutes = waiting.minutesWaiting
                canvas.drawText(
                    if (minutes != null) {
                        service.getString(R.string.waiting_minutes, minutes)
                    } else {
                        service.getString(R.string.waiting_badge_tile)
                    },
                    cx,
                    line2Y,
                    textPaint,
                )
            }

            current != null -> {
                textPaint.textSize = w * 0.06f
                textPaint.color = Color.WHITE
                canvas.drawText(current.patientName, cx, line1Y, textPaint)
                textPaint.textSize = w * 0.05f
                if (current.minutesOverrun > 0) {
                    textPaint.color = 0xFFEF5350.toInt()
                    canvas.drawText(
                        service.getString(R.string.overrun_format, current.minutesOverrun),
                        cx,
                        line2Y,
                        textPaint,
                    )
                } else {
                    textPaint.color = Color.GRAY
                    canvas.drawText(
                        "${current.startTime}–${current.endTime}", cx, line2Y, textPaint,
                    )
                }
            }

            next != null -> {
                textPaint.textSize = w * 0.05f
                textPaint.color = Color.GRAY
                canvas.drawText(
                    service.getString(R.string.next_label, next.startTime),
                    cx,
                    line1Y,
                    textPaint,
                )
                textPaint.textSize = w * 0.06f
                textPaint.color = Color.WHITE
                canvas.drawText(next.patientName, cx, line2Y, textPaint)
            }

            else -> {
                textPaint.textSize = w * 0.05f
                textPaint.color = Color.GRAY
                canvas.drawText(service.getString(R.string.no_visits), cx, line1Y, textPaint)
            }
        }
    }
}

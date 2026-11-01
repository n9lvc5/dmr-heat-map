package com.dmrmap.survey

import kotlinx.coroutines.flow.MutableStateFlow

data class SurveyPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    /** Strongest channel power seen since the previous GPS fix. */
    val peakDbfs: Double,
    val noiseDbfs: Double,
    val active: Boolean,
) {
    val snrDb get() = peakDbfs - noiseDbfs
}

data class LiveReading(
    val channelDbfs: Double,
    val noiseDbfs: Double,
    val clipFraction: Double,
    val active: Boolean,
) {
    val snrDb get() = channelDbfs - noiseDbfs
}

/** Process-wide state shared between [SurveyService] and the UI. */
object SurveyState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("Idle")
    val live = MutableStateFlow<LiveReading?>(null)
    val pointCount = MutableStateFlow(0)
    val activeCount = MutableStateFlow(0)
    val grid = HeatGrid()
    /** Bumped whenever [grid] changes so the map knows to redraw. */
    val gridVersion = MutableStateFlow(0)
}

package com.maxrave.domain.data.player

import kotlinx.coroutines.flow.MutableStateFlow

/** Loudness of the three bands the gear visualizer reacts to, each from 0f (silent) to 1f (loud). */
class AudioBands(
    val bass: Float,
    val mid: Float,
    val treble: Float,
)

/**
 * Hand-off between the audio thread and the UI. The player's audio sink writes [bands] only while
 * something is showing the visualizer ([enabled]); nothing is analysed otherwise.
 */
object AudioBandsBus {
    val bands = MutableStateFlow(AudioBands(0f, 0f, 0f))
    val enabled = MutableStateFlow(false)
}

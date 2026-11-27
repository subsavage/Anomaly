package com.anomaly.game.audio

import android.media.AudioManager
import android.media.ToneGenerator

data class AudioSettings(
    val musicEnabled: Boolean = true,
    val soundEffectsEnabled: Boolean = true
)

enum class AudioCue { CORRECT, INCORRECT }

/** Small integration seam for original tracks and effects, with no third-party audio dependency. */
interface GameAudioController {
    fun updateSettings(settings: AudioSettings)
    fun playEffect(cue: AudioCue)
    fun release()
}

/** Preserves the existing native feedback tones as replaceable sound effects. */
class ToneGameAudioController : GameAudioController {
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
    private var settings = AudioSettings()

    override fun updateSettings(settings: AudioSettings) { this.settings = settings }

    override fun playEffect(cue: AudioCue) {
        if (!settings.soundEffectsEnabled) return
        val toneType = when (cue) {
            AudioCue.CORRECT -> ToneGenerator.TONE_PROP_BEEP2
            AudioCue.INCORRECT -> ToneGenerator.TONE_PROP_NACK
        }
        tone.startTone(toneType, 65)
    }

    override fun release() { tone.release() }
}

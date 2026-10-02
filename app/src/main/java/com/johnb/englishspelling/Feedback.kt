package com.johnb.englishspelling

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech

/**
 * Right/wrong feedback: a short sound effect plus a spoken phrase.
 *
 * The sound comes from `assets/sfx/correct.(wav|mp3)` / `wrong.(wav|mp3)`
 * (bundled), and falls back to a plain tone if those are missing.
 */
object Feedback {

    private var player: MediaPlayer? = null

    fun correct(context: Context, tts: TextToSpeech?, ttsReady: Boolean) {
        cue(context, "correct")
        say(tts, ttsReady, "Great job!")
    }

    fun wrong(context: Context, tts: TextToSpeech?, ttsReady: Boolean) {
        cue(context, "wrong")
        say(tts, ttsReady, "Try again")
    }

    private fun say(tts: TextToSpeech?, ready: Boolean, phrase: String) {
        if (tts == null || !ready) return
        tts.setSpeechRate(1.0f)
        // Let the sound effect play first, then the phrase.
        tts.playSilentUtterance(380, TextToSpeech.QUEUE_FLUSH, "fbGap")
        tts.speak(phrase, TextToSpeech.QUEUE_ADD, null, "fb")
    }

    private fun cue(context: Context, name: String) {
        val asset = listOf("sfx/$name.wav", "sfx/$name.mp3").firstOrNull { p ->
            try {
                context.assets.open(p).close(); true
            } catch (e: Exception) {
                false
            }
        }
        if (asset != null) {
            try {
                player?.release()
                player = null
                val afd = context.assets.openFd(asset)
                val mp = MediaPlayer()
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                mp.setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                }
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                // fall through to a tone
            }
        }
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            val tone =
                if (name == "correct") ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK
            tg.startTone(tone, 300)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 600)
        } catch (e: Exception) {
            // no audio available — ignore
        }
    }

    fun release() {
        player?.release()
        player = null
    }
}

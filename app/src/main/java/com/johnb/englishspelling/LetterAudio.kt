package com.johnb.englishspelling

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech

/**
 * Plays the sound of a single letter as it is entered.
 *
 * If a recording exists at `assets/lettersounds/<key>.mp3` it is played (this is
 * how you get real phonics — drop clips into that folder). Otherwise the English
 * text-to-speech voice says the letter's name.
 *
 * Asset key mapping:
 *   a..z      -> a.mp3 .. z.mp3
 *   -  '  ' ' -> hyphen / apostrophe / space .mp3
 */
object LetterAudio {

    private var player: MediaPlayer? = null

    fun keyFor(ch: Char): String = when (ch.lowercaseChar()) {
        '-' -> "hyphen"; '\'' -> "apostrophe"; ' ' -> "space"
        else -> ch.lowercaseChar().toString()
    }

    /** What the voice says for one character. A lone lowercase "a" is read as the
     *  word "uh"; the capital is read as the letter name ("ay"), so letters are
     *  always spoken as capitals. */
    fun nameFor(ch: Char): String = when (ch) {
        ' ' -> "space"
        '-' -> "hyphen"
        '\'' -> "apostrophe"
        else -> ch.uppercaseChar().toString()
    }

    fun hasClip(context: Context, ch: Char): Boolean = try {
        context.assets.open("lettersounds/${keyFor(ch)}.mp3").close(); true
    } catch (e: Exception) {
        false
    }

    fun play(context: Context, tts: TextToSpeech?, ttsReady: Boolean, ch: Char) {
        if (hasClip(context, ch)) {
            try {
                player?.release()
                player = null
                val afd = context.assets.openFd("lettersounds/${keyFor(ch)}.mp3")
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
                // fall through to the voice
            }
        }
        if (tts != null && ttsReady) {
            tts.speak(nameFor(ch), TextToSpeech.QUEUE_FLUSH, null, "letter")
        }
    }

    fun release() {
        player?.release()
        player = null
    }
}

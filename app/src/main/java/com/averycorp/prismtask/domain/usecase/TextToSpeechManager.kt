package com.averycorp.prismtask.domain.usecase

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around Android [TextToSpeech] that defers engine
 * initialisation until the first [speak] call and exposes a simple
 * fire-and-forget API for voice-feedback responses.
 */
@Singleton
class TextToSpeechManager
@Inject
constructor(@ApplicationContext private val context: Context) {
    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private var pending: String? = null

    /** Speak [text] using the user's default locale. No-op if empty. */
    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (engine != null && ready.get()) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            return
        }
        pending = text
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    ready.set(true)
                    pending?.let { queued ->
                        tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
                    }
                    pending = null
                }
            }
        }
    }

    /** Stop any in-flight utterance. */
    fun stop() {
        tts?.stop()
    }

    /** Release the engine. Call when the app is shutting down. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
        pending = null
    }

    private companion object {
        const val UTTERANCE_ID = "prismtask-voice-feedback"
    }
}

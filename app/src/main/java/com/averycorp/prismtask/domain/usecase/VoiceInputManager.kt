package com.averycorp.prismtask.domain.usecase

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around Android's [SpeechRecognizer] that exposes reactive
 * listening state and live partial-transcription for voice-input UI.
 *
 * The manager owns a single SpeechRecognizer instance which is lazily
 * created, recycled between listens, and released on [release]. It reports:
 *
 *  * [isListening]  — true while the mic is active
 *  * [partialText]  — live text as the user speaks (via PARTIAL_RESULTS)
 *  * [rmsLevel]     — amplitude 0..1 suitable for driving pulse animations
 */
@Singleton
class VoiceInputManager
@Inject
constructor(@ApplicationContext private val context: Context) {
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /** Returns true if on-device or Google-backed speech recognition is available. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Returns true if the app currently holds RECORD_AUDIO permission. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Begin a recognition session. [onResult] is called once with the final
     * transcript; [onError] is called with a user-facing error string if the
     * session fails (no internet, no speech, permission, etc.).
     *
     * Partial results flow through [partialText] during the session.
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isAvailable()) {
            onError("Voice input not available on this device")
            return
        }
        if (!hasPermission()) {
            onError("Microphone permission needed for voice input")
            return
        }
        if (_isListening.value) return

        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
                _partialText.value = ""
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                // Normalize: SpeechRecognizer emits roughly -2..10 dB
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _rmsLevel.value = normalized
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _rmsLevel.value = 0f
            }

            override fun onError(error: Int) {
                _isListening.value = false
                _rmsLevel.value = 0f
                val message = when (error) {
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Voice input requires internet"
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No speech detected — try again"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Microphone permission needed for voice input"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "Voice input is busy — try again"
                    SpeechRecognizer.ERROR_CLIENT -> "Voice input error"
                    SpeechRecognizer.ERROR_SERVER -> "Speech service unavailable"
                    else -> "Voice input error"
                }
                onError(message)
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                _rmsLevel.value = 0f
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    _partialText.value = text
                    onResult(text)
                } else {
                    onError("No speech detected — try again")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    _partialText.value = text
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1500L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                1000L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1500L
            )
        }

        try {
            sr.startListening(intent)
        } catch (e: SecurityException) {
            _isListening.value = false
            onError("Microphone permission needed for voice input")
        } catch (e: Exception) {
            _isListening.value = false
            onError("Voice input error")
        }
    }

    /** Stops the active recognition session; the final transcript (if any)
     *  will still be delivered via the pending [RecognitionListener.onResults]. */
    fun stopListening() {
        recognizer?.stopListening()
        _isListening.value = false
        _rmsLevel.value = 0f
    }

    /** Aborts and releases the underlying recognizer. */
    fun release() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _isListening.value = false
        _partialText.value = ""
        _rmsLevel.value = 0f
    }

    /** Clear the transcription buffer (e.g. after consuming a result). */
    fun clearPartialText() {
        _partialText.value = ""
    }
}

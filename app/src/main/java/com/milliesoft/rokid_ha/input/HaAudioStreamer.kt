package com.milliesoft.rokid_ha.input

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Streams 16kHz 16-bit mono PCM audio from the Rokid Glasses microphone directly
 * to Home Assistant's WebSocket `assist_pipeline/run` STT handler, and plays back
 * Home Assistant's TTS response audio over the glasses speakers.
 */
class HaAudioStreamer(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    ttsReady = true
                }
            }
        } catch (_: Exception) {
        }
    }

    val isCurrentlyRecording: Boolean
        get() = isRecording

    @SuppressLint("MissingPermission")
    fun startStreamingToHomeAssistant(
        onAudioChunk: (ByteArray, Int) -> Unit,
        onSpeechFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        stopRecordingOnly()

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBufSize <= 0) {
            onError("MIC INIT ERROR")
            return
        }

        val bufferSize = (minBufSize * 2).coerceAtLeast(4096)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                encoding,
                bufferSize
            )
        } catch (e: Exception) {
            onError("MIC ACCESS ERROR: ${e.localizedMessage}")
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("MIC NOT INITIALIZED")
            return
        }

        audioRecord = recorder
        isRecording = true

        thread(name = "RokidMicToHomeAssistant", isDaemon = true) {
            try {
                recorder.startRecording()
                val chunkBytes = ByteArray(2048) // 64ms per chunk at 16kHz 16-bit mono
                val startMs = System.currentTimeMillis()
                var heardSpeech = false
                var lastVoiceMs = startMs

                while (isRecording) {
                    val read = recorder.read(chunkBytes, 0, chunkBytes.size)
                    if (read > 0) {
                        onAudioChunk(chunkBytes, read)

                        val rms = computeRms16Bit(chunkBytes, read)
                        val now = System.currentTimeMillis()
                        if (rms > 380.0) {
                            heardSpeech = true
                            lastVoiceMs = now
                        }

                        // Auto-stop after 1.35s of silence once speech has started, or after 7s max
                        val elapsed = now - startMs
                        if ((heardSpeech && now - lastVoiceMs > 1350L && elapsed > 1200L) || elapsed > 7000L) {
                            break
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                val wasActive = isRecording
                stopRecordingOnly()
                if (wasActive) {
                    onSpeechFinished()
                }
            }
        }
    }

    fun stopRecordingAndFinish(onSpeechFinished: () -> Unit) {
        if (isRecording) {
            stopRecordingOnly()
            onSpeechFinished()
        }
    }

    private fun stopRecordingOnly() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    fun playAudioBytes(audioBytes: ByteArray, ext: String = "mp3", fallbackText: String? = null) {
        if (audioBytes.isEmpty()) {
            speakFallbackText(fallbackText)
            return
        }
        try {
            val safeExt = ext.lowercase().ifBlank { "mp3" }
            val tempFile = File(appContext.cacheDir, "rokid_ha_tts_reply.$safeExt")
            tempFile.writeBytes(audioBytes)

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setVolume(1.0f, 1.0f)
                FileInputStream(tempFile).use { fis ->
                    setDataSource(fis.fd)
                }
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ ->
                    speakFallbackText(fallbackText)
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            speakFallbackText(fallbackText)
        }
    }

    fun playTtsUrlOrSpeak(fullAudioUrl: String?, fallbackText: String?) {
        if (!fullAudioUrl.isNullOrBlank()) {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setVolume(1.0f, 1.0f)
                    setDataSource(fullAudioUrl)
                    setOnPreparedListener { it.start() }
                    setOnErrorListener { _, _, _ ->
                        speakFallbackText(fallbackText)
                        true
                    }
                    prepareAsync()
                }
                return
            } catch (_: Exception) {
            }
        }
        speakFallbackText(fallbackText)
    }

    private fun speakFallbackText(text: String?) {
        if (text.isNullOrBlank() || !ttsReady) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ha_reply")
        } catch (_: Exception) {
        }
    }

    fun release() {
        stopRecordingOnly()
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }

    private fun computeRms16Bit(buffer: ByteArray, length: Int): Double {
        val sampleCount = length / 2
        if (sampleCount == 0) return 0.0
        var sumSquares = 0.0
        var i = 0
        while (i + 1 < length) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val absSample = abs(sample.toDouble())
            sumSquares += absSample * absSample
            i += 2
        }
        return sqrt(sumSquares / sampleCount)
    }
}

package com.industrial.horn.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Low-latency AudioTrack backend.
 *
 * AudioTrack is opened in streaming mode with `PERFORMANCE_MODE_LOW_LATENCY` and 48 kHz stereo
 * Float32 PCM (contract 00 §1 C1/C8). Rendering happens on one dedicated
 * `THREAD_PRIORITY_URGENT_AUDIO` thread; the main thread only flips a request flag. Press ->
 * audible output therefore does not involve Compose, coroutines, or allocation on the audio path.
 *
 * The writer keeps a small 5 ms block size so stop/panic can silence the track within one block.
 */
class SynthAudioTrackEngine(
    private val sampleRate: Int = SAMPLE_RATE,
    private val framesPerBlock: Int = FRAMES_PER_BLOCK,
) : HornAudioEngine {
    init {
        require(sampleRate >= 44_100) { "Low-latency output requires at least 44.1 kHz" }
        require(framesPerBlock > 0) { "framesPerBlock must be positive" }
    }

    private val lock = java.lang.Object()
    private val _events =
        MutableSharedFlow<HornAudioEvent>(
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val events: Flow<HornAudioEvent> = _events.asSharedFlow()

    private val renderer = HornRenderer(sampleRate = sampleRate, framesPerBlock = framesPerBlock)
    private val telemetry = AudioTelemetryBuffer(ENGINE_NAME)

    // Preallocated telemetry scratch (PCM16 exists only in this telemetry mirror, 00 §1 C8).
    private val telemetryLeft = FloatArray(framesPerBlock)
    private val telemetryRight = FloatArray(framesPerBlock)
    private val pcmLeft = ShortArray(framesPerBlock)
    private val pcmRight = ShortArray(framesPerBlock)

    private var audioTrack: AudioTrack? = null
    private var writerThread: Thread? = null
    private var playRequested = false
    private var released = false
    private var panicGeneration = 0L

    override fun prepare() {
        synchronized(lock) {
            if (released || audioTrack != null) return
        }
        try {
            val track = createTrack()
            synchronized(lock) {
                if (released || audioTrack != null) {
                    track.release()
                } else {
                    audioTrack = track
                }
            }
        } catch (_: Exception) {
            // Warm-up is best effort. start() retries and reports a real failure through events.
        }
    }

    override fun selectHorn(type: HornType) {
        renderer.requestType(type)
    }

    override fun setExpression(expression: Float) {
        renderer.setExpression(expression)
    }

    override fun setAcousticSpace(space: AcousticSpace) {
        renderer.setAcousticSpace(space)
    }

    override fun setProtection(enabled: Boolean) {
        renderer.setProtection(enabled)
    }

    override fun setCustomProfile(profile: CustomHornProfile) {
        renderer.setCustomProfile(profile)
    }

    override fun setOutputMode(mode: OutputMode) {
        renderer.setOutputMode(mode)
    }

    override fun telemetrySnapshot(): AudioTelemetry = telemetry.snapshot()

    override fun readWaveform(destination: FloatArray): Int = telemetry.readWaveform(destination)

    override fun readWaveformStereo(
        left: FloatArray,
        right: FloatArray,
    ): Int = telemetry.readWaveformStereo(left, right)

    override fun start(): Boolean {
        synchronized(lock) {
            if (released) return false
            playRequested = true
            if (writerThread?.isAlive != true) {
                val thread =
                    Thread(::runWriter, "Horn-AudioTrack").apply {
                        isDaemon = true
                        priority = Thread.MAX_PRIORITY
                    }
                writerThread = thread
                thread.start()
            }
            lock.notifyAll()
        }
        return true
    }

    override fun stop() {
        synchronized(lock) {
            if (!playRequested) return
            playRequested = false
            lock.notifyAll()
        }
    }

    override fun panicStop() {
        val track: AudioTrack?
        synchronized(lock) {
            if (released) return
            playRequested = false
            panicGeneration++
            track = audioTrack
            lock.notifyAll()
        }
        if (track != null) {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
            }
            runCatching { track.flush() }
        }
    }

    override fun release() {
        val thread: Thread?
        val track: AudioTrack?
        synchronized(lock) {
            if (released) return
            released = true
            playRequested = false
            panicGeneration++
            thread = writerThread
            track = audioTrack
            lock.notifyAll()
        }

        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }

        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(RELEASE_JOIN_TIMEOUT_MS) }
            if (thread.isAlive) {
                synchronized(lock) { audioTrack = null }
                track?.let { runCatching { it.release() } }
            }
        } else if (thread == null) {
            synchronized(lock) { audioTrack = null }
            track?.let { runCatching { it.release() } }
        }
    }

    private fun createTrack(): AudioTrack {
        val minBufferSize =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
        check(minBufferSize > 0) { "AudioTrack min buffer unavailable: $minBufferSize" }

        val bufferSizeBytes = max(minBufferSize, framesPerBlock * BYTES_PER_FRAME * BUFFER_BLOCKS)
        val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        val format =
            AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSizeBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("AudioTrack failed to initialize")
        }
        return track
    }

    private fun runWriter() {
        // Best effort: the priority is an optimisation, not a correctness requirement.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        var localTrack: AudioTrack? = null
        var activeGeneration = -1L
        try {
            while (true) {
                val shouldPlay =
                    synchronized(lock) {
                        while (!playRequested && !released) lock.wait()
                        !released
                    }
                if (!shouldPlay) break

                if (localTrack == null) {
                    localTrack = obtainTrack()
                }
                val track = localTrack ?: break

                activeGeneration = synchronized(lock) { panicGeneration }
                synchronized(lock) {
                    if (!playRequested || released || panicGeneration != activeGeneration) {
                        renderer.panic()
                        continue
                    }
                    renderer.start()
                    track.play()
                }

                while (true) {
                    val requested: Boolean
                    val generation: Long
                    synchronized(lock) {
                        requested = playRequested
                        generation = panicGeneration
                    }
                    if (generation != activeGeneration) break

                    if (requested && !renderer.isPlaying) {
                        renderer.start()
                    } else if (!requested && renderer.isPlaying) {
                        renderer.stop()
                    }

                    if (renderer.isSilent && !requested) break

                    val renderStarted = System.nanoTime()
                    val frames = renderer.renderBlock()
                    val renderMicros =
                        ((System.nanoTime() - renderStarted) / 1_000L).toInt().coerceAtLeast(0)
                    if (frames == 0) {
                        if (renderer.isSilent && !requested) break
                        continue
                    }

                    val written =
                        track.write(
                            renderer.outputBuffer,
                            0,
                            frames * 2,
                            AudioTrack.WRITE_BLOCKING,
                        )
                    if (written < 0) {
                        if (synchronized(lock) { panicGeneration == activeGeneration }) {
                            error("AudioTrack write failed: $written")
                        }
                        break
                    }

                    val latencyMs =
                        track.bufferSizeInFrames * 1_000f / sampleRate +
                            frames * 1_000f / sampleRate
                    val interleaved = renderer.outputBuffer
                    for (frame in 0 until frames) {
                        val left = interleaved[2 * frame]
                        val right = interleaved[2 * frame + 1]
                        telemetryLeft[frame] = left
                        telemetryRight[frame] = right
                        pcmLeft[frame] = pcm16(left)
                        pcmRight[frame] = pcm16(right)
                    }
                    telemetry.updateStereo(
                        rmsLeft = telemetry.calculateRms(pcmLeft, frames),
                        rmsRight = telemetry.calculateRms(pcmRight, frames),
                        correlation =
                            AudioTelemetryBuffer.stereoCorrelation(
                                telemetryLeft,
                                telemetryRight,
                                frames,
                            ),
                        outputMode = renderer.currentOutputMode,
                        latencyMs = latencyMs,
                        xrunCount = track.underrunCount.toLong(),
                        renderMicros = renderMicros,
                        isPlaying = true,
                    )
                    telemetry.pushWaveformStereo(pcmLeft, pcmRight, frames)

                    if (synchronized(lock) { panicGeneration != activeGeneration }) break
                    if (!requested && renderer.isSilent) break
                }

                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                }
                runCatching { track.flush() }
                telemetry.updateStereo(
                    rmsLeft = 0f,
                    rmsRight = 0f,
                    correlation = 1f,
                    outputMode = renderer.currentOutputMode,
                    latencyMs = 0f,
                    xrunCount = track.underrunCount.toLong(),
                    renderMicros = 0,
                    isPlaying = false,
                )
                renderer.panic()
                activeGeneration = -1L
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Exception) {
            val isPanic =
                synchronized(lock) {
                    activeGeneration >= 0L && panicGeneration != activeGeneration
                }
            if (!isPanic) {
                synchronized(lock) { playRequested = false }
                _events.tryEmit(HornAudioEvent.Failure(failure))
            }
        } finally {
            localTrack?.let { runCatching { it.release() } }
            synchronized(lock) {
                if (audioTrack === localTrack) audioTrack = null
                writerThread = null
            }
        }
    }

    private fun obtainTrack(): AudioTrack? {
        synchronized(lock) {
            audioTrack?.let { return it }
            if (released) return null
        }
        return try {
            val created = createTrack()
            synchronized(lock) {
                when {
                    released -> {
                        created.release()
                        null
                    }

                    audioTrack != null -> {
                        // prepare() won the race; never leak the second native track.
                        created.release()
                        audioTrack
                    }

                    else -> {
                        audioTrack = created
                        created
                    }
                }
            }
        } catch (failure: Exception) {
            _events.tryEmit(HornAudioEvent.Failure(failure))
            null
        }
    }

    /** PCM16 mirror conversion used only by the telemetry path (00 §1 C8). */
    private fun pcm16(sample: Float): Short = (sample * 32_767f).roundToInt().coerceIn(-32_768, 32_767).toShort()

    private companion object {
        const val ENGINE_NAME = "AudioTrack JVM"
        const val SAMPLE_RATE = 48_000
        const val FRAMES_PER_BLOCK = 240
        const val BYTES_PER_FRAME = 8 // 2 channels x Float32
        const val BUFFER_BLOCKS = 4
        const val RELEASE_JOIN_TIMEOUT_MS = 500L
    }
}

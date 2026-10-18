package com.yiyu.ledger.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceRecorder(private val context: Context, private val directory: File) {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var file: File? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    @Volatile var byteCount = 0; private set
    val isRunning get() = running
    fun start(name: String, onFrame: (ByteArray) -> Unit, onLimit: () -> Unit, onFailure: (String) -> Unit) {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "请允许麦克风权限后重新按住说话" }
        check(!running) { "录音正在进行" }
        require(Regex("^recording_[a-zA-Z0-9_-]+\\.wav$").matches(name))
        directory.mkdirs()
        val output = File(directory, name)
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "手机暂不支持当前录音格式" }
        val audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 6400)).build()
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风被占用，请稍后重试" }
        } catch (error: Exception) { audio.release(); throw error }
        file = output; recorder = audio; byteCount = 0; running = true
        if (Build.VERSION.SDK_INT >= 29) {
            val callback = object: AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if (running && configs.any { it.isClientSilenced }) onFailure("麦克风被系统中断，请重新录入")
                }
            }
            audio.registerAudioRecordingCallback(context.mainExecutor, callback)
            recordingCallback = callback
        }
        worker = Thread({
            var limit = false
            var failure: String? = null
            try {
                RandomAccessFile(output, "rw").use { destination ->
                    destination.setLength(0); destination.write(wavHeader(0))
                    val buffer = ByteArray(3200)
                    while (running && byteCount < 960000) {
                        val read = audio.read(buffer, 0, minOf(buffer.size, 960000 - byteCount), AudioRecord.READ_BLOCKING)
                        if (read < 0) { if (running) error("麦克风被系统中断，请重试"); break }
                        if (read == 0) continue
                        val count = read - read % 2
                        if (count > 0) {
                            destination.write(buffer, 0, count); byteCount += count
                            onFrame(buffer.copyOf(count))
                        }
                    }
                    limit = byteCount >= 960000
                    destination.seek(0); destination.write(wavHeader(byteCount)); destination.fd.sync()
                }
            } catch (_: Exception) { if (running) failure = "录音未能完成，请重试" }
            finally {
                running = false
                runCatching { audio.stop() }
                // Release happens in stop(), after this worker has finished using the recorder.
            }
            if (failure != null) onFailure(failure!!)
            else if (limit) onLimit()
        }, "yiyu-microphone").apply { start() }
    }
    /** Call from Dispatchers.IO; waits until the WAV header and file are complete. */
    @Synchronized fun stop(): File? {
        running = false
        runCatching { recorder?.stop() }
        worker?.join(2500)
        check(worker?.isAlive != true) { "麦克风尚未释放，请稍后重试" }
        if (Build.VERSION.SDK_INT >= 29) recordingCallback?.let { recorder?.unregisterAudioRecordingCallback(it) }
        recordingCallback = null
        recorder?.release(); recorder = null; worker = null
        return file
    }
    fun cancel() { stop()?.delete(); file = null }
    companion object {
        fun wavHeader(size: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
        }.array()
        fun replay(file: File, onFrame: (ByteArray) -> Unit) {
            require(file.length() in 6444..960044) { "录音文件不完整，请重新录入" }
            file.inputStream().use { source ->
                val header = ByteArray(44)
                require(source.read(header) == 44) { "录音文件不完整" }
                val size = (file.length() - 44).toInt()
                require(header.contentEquals(wavHeader(size)) && size % 2 == 0) { "录音格式不正确" }
                val buffer = ByteArray(3200)
                while (true) { val read = source.read(buffer); if (read < 0) break; onFrame(buffer.copyOf(read)) }
            }
        }
    }
}

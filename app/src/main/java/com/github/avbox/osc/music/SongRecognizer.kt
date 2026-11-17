package com.github.avbox.osc.music

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import com.github.avbox.osc.util.KV
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 听歌识曲 (v1.20.0): records a short sample with the microphone and
 * identifies it through ACRCloud's identification API — the same
 * engine behind most "name that song" features. The app ships no
 * credentials: Torres registers his own ACRCloud project and pastes
 * its Host / Access Key / Access Secret into the in-app dialog once;
 * the three values persist in KV and never leave the device except
 * as the API's own HMAC signature.
 *
 * API contract (verified against ACRCloud's SDK sources): POST
 * https://{host}/v1/identify as multipart/form-data with the audio
 * in `sample`, plus access_key / data_type=audio /
 * signature_version=1 / timestamp / signature, where the signature
 * is base64(HMAC-SHA1(secret, "POST\n/v1/identify\n{access_key}\n
 * audio\n1\n{timestamp}")). WAV samples are accepted, so the
 * recorder below only has to wrap raw PCM in a RIFF header — no
 * encoder dependency. All KV reads pass a "" default (without one,
 * unregistered keys decode to null — the v1.9.0 music-import bug).
 */
object SongRecognizer {

    private const val KV_HOST = "lx_acr_host"
    private const val KV_KEY = "lx_acr_access_key"
    private const val KV_SECRET = "lx_acr_access_secret"

    private const val SAMPLE_RATE = 16000
    private const val IDENTIFY_PATH = "/v1/identify"

    data class Keys(val host: String, val accessKey: String, val accessSecret: String)

    data class RecognizedSong(
        val title: String,
        val artist: String,
        val album: String,
        val score: Double,
    )

    class RecognizeException(message: String) : Exception(message)

    fun loadKeys(): Keys = Keys(
        host = KV.get(KV_HOST, ""),
        accessKey = KV.get(KV_KEY, ""),
        accessSecret = KV.get(KV_SECRET, ""),
    )

    fun saveKeys(keys: Keys) {
        KV.put(KV_HOST, normalizeHost(keys.host))
        KV.put(KV_KEY, keys.accessKey.trim())
        KV.put(KV_SECRET, keys.accessSecret.trim())
    }

    fun hasKeys(): Boolean {
        val keys = loadKeys()
        return keys.host.isNotBlank() && keys.accessKey.isNotBlank() &&
            keys.accessSecret.isNotBlank()
    }

    /** Accepts a bare host or a pasted URL and keeps just the host. */
    fun normalizeHost(raw: String): String {
        var host = raw.trim()
        host = host.removePrefix("https://").removePrefix("http://")
        val slash = host.indexOf('/')
        if (slash >= 0) host = host.substring(0, slash)
        return host.trim()
    }

    /**
     * Records [seconds] of mono 16 kHz PCM and returns it as a WAV
     * byte array. Runs on the caller's (IO) thread; [cancelled] is
     * polled between reads so the listening dialog's cancel button
     * stops the capture promptly. Throws [RecognizeException] with a
     * user-readable message on any device-side failure.
     */
    fun recordWav(seconds: Int, cancelled: () -> Boolean): ByteArray {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw RecognizeException("录音设备不可用")
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, 8192),
            )
        } catch (t: Throwable) {
            throw RecognizeException("录音初始化失败")
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw RecognizeException("录音初始化失败")
            }
            val pcm = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            val targetBytes = SAMPLE_RATE * 2 * seconds
            recorder.startRecording()
            try {
                while (pcm.size() < targetBytes) {
                    if (cancelled()) throw RecognizeException("已取消")
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        pcm.write(chunk, 0, minOf(read, targetBytes - pcm.size()))
                    } else if (read < 0) {
                        throw RecognizeException("录音中断")
                    }
                }
            } finally {
                try {
                    recorder.stop()
                } catch (t: Throwable) {
                    // Already stopped / never started; the sample (or
                    // the failure above) is what matters.
                }
            }
            return wrapWav(pcm.toByteArray())
        } finally {
            recorder.release()
        }
    }

    /** Wraps 16-bit mono PCM in a canonical 44-byte RIFF/WAVE header. */
    private fun wrapWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val byteRate = SAMPLE_RATE * 2
        fun writeIntLE(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        fun writeShortLE(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLE(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeIntLE(16)
        writeShortLE(1) // PCM
        writeShortLE(1) // mono
        writeIntLE(SAMPLE_RATE)
        writeIntLE(byteRate)
        writeShortLE(2) // block align
        writeShortLE(16) // bits per sample
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLE(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * Identifies a WAV sample. Returns null when the service ran
     * fine but found no match; throws [RecognizeException] for
     * credential / network / service failures.
     */
    fun identify(wav: ByteArray, keys: Keys): RecognizedSong? {
        val host = normalizeHost(keys.host)
        if (host.isEmpty() || keys.accessKey.isBlank() || keys.accessSecret.isBlank()) {
            throw RecognizeException("密钥未填写完整")
        }
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val stringToSign = "POST\n$IDENTIFY_PATH\n${keys.accessKey}\naudio\n1\n$timestamp"
        val signature = try {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(keys.accessSecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            Base64.encodeToString(
                mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP,
            )
        } catch (t: Throwable) {
            throw RecognizeException("签名失败")
        }

        val boundary = "avbox-" + System.currentTimeMillis().toString(16)
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write(
                ("--$boundary\r\nContent-Disposition: form-data; " +
                    "name=\"$name\"\r\n\r\n$value\r\n").toByteArray(Charsets.UTF_8),
            )
        }
        field("access_key", keys.accessKey)
        field("data_type", "audio")
        field("signature_version", "1")
        field("signature", signature)
        field("timestamp", timestamp)
        field("sample_bytes", wav.size.toString())
        body.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"sample\"; " +
                "filename=\"sample.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                .toByteArray(Charsets.UTF_8),
        )
        body.write(wav)
        body.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))

        val connection = try {
            (URL("https://$host$IDENTIFY_PATH").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
        } catch (t: Throwable) {
            throw RecognizeException("网络不可用")
        }
        val responseText = try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } catch (t: Throwable) {
            throw RecognizeException("网络请求失败")
        } finally {
            connection.disconnect()
        }

        val root = try {
            JSONObject(responseText)
        } catch (t: Throwable) {
            throw RecognizeException("识别服务返回异常")
        }
        val status = root.optJSONObject("status")
        val code = status?.optInt("code", -1) ?: -1
        if (code != 0) {
            val msg = status?.optString("msg", "") ?: ""
            throw RecognizeException(
                if (msg.isNotBlank()) "识别服务：$msg" else "识别服务返回错误（$code）",
            )
        }
        val music = root.optJSONObject("metadata")?.optJSONArray("music")
        if (music == null || music.length() == 0) return null
        val first = music.optJSONObject(0) ?: return null
        val title = first.optString("title", "")
        if (title.isBlank()) return null
        val artists = first.optJSONArray("artists")
        val artist = if (artists != null && artists.length() > 0) {
            artists.optJSONObject(0)?.optString("name", "") ?: ""
        } else {
            ""
        }
        val album = first.optJSONObject("album")?.optString("name", "") ?: ""
        return RecognizedSong(
            title = title,
            artist = artist,
            album = album,
            score = first.optDouble("score", 0.0),
        )
    }
}

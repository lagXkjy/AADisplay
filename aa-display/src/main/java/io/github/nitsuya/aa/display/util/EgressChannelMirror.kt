package io.github.nitsuya.aa.display.util

import android.annotation.SuppressLint
import android.util.Log
import io.github.nitsuya.aa.display.egress.EgressChannel
import java.io.File

/**
 * system_server-readable mirror of the user's egress channel.
 *
 * App CE `files/aadisplay_carplay_channel` is the user-facing source of truth, but
 * system_server cannot reliably read app data (0700). BootArm uses this
 * `/data/system` file, written only from [CoreManagerService] after the app
 * reports the channel over Binder. No Settings.Global — OEM often blocks it.
 */
object EgressChannelMirror {
    private const val TAG = "AADisplay_EgressMirror"
    const val PATH = "/data/system/aadisplay_egress_channel"

    @Volatile
    private var cached: Int? = null

    fun read(): Int? {
        cached?.let { return it }
        val fromFile = readFile()
        if (fromFile != null) {
            cached = fromFile
            return fromFile
        }
        return null
    }

    /** Persist from system_server. */
    fun write(channel: Int): Boolean {
        val normalized = when (channel) {
            EgressChannel.CHANNEL_CARPLAY,
            EgressChannel.CHANNEL_AAP,
            -> channel
            else -> return false
        }
        val ok = writeFile(normalized)
        if (ok) {
            cached = normalized
            Log.i(TAG, "mirrored channel=$normalized path=$PATH")
        }
        return ok
    }

    private fun readFile(): Int? {
        val f = File(PATH)
        if (!f.isFile) return null
        return try {
            parse(f.readText().trim())
        } catch (e: Throwable) {
            Log.w(TAG, "read failed", e)
            null
        }
    }

    @SuppressLint("SetWorldReadable")
    private fun writeFile(channel: Int): Boolean {
        return try {
            val f = File(PATH)
            f.parentFile?.mkdirs()
            val tmp = File("$PATH.tmp")
            tmp.writeText(channel.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(channel.toString())
                tmp.delete()
            }
            f.setReadable(true, false)
            f.setWritable(true, true)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "write failed", e)
            false
        }
    }

    private fun parse(text: String): Int? = when (text) {
        EgressChannel.CHANNEL_CARPLAY.toString(), "cp", "carplay" -> EgressChannel.CHANNEL_CARPLAY
        EgressChannel.CHANNEL_AAP.toString(), "aap", "native-aa" -> EgressChannel.CHANNEL_AAP
        else -> null
    }
}

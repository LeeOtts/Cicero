package com.leeotts.cicero.audio

import android.content.Context
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import com.leeotts.cicero.TAG
import java.io.File

/**
 * Porcupine, behind [WakeDetectors].
 *
 * Uses the low-level Porcupine class rather than PorcupineManager on purpose.
 * PorcupineManager opens its own AudioRecord on the phone microphone, which
 * would make both the glasses path and the arming gate impossible - the whole
 * design depends on owning the microphone ourselves so it can be closed the
 * instant policy says so.
 *
 * Two failures are overwhelmingly the likely ones and neither may crash a
 * foreground service: a rejected access key, and a keyword file that is missing
 * or was trained against a different engine version. Both come back as a
 * Result the Settings screen can render as a sentence.
 */
class PorcupineDetectors(context: Context) : WakeDetectors {

    private val appContext = context.applicationContext

    override fun create(accessKey: String, sensitivity: Float): Result<WakeDetector> {
        if (accessKey.isBlank()) {
            return Result.failure(
                IllegalStateException("Add a Picovoice access key in Settings to listen for Hey Cicero."),
            )
        }
        val keyword = installedKeyword(appContext)
            ?: return Result.failure(
                IllegalStateException("The Hey Cicero keyword file is missing. Add it in Settings."),
            )

        return runCatching {
            val engine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(keyword.absolutePath)
                .setSensitivity(sensitivity.coerceIn(0f, 1f))
                .build(appContext)
            PorcupineDetector(engine)
        }.recoverCatching {
            Log.e(TAG, "Porcupine would not start", it)
            // Its own messages name the cause well enough to show verbatim -
            // an expired key and a version-mismatched keyword read differently.
            throw IllegalStateException(it.message ?: "The wake word engine would not start.")
        }
    }
}

private class PorcupineDetector(private val engine: Porcupine) : WakeDetector {
    override val frameLength = engine.frameLength
    override val sampleRate = engine.sampleRate
    override fun process(frame: ShortArray): Int = engine.process(frame)
    override fun close() {
        runCatching { engine.delete() }
    }
}

/** Where a keyword lives once installed, whether shipped or picked by the user. */
internal fun keywordFile(context: Context) =
    File(File(context.filesDir, KEYWORD_DIR), KEYWORD_NAME)

/** The installed keyword, or null when there is not one to use. */
internal fun installedKeyword(context: Context): File? =
    keywordFile(context).takeIf { it.isFile && it.length() > 0 }

/**
 * Copies the keyword shipped in assets into files, if one was shipped and
 * nothing is installed yet.
 *
 * Absolute paths are used rather than asset-relative ones because Porcupine's
 * handling of the latter has shifted between versions. Going through files also
 * makes "is a keyword installed" a fact Settings can check and report, and
 * gives the user somewhere to drop a replacement when a free-tier keyword
 * expires - without waiting on a new build.
 */
internal fun installBundledKeyword(context: Context) {
    val target = keywordFile(context)
    if (target.isFile && target.length() > 0) return
    runCatching {
        context.assets.open("$KEYWORD_DIR/$KEYWORD_NAME").use { input ->
            target.parentFile?.mkdirs()
            target.outputStream().use(input::copyTo)
        }
        Log.i(TAG, "installed the bundled Hey Cicero keyword")
    }.onFailure {
        // Expected when no keyword ships with the build: the user trains their
        // own on the Picovoice console and imports it from Settings.
        Log.i(TAG, "no bundled keyword to install (${it.message})")
    }
}

private const val KEYWORD_DIR = "porcupine"
private const val KEYWORD_NAME = "hey_cicero_android.ppn"

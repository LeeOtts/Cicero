package com.leeotts.cicero.audio

import android.content.Context
import android.content.res.AssetManager
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Where the wake word lives.
 *
 * The first two are openWakeWord's shared front end and are the same for every
 * phrase. Only the classifier changes, so swapping the wake word is this one
 * line plus the matching asset.
 *
 * NOTE: hey_jarvis is one of openWakeWord's pre-trained models and is licensed
 * CC BY-NC-SA 4.0. It stands in until "Hey Cicero" is trained - a model trained
 * from the project's own Colab carries no such restriction, which is the reason
 * to train one rather than ship this.
 */
private const val MELSPEC_ASSET = "wakeword/melspectrogram.tflite"
private const val EMBEDDING_ASSET = "wakeword/embedding_model.tflite"
private const val CLASSIFIER_ASSET = "wakeword/hey_jarvis_v0.1.tflite"

/** openWakeWord's transform of the raw melspectrogram output. */
private const val MEL_SCALE = 10f
private const val MEL_OFFSET = 2f

/**
 * The real models, loaded out of the APK's assets.
 *
 * One interpreter per graph, single-threaded on purpose: these are small graphs
 * run once per 80 ms, and handing them a thread pool costs more in wakeups and
 * contention than it saves in latency - which is the wrong trade for something
 * that runs all day.
 */
internal class TfLiteWakeWordModels private constructor(
    private val melspec: Interpreter,
    private val embedding: Interpreter,
    private val classifier: Interpreter,
) : WakeWordModels {

    companion object {
        fun load(assets: AssetManager): TfLiteWakeWordModels {
            val options = Interpreter.Options().setNumThreads(1)
            return TfLiteWakeWordModels(
                melspec = Interpreter(map(assets, MELSPEC_ASSET), options),
                embedding = Interpreter(map(assets, EMBEDDING_ASSET), options),
                classifier = Interpreter(map(assets, CLASSIFIER_ASSET), options),
            )
        }

        /**
         * Maps an asset without copying it onto the heap.
         *
         * openFd throws for a compressed asset, which is what the noCompress
         * rule in build.gradle.kts is there to prevent.
         */
        private fun map(assets: AssetManager, path: String): MappedByteBuffer =
            assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        fd.startOffset,
                        fd.declaredLength,
                    )
                }
            }
    }

    /** How many samples the melspectrogram graph is currently shaped for. */
    private var melInputSize = 0

    /**
     * The melspectrogram graph ships with a placeholder [1, 1] input and has to
     * be resized to the real sample count before it will run. The output length
     * follows from that, so it is read back rather than assumed.
     */
    override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
        if (melInputSize != samples.size) {
            melspec.resizeInput(0, intArrayOf(1, samples.size))
            melspec.allocateTensors()
            melInputSize = samples.size
        }

        val shape = melspec.getOutputTensor(0).shape() // [1, 1, frames, bins]
        val frames = shape[2]
        val bins = shape[3]
        val out = Array(1) { Array(1) { Array(frames) { FloatArray(bins) } } }
        melspec.run(arrayOf(samples), out)

        // Upstream applies this before the embedding model. Without it the
        // embeddings land outside the range the classifiers were trained on and
        // the wake word simply never fires.
        return Array(frames) { f ->
            FloatArray(bins) { b -> out[0][0][f][b] / MEL_SCALE + MEL_OFFSET }
        }
    }

    override fun embed(window: Array<FloatArray>): FloatArray {
        val input = Array(1) { Array(window.size) { f ->
            Array(window[f].size) { b -> floatArrayOf(window[f][b]) }
        } }
        val out = Array(1) { Array(1) { Array(1) { FloatArray(EMBEDDING_SIZE) } } }
        embedding.run(input, out)
        return out[0][0][0]
    }

    override fun classify(features: Array<FloatArray>): Float {
        val out = Array(1) { FloatArray(1) }
        classifier.run(arrayOf(features), out)
        return out[0][0]
    }

    override fun close() {
        melspec.close()
        embedding.close()
        classifier.close()
    }
}

private const val EMBEDDING_SIZE = 96

/** Loads the packaged models. Held apart so tests can substitute fakes. */
internal class WakeWordAssets(context: Context) : WakeWordModelSource {
    private val assets = context.applicationContext.assets
    override fun load(): WakeWordModels = TfLiteWakeWordModels.load(assets)
}

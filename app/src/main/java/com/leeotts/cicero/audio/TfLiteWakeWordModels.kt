package com.leeotts.cicero.audio

import android.content.Context
import android.content.res.AssetManager
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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

private const val MEL_BINS = 32
private const val EMBED_WINDOW = 76
private const val EMBEDDING_SIZE = 96
private const val FEATURE_FRAMES = 16

/**
 * A direct buffer and its float view, kept together.
 *
 * TFLite reads and writes direct buffers without copying. The alternative - the
 * nested-array overload - allocates a FloatArray per row, which for the
 * embedding model's 76 x 32 input is 2432 objects every 80 ms, all of them
 * garbage immediately afterwards.
 */
private class Scratch(elements: Int) {
    val bytes: ByteBuffer =
        ByteBuffer.allocateDirect(elements * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    val floats: FloatBuffer = bytes.asFloatBuffer()
}

/**
 * The real models, loaded out of the APK's assets.
 *
 * One interpreter per graph, single-threaded on purpose: these are small graphs
 * run once per 80 ms, and handing them a thread pool costs more in wakeups and
 * contention than it saves in latency - which is the wrong trade for something
 * that runs all day. XNNPACK is asked for explicitly rather than left to the
 * default, because the embedding model is twenty convolutions and its kernels
 * are the difference between a few ms and a few tens of ms per chunk.
 *
 * Not thread-safe: the scratch buffers are reused, so one capture loop only.
 */
internal class TfLiteWakeWordModels private constructor(
    private val melspec: Interpreter,
    private val embedding: Interpreter,
    private val classifier: Interpreter,
) : WakeWordModels {

    companion object {
        fun load(assets: AssetManager): TfLiteWakeWordModels {
            val options = Interpreter.Options().setNumThreads(1).setUseXNNPACK(true)
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

    private val embedIn = Scratch(EMBED_WINDOW * MEL_BINS)
    private val embedOut = Scratch(EMBEDDING_SIZE)
    private val classifyIn = Scratch(FEATURE_FRAMES * EMBEDDING_SIZE)
    private val classifyOut = Scratch(1)

    /** Sized to whatever the melspectrogram graph is currently shaped for. */
    private var melIn: Scratch? = null
    private var melOut: Scratch? = null
    private var melFrames = 0

    /**
     * The melspectrogram graph ships with a placeholder [1, 1] input and has to
     * be resized to the real sample count before it will run. The output length
     * follows from that, so it is read back rather than assumed.
     */
    override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
        var input = melIn
        var output = melOut
        if (input == null || input.floats.capacity() != samples.size) {
            melspec.resizeInput(0, intArrayOf(1, samples.size))
            melspec.allocateTensors()
            val shape = melspec.getOutputTensor(0).shape() // [1, 1, frames, bins]
            melFrames = shape[2]
            input = Scratch(samples.size).also { melIn = it }
            output = Scratch(melFrames * shape[3]).also { melOut = it }
        }
        requireNotNull(output)

        input.floats.rewind()
        input.floats.put(samples)
        input.bytes.rewind()
        output.bytes.rewind()
        melspec.run(input.bytes, output.bytes)

        // Upstream applies this before the embedding model. Without it the
        // embeddings land outside the range the classifiers were trained on and
        // the wake word simply never fires.
        output.floats.rewind()
        return Array(melFrames) {
            val frame = FloatArray(MEL_BINS)
            for (b in 0 until MEL_BINS) frame[b] = output.floats.get() / MEL_SCALE + MEL_OFFSET
            frame
        }
    }

    override fun embed(window: Array<FloatArray>): FloatArray {
        embedIn.floats.rewind()
        for (frame in window) embedIn.floats.put(frame)
        embedIn.bytes.rewind()
        embedOut.bytes.rewind()
        embedding.run(embedIn.bytes, embedOut.bytes)

        embedOut.floats.rewind()
        val out = FloatArray(EMBEDDING_SIZE)
        embedOut.floats.get(out)
        return out
    }

    override fun classify(features: Array<FloatArray>): Float {
        classifyIn.floats.rewind()
        for (frame in features) classifyIn.floats.put(frame)
        classifyIn.bytes.rewind()
        classifyOut.bytes.rewind()
        classifier.run(classifyIn.bytes, classifyOut.bytes)

        return classifyOut.floats.get(0)
    }

    override fun close() {
        melspec.close()
        embedding.close()
        classifier.close()
    }
}

/** Loads the packaged models. Held apart so tests can substitute fakes. */
internal class WakeWordAssets(context: Context) : WakeWordModelSource {
    private val assets = context.applicationContext.assets
    override fun load(): WakeWordModels = TfLiteWakeWordModels.load(assets)
}

package ai.forma.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One pose reading, engine-independent and already turned upright.
 *
 * The runner records two landmarks per frame as a stability probe. They were
 * the heels while heel lift was in the fault set; they are now the **knees**,
 * because the knee is the landmark the depth and lean thresholds actually
 * depend on and it is the only distal joint present in both topologies. A
 * probe on a landmark one engine does not emit cannot compare two engines.
 */
data class PoseReading(
    val timestampMs: Long,
    val found: Boolean,
    val leftX: Float,
    val leftY: Float,
    val leftVis: Float,
    val rightX: Float,
    val rightY: Float,
    val rightVis: Float,
)

/**
 * Whatever the harness can point a camera at.
 *
 * [submit] returns false when the frame was dropped rather than accepted, so
 * the caller can forget the preparation cost it recorded for it. Both engines
 * are asynchronous: MediaPipe because it runs in LIVE_STREAM mode, MoveNet
 * because this harness drives it on its own single thread. Measuring one
 * synchronously against the other asynchronously would compare the harness
 * with itself rather than the two engines.
 */
interface PoseEngine {
    fun submit(bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int): Boolean
    fun close()
}

enum class Family(val label: String) { MEDIAPIPE("mediapipe"), MOVENET("movenet") }

enum class Backend(val label: String) { CPU("cpu"), GPU("gpu") }

data class Config(
    val family: Family,
    val model: String,
    val asset: String,
    val backend: Backend,
) {
    val label: String get() = "$model-${backend.label}"
}

/**
 * The rows of the Chapter 4 engine table, in the order they are run.
 *
 * MediaPipe first so that the six configurations keep the labels the
 * 2026-11-02 run used and remain comparable by name. MoveNet last because it
 * is the addition.
 */
val ALL_CONFIGS: List<Config> = buildList {
    listOf(
        "lite" to "pose_landmarker_lite.task",
        "full" to "pose_landmarker_full.task",
        "heavy" to "pose_landmarker_heavy.task",
    ).forEach { (model, asset) ->
        Backend.entries.forEach { add(Config(Family.MEDIAPIPE, model, asset, it)) }
    }
    Backend.entries.forEach {
        add(Config(Family.MOVENET, "movenet-lightning", "movenet.tflite", it))
    }
}

/** What a single-configuration run starts on. */
val SHIPPED_CONFIG: Config = ALL_CONFIGS.first { it.label == "lite-gpu" }

/** Knee indices. BlazePose emits 33 landmarks, MoveNet the 17 of COCO. */
object BlazePose {
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val COUNT = 33
}

object Coco {
    const val LEFT_KNEE = 13
    const val RIGHT_KNEE = 14
    const val COUNT = 17
}

/**
 * Rotates a normalised landmark from the sensor's frame into the upright one.
 *
 * Only MediaPipe needs this. It is given the turn through
 * `ImageProcessingOptions` so it can fold the rotation into the resize it
 * already performs, which is what took preparation from 44 ms to 7 ms, and it
 * then reports landmarks in the coordinates of the image it was handed, which
 * is the unrotated one. The MoveNet adapter rotates during its own letterbox
 * and so reports upright already.
 */
fun upright(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
    when (((rotationDegrees % 360) + 360) % 360) {
        90 -> (1f - y) to x
        180 -> (1f - x) to (1f - y)
        270 -> y to (1f - x)
        else -> x to y
    }

// ---------------------------------------------------------------- MediaPipe

/**
 * MediaPipe PoseLandmarker in LIVE_STREAM mode.
 *
 * Construction can throw: the GPU delegate is not available for every model on
 * every device, and a failure here is a finding rather than a crash. The
 * runner catches it and records the configuration as unavailable.
 */
class MediaPipeEngine(
    context: Context,
    private val config: Config,
    private val onReading: (PoseReading) -> Unit,
    private val onError: (String) -> Unit,
) : PoseEngine {

    private val rotationByTs = HashMap<Long, Int>()

    private val landmarker: PoseLandmarker = run {
        val base = BaseOptions.builder()
            .setModelAssetPath(config.asset)
            .setDelegate(if (config.backend == Backend.GPU) Delegate.GPU else Delegate.CPU)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ ->
                val ts = result.timestampMs()
                val rotation = synchronized(rotationByTs) { rotationByTs.remove(ts) } ?: 0
                val lm = result.landmarks().firstOrNull()
                val found = lm != null && lm.size >= BlazePose.COUNT
                val l = if (found) lm!![BlazePose.LEFT_KNEE] else null
                val r = if (found) lm!![BlazePose.RIGHT_KNEE] else null
                val (lx, ly) = upright(l?.x() ?: 0f, l?.y() ?: 0f, rotation)
                val (rx, ry) = upright(r?.x() ?: 0f, r?.y() ?: 0f, rotation)
                onReading(
                    PoseReading(
                        timestampMs = ts,
                        found = found,
                        leftX = lx, leftY = ly,
                        leftVis = l?.visibility()?.orElse(0f) ?: 0f,
                        rightX = rx, rightY = ry,
                        rightVis = r?.visibility()?.orElse(0f) ?: 0f,
                    )
                )
            }
            .setErrorListener { e -> onError(e.message ?: "unknown MediaPipe error") }
            .build()

        PoseLandmarker.createFromOptions(context, options)
    }

    override fun submit(bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int): Boolean {
        // The two frameworks count rotation in opposite directions. CameraX
        // reports the clockwise rotation needed to bring the frame upright;
        // ImageProcessingOptions takes counterclockwise degrees. Passing the
        // CameraX value through unconverted lands the image 180 degrees out on
        // a portrait-locked phone, which still produces plausible-looking
        // output, so the conversion is explicit.
        val counterclockwise = (360 - (rotationDegrees % 360)) % 360
        val options = ImageProcessingOptions.builder()
            .setRotationDegrees(counterclockwise)
            .build()
        synchronized(rotationByTs) { rotationByTs[timestampMs] = rotationDegrees }
        return runCatching {
            landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), options, timestampMs)
        }.onFailure {
            synchronized(rotationByTs) { rotationByTs.remove(timestampMs) }
        }.isSuccess
    }

    override fun close() {
        runCatching { landmarker.close() }
    }
}

// ------------------------------------------------------------------ MoveNet

/**
 * MoveNet SinglePose Lightning on a bare TFLite interpreter.
 *
 * Input is `image`, [1, 192, 192, 3] float32 RGB in 0 to 1. Output is
 * `kpt_with_conf`, [1, 1, 17, 3], each row being y, x and a confidence, all
 * normalised to the 192-pixel input square.
 *
 * **Why the work happens here rather than in the caller.** MediaPipe performs
 * its own rotation and resize inside the engine, between submission and the
 * result callback, so that cost lands in the measured inference time rather
 * than in the measured preparation time. This adapter does its letterbox on
 * the inference thread for the same reason: if the caller resized, MoveNet's
 * preparation would carry a cost MediaPipe's does not and the split between
 * the two columns would stop meaning the same thing for each engine.
 */
class MoveNetEngine(
    context: Context,
    private val config: Config,
    private val onReading: (PoseReading) -> Unit,
    private val onError: (String) -> Unit,
) : PoseEngine {

    private companion object {
        const val SIDE = 192
    }

    private var gpuDelegate: GpuDelegate? = null

    private val interpreter: Interpreter = run {
        val options = Interpreter.Options()
        if (config.backend == Backend.GPU) {
            val d = GpuDelegate()
            gpuDelegate = d
            options.addDelegate(d)
        } else {
            options.numThreads = 4
        }
        Interpreter(loadAsset(context, config.asset), options)
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private val square = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(SIDE * SIDE)
    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(SIDE * SIDE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(1) { Array(Coco.COUNT) { FloatArray(3) } } }

    private fun loadAsset(context: Context, name: String): ByteBuffer {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { stream ->
            return stream.channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        }
    }

    /**
     * Drops the frame when inference is already running, which is what
     * LIVE_STREAM does on the MediaPipe side. Holding the frame instead would
     * turn the frame rate into a measure of the queue rather than the model.
     */
    override fun submit(bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        return runCatching {
            worker.execute {
                try {
                    run(bitmap, timestampMs, rotationDegrees)
                } catch (t: Throwable) {
                    onError(t.message ?: "unknown TFLite error")
                } finally {
                    busy.set(false)
                }
            }
        }.onFailure { busy.set(false) }.isSuccess
    }

    private fun run(bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int) {
        val rot = ((rotationDegrees % 360) + 360) % 360
        val swapped = rot == 90 || rot == 270
        val uw = if (swapped) bitmap.height else bitmap.width
        val uh = if (swapped) bitmap.width else bitmap.height

        // Letterbox rather than squash. MoveNet wants a square and the frame
        // is 9:16; stretching it would distort every angle the thresholds are
        // built on, and the distortion would be read as the model being bad.
        val scale = SIDE.toFloat() / maxOf(uw, uh)
        val padX = (SIDE - uw * scale) / 2f
        val padY = (SIDE - uh * scale) / 2f

        val m = Matrix()
        m.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        m.postRotate(rot.toFloat())
        m.postScale(scale, scale)
        m.postTranslate(SIDE / 2f, SIDE / 2f)

        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(bitmap, m, paint)

        square.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()

        interpreter.run(input, output)

        val k = output[0][0]
        val l = unpad(k[Coco.LEFT_KNEE], padX, padY, scale, uw, uh)
        val r = unpad(k[Coco.RIGHT_KNEE], padX, padY, scale, uw, uh)

        // MoveNet always returns seventeen points with a score each; it has no
        // notion of "no pose in frame". An empty room still produces a full
        // skeleton at low confidence, so the presence test has to be the score
        // and not the count, which is the opposite of the MediaPipe case.
        val found = k.any { it[2] >= 0.3f }

        onReading(
            PoseReading(
                timestampMs = timestampMs,
                found = found,
                leftX = l[0], leftY = l[1], leftVis = l[2],
                rightX = r[0], rightY = r[1], rightVis = r[2],
            )
        )
    }

    /** From the padded 192-square back into normalised upright image space. */
    private fun unpad(
        kp: FloatArray, padX: Float, padY: Float, scale: Float, uw: Int, uh: Int,
    ): FloatArray {
        val px = kp[1] * SIDE
        val py = kp[0] * SIDE
        return floatArrayOf(
            ((px - padX) / scale / uw).coerceIn(0f, 1f),
            ((py - padY) / scale / uh).coerceIn(0f, 1f),
            kp[2],
        )
    }

    override fun close() {
        worker.shutdown()
        runCatching { interpreter.close() }
        runCatching { gpuDelegate?.close() }
    }
}

/** Builds whichever engine the configuration names. */
fun createEngine(
    context: Context,
    config: Config,
    onReading: (PoseReading) -> Unit,
    onError: (String) -> Unit,
): PoseEngine = when (config.family) {
    Family.MEDIAPIPE -> MediaPipeEngine(context, config, onReading, onError)
    Family.MOVENET -> MoveNetEngine(context, config, onReading, onError)
}

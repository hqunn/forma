package ai.forma.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import ai.forma.assessment.Landmarks
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The pose estimator, fixed at the configuration the engine measurement chose.
 *
 * MoveNet Lightning on the GPU delegate. The benchmark can select any of
 * eight configurations because selecting between them is its purpose; this
 * one takes no parameters at all, because a configuration the product can be
 * put into but which was never measured is a configuration whose behaviour
 * nobody can state.
 *
 * Measured on the target device, run `sweep-20261104-204742`: 29.57 fps,
 * 13 ms median inference, 27 ms p95 from frame delivery to assessment
 * complete, against a 65 ms requirement that no MediaPipe configuration in
 * the same run came under. It ran last and hottest, at 38.5 degrees, and
 * still won by a factor of two and a half.
 *
 * **What this costs.** MoveNet emits a keypoint score rather than BlazePose's
 * visibility, and the two are not the same quantity. On the same pose, the
 * knee scored a median of 0.76 here against 0.99 there, so a confidence floor
 * carried across unchanged is a much tighter gate than it was. Framing
 * discipline matters correspondingly more.
 */
class PoseEngine(
    context: Context,
    private val onResult: (List<FrameAdapter.Reading>, Long) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var gpuDelegate: GpuDelegate? = null

    private val interpreter: Interpreter = run {
        val options = Interpreter.Options()
        val d = GpuDelegate()
        gpuDelegate = d
        options.addDelegate(d)
        Interpreter(mapAsset(context, MODEL_ASSET), options)
    }

    private val worker = Executors.newSingleThreadExecutor()

    /**
     * A frame arriving while inference is running is dropped, not queued.
     *
     * A queued frame is eventually answered, but the answer describes a
     * position the body has already left, and a correction about a position
     * the user is no longer in is wrong feedback rather than late feedback.
     * Dropping keeps every answer current at the cost of answering less
     * often, which is the trade NFR-P2 exists to bound.
     */
    private val busy = AtomicBoolean(false)

    /**
     * Set before the interpreter is destroyed, so a frame that arrives during
     * teardown is refused rather than handed to a closed native object.
     */
    private val closed = AtomicBoolean(false)

    private val square = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(SIDE * SIDE)
    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(SIDE * SIDE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(1) { Array(Landmarks.COUNT) { FloatArray(3) } } }

    private fun mapAsset(context: Context, name: String): ByteBuffer {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { stream ->
            return stream.channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        }
    }

    /**
     * Submits one frame. The result arrives later on the worker thread.
     *
     * Returns false when the frame was dropped because inference was already
     * running, so the caller can discard whatever it was holding for it
     * rather than wait for a result that will never come.
     *
     * [timestampMs] must come from a monotonic clock. The assessment layer
     * measures every interval from this same reading, so a clock that can
     * step backwards corrupts the segmenter and the trigger policy at once.
     */
    fun submit(bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int): Boolean {
        if (closed.get()) return false
        if (!busy.compareAndSet(false, true)) return false
        return runCatching {
            worker.execute {
                try {
                    run(bitmap, timestampMs, rotationDegrees)
                } catch (t: Throwable) {
                    onError(t.message ?: "pose inference failed")
                } finally {
                    busy.set(false)
                }
            }
        }.onFailure { busy.set(false) }.isSuccess
    }

    /**
     * The turn is applied to the image before inference rather than to the
     * landmarks after it, and that is forced rather than chosen: MoveNet is
     * not rotation invariant, so a sideways subject is a worse detection and
     * not merely a rotated one. The output is therefore already in upright
     * normalised coordinates and nothing downstream rotates anything.
     *
     * [rotationDegrees] is CameraX's clockwise rotation to upright, and
     * `Matrix.postRotate` turns clockwise for a positive angle in the same
     * y-down space, so the value passes through unconverted. This was checked
     * against the other engine on 2026-11-04 rather than reasoned about: with
     * the subject standing still, the knees landed within 0.045 in x and
     * 0.015 in y of where MediaPipe put them.
     */
    private fun run(bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int) {
        val rot = ((rotationDegrees % 360) + 360) % 360
        val swapped = rot == 90 || rot == 270
        val uw = if (swapped) bitmap.height else bitmap.width
        val uh = if (swapped) bitmap.width else bitmap.height

        val box = FrameAdapter.letterbox(uw, uh, SIDE)

        val m = Matrix()
        m.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        m.postRotate(rot.toFloat())
        m.postScale(box.scale, box.scale)
        m.postTranslate(SIDE / 2f, SIDE / 2f)

        canvas.drawColor(Color.BLACK)
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
        onResult(k.map { FrameAdapter.unpad(it, box) }, timestampMs)
    }

    /**
     * Shut down and release the native objects, in that order and not the
     * other way round.
     *
     * The worker holds a frame for the length of one inference, and
     * `shutdown` does not wait for it. Destroying the interpreter while that
     * frame is still inside it tears down a native object the running thread
     * is using, which crashes the process rather than throwing: there is no
     * Kotlin frame to catch it in. The user sees the system's own
     * "keeps stopping" dialog and the session record is never written.
     *
     * So: refuse new work, wait for the one frame that may be in flight, and
     * only then close. The wait is bounded because an inference is tens of
     * milliseconds and a hung one must not block the interface.
     */
    fun close() {
        closed.set(true)
        worker.shutdown()
        runCatching { worker.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) }
        runCatching { interpreter.close() }
        runCatching { gpuDelegate?.close() }
    }

    companion object {
        /** The square input MoveNet Lightning takes, as the exported graph declares it. */
        const val SIDE = 192

        /**
         * How long [close] waits for a frame already inside the interpreter.
         * An inference measured 13 ms median on the target device, so this is
         * generous; it is bounded at all because a hung inference must not
         * hold the interface open.
         */
        const val CLOSE_WAIT_MS = 400L

        /**
         * Committed, unlike the MediaPipe bundles it replaces, because it
         * could not be scripted: the URLs in Google's own documentation
         * return 403 and the weights now need an authenticated account. This
         * is the float export published by Qualcomm AI Hub. Appendix B
         * carries the provenance.
         */
        const val MODEL_ASSET = "movenet.tflite"
    }
}

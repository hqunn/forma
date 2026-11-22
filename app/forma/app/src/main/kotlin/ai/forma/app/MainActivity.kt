package ai.forma.app

import ai.forma.assessment.FaultSpec
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Where the nine screens are chosen between.
 *
 * Two things drive what is shown, and keeping them apart is what stops the
 * navigation tangling with the session. [Stage] is owned by the view model
 * and describes the training path: ready, live, summary. [Place] is owned
 * here and describes everything the user navigates to themselves: home, the
 * exercise list, history, settings.
 *
 * During a set the stage wins, because the set is not something the user can
 * navigate out of by accident.
 */
/** The clip the sound check plays. Any cue would do; this one is short. */
private const val TEST_CUE_ASSET = "cue_squat_chest_en.wav"

private enum class Place { HOME, EXERCISES, HISTORY, DETAIL, FAULT, SETTINGS }

class MainActivity : ComponentActivity() {
    private lateinit var executor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A set lasts minutes and the user never touches the screen during
        // it, which is exactly the condition the display times out under.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        executor = Executors.newSingleThreadExecutor()
        setContent { FormaApp(executor) }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}

@Composable
private fun FormaApp(executor: ExecutorService) {
    val context = LocalContext.current
    val vm: SessionViewModel = viewModel()
    val state by vm.state.collectAsState()

    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    if (!granted) {
        PermissionScreen { ask.launch(Manifest.permission.CAMERA) }
        return
    }

    var place by remember { mutableStateOf(Place.HOME) }
    var useFront by remember { mutableStateOf(true) }
    // Which exercise's guide is open, and whether this trainee has asked
    // not to see it again. Held here rather than in the view model: it is
    // navigation, and it must survive a set ending and another beginning.
    var guideFor by remember { mutableStateOf<ExerciseEntry?>(null) }
    var guideHidden by remember { mutableStateOf(false) }
    var openRecord by remember { mutableStateOf<SetRecord?>(null) }
    // Session detail is reachable from two places now, and back has to go
    // to whichever one it was.
    var detailFrom by remember { mutableStateOf(Place.HISTORY) }
    var openFault by remember { mutableStateOf<FaultSpec?>(null) }

    // Read back when the user navigates to a screen that shows history, so a
    // set written a moment ago appears without restarting the application.
    val entries = remember { Catalog.load(context) }
    var history by remember { mutableStateOf(emptyList<SetRecord>()) }
    fun refresh() { history = SessionStore.all(context) }

    val err = state.error
    if (err != null) {
        ErrorScreen(err) { vm.reset(); place = Place.HOME }
        return
    }

    // The training path owns the screen while a set is in progress.
    //
    // Framing, the live set and the summary are one composable rather than
    // three branches, because the camera is bound by a view inside it. Three
    // branches would mean three call sites, the view would be rebuilt on each
    // transition, and the rebind would land exactly when counting starts.
    if (state.stage != Stage.IDLE) {
        TrainingPath(
            state = state,
            vm = vm,
            executor = executor,
            useFront = useFront,
            entry = remember(state.exerciseName) {
                entries.firstOrNull { it.spec.id == vm.activeSpec?.id }
            },
            guideFor = guideFor,
            guideHidden = guideHidden,
            onGuideHiddenChange = { hide ->
                guideHidden = hide
                guideFor?.let { GuidePrefs.setHidden(context, it.spec.id, hide) }
            },
            onOpenGuide = { guideFor = it },
            onCloseGuide = { guideFor = null },
            onSwapLens = { useFront = !useFront },
            onLeave = { guideFor = null; vm.reset(); place = Place.EXERCISES },
            onRepeat = {
                val id = vm.activeSpec?.id
                vm.reset()
                // No guide on a repeat. They have just done the exercise.
                entries.firstOrNull { it.spec.id == id }?.let { vm.begin(context, it.asset) }
            },
            onFinish = { guideFor = null; vm.reset(); refresh(); place = Place.HOME },
        )
        return
    }

    when (place) {
        Place.HOME -> HomeScreen(
            last = remember(history) { history.firstOrNull() ?: SessionStore.latest(context) },
            onStart = { place = Place.EXERCISES },
            onHistory = { refresh(); place = Place.HISTORY },
            onOpenLast = {
                openRecord = it
                detailFrom = Place.HOME
                place = Place.DETAIL
            },
            onSettings = { place = Place.SETTINGS },
        )

        Place.EXERCISES -> ExerciseScreen(
            entries = entries,
            onBack = { place = Place.HOME },
            onPick = { picked ->
                guideHidden = GuidePrefs.hidden(context, picked.spec.id)
                guideFor = if (guideHidden) null else picked
                vm.begin(context, picked.asset)
            },
        )

        Place.HISTORY -> HistoryScreen(
            records = history,
            onBack = { place = Place.HOME },
            onOpen = {
                openRecord = it
                detailFrom = Place.HISTORY
                place = Place.DETAIL
            },
        )

        Place.DETAIL -> {
            val r = openRecord
            if (r == null) { place = detailFrom } else {
                DetailScreen(
                    record = r,
                    spec = entries.firstOrNull { it.spec.id == r.specId }?.spec
                        ?: entries.firstOrNull { it.displayName == r.exercise }?.spec,
                    backLabel = if (detailFrom == Place.HOME) "Home" else "History",
                    onBack = { place = detailFrom },
                    onFault = { openFault = it; place = Place.FAULT },
                )
            }
        }

        Place.FAULT -> {
            val f = openFault
            val r = openRecord
            if (f == null) { place = Place.DETAIL } else {
                val spec = entries.firstOrNull { it.spec.id == r?.specId }?.spec
                FaultScreen(
                    fault = f,
                    cue = spec?.cues?.get(f.code)?.text,
                    onBack = { place = Place.DETAIL },
                )
            }
        }

        Place.SETTINGS -> SettingsScreen(
            onBack = { place = Place.HOME },
            onTestSound = { vm.playTestCue(context, TEST_CUE_ASSET) },
        )
    }
}

/**
 * Framing, the live set and the summary, with one camera beneath all three.
 *
 * The preview is composed once and stays in the same position in the tree for
 * the whole path. The summary covers it completely, which is correct: the set
 * is over and there is nothing left to frame.
 */
@Composable
private fun TrainingPath(
    state: AppState,
    vm: SessionViewModel,
    executor: ExecutorService,
    useFront: Boolean,
    entry: ExerciseEntry?,
    guideFor: ExerciseEntry?,
    guideHidden: Boolean,
    onGuideHiddenChange: (Boolean) -> Unit,
    onOpenGuide: (ExerciseEntry) -> Unit,
    onCloseGuide: () -> Unit,
    onSwapLens: () -> Unit,
    onLeave: () -> Unit,
    onRepeat: () -> Unit,
    onFinish: () -> Unit,
) {
    val framing = state.stage == Stage.READY
    Box(Modifier.fillMaxSize().background(T.dark)) {
        // One call site for the whole path. During framing it sits where the
        // design puts it; afterwards it is still composed, still bound, and
        // covered by an opaque screen.
        // The preview and the controls above it occupy the top of the Box.
        // Composed once for the whole path, so starting a set does not
        // rebind the camera at the moment counting begins.
        Column(Modifier.fillMaxSize().padding(T.screen).padding(top = T.s5)) {
            if (framing) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackRow("Back", onDark = true, onClick = onLeave)
                    RoundButton(R.drawable.ic_swap_camera, "Switch camera", onDark = true, onClick = onSwapLens)
                }
                Spacer(Modifier.height(T.s3))
            }
            // A camera frame, not a thumbnail. The trainee is checking that
            // their whole body is in shot from several metres away, which a
            // small box cannot show them.
            CameraFeed(
                vm, executor, useFront,
                Modifier.fillMaxWidth().fillMaxHeight(PREVIEW_FRACTION),
            )
        }
        when (state.stage) {
            Stage.READY -> SetupOverlay(
                state = state,
                reminders = vm.activeSpec?.framing?.reminders.orEmpty(),
                onGuide = { entry?.let(onOpenGuide) },
                onStart = { vm.startSet() },
            )
            Stage.SET ->
                LiveScreen(state, vm.activeSpec) { vm.endSet() }
            else ->
                SummaryScreen(state, vm.activeSpec, onRepeat, onFinish)
        }

        // Last in the Box, so it covers the preview and the overlay both.
        // Only before the set: a sheet that could open over a live set
        // would be a way to blind the camera halfway through one.
        if (guideFor != null && state.stage == Stage.READY) {
            GuideSheet(
                entry = guideFor,
                hidden = guideHidden,
                onHiddenChange = onGuideHiddenChange,
                onClose = onCloseGuide,
            )
        }
    }
}

// ---------------------------------------------------------------- camera

@Composable
private fun CameraFeed(
    vm: SessionViewModel,
    executor: ExecutorService,
    useFrontCamera: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Keyed on the lens so that changing it rebuilds the view and rebinds the
    // provider. Without this the factory never runs again and the toggle is a
    // control that changes a colour and nothing else.
    key(useFrontCamera) {
        AndroidView(
            modifier = modifier.clip(T.rM),
            factory = { ctx ->
                val view = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                bindCamera(ctx, view, context as LifecycleOwner, vm, executor, useFrontCamera)
                view
            },
        )
    }
}

private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    vm: SessionViewModel,
    executor: ExecutorService,
    useFrontCamera: Boolean,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(720, 1280),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            // A late correction is worse than a missed one, so a backlog is
            // dropped rather than worked through. 5.5.2 carries the argument.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(executor) { proxy ->
            try {
                // Preparation is the buffer copy and nothing else. The
                // rotation travels with the frame to the engine instead of
                // being applied here, which is the defect 4.2.2 records.
                vm.onFrame(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
            } catch (_: Throwable) {
                // One bad frame is not a reason to end a set.
            } finally {
                proxy.close()
            }
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            // CameraX mirrors the preview for a front lens and leaves the
            // analysis stream alone, so the landmarks should carry the
            // subject's real left and right either way. Should: if it is
            // wrong the symptom is specific, framing never passes on the
            // front camera, because every specification names LEFT_
            // landmarks.
            if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }, androidx.core.content.ContextCompat.getMainExecutor(context))
}

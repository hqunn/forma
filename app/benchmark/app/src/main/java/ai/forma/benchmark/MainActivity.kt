package ai.forma.benchmark

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.concurrent.Executors

private val Ink = Color(0xFF0D1B2A)
private val Paper = Color(0xFFE8EDF2)
private val Muted = Color(0xFF8A99AB)
private val Accent = Color(0xFF4DA3FF)
private val Warn = Color(0xFFE0A33E)

class MainActivity : ComponentActivity() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A ten-minute run must not be interrupted by the screen sleeping.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                BenchmarkScreen(analysisExecutor)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}

@Composable
private fun BenchmarkScreen(executor: java.util.concurrent.ExecutorService) {
    val context = LocalContext.current
    val vm: BenchmarkViewModel = viewModel()
    val state by vm.state.collectAsState()

    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    // Starts on what the product ships rather than on whatever happens to be
    // first in the list, which was lite-cpu: a configuration 4.2 excluded.
    var pick by remember { mutableStateOf(SHIPPED_CONFIG) }
    val busy = state.phase != Phase.IDLE && state.phase != Phase.DONE &&
        state.phase != Phase.ERROR

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "FORMA pose benchmark",
            color = Paper, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(
            "4.2. Six configurations, measured on this device.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
        )

        if (!granted) {
            Text("Camera permission is required.", color = Warn, fontSize = 14.sp)
            Button(
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("Grant camera access") }
            return@Column
        }

        CameraFeed(vm, executor)

        StatusPanel(state)

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { vm.runSweep(context) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.weight(1f),
            ) { Text("Latency sweep") }

            OutlinedButton(
                onClick = { vm.cancel() },
                enabled = busy,
                modifier = Modifier.weight(1f),
            ) { Text("Cancel", color = Paper) }
        }

        Text(
            "Mode A rebuilds the whole engine table: eight configurations across " +
                "two engines, 60 s each after a 5 s warm-up, with a cooldown " +
                "between them. 11 to 28 minutes depending on how hot the phone " +
                "gets. Stopping early keeps every configuration already measured.",
            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
        )

        Text(
            "One configuration",
            color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
        )
        ConfigPicker(selected = pick, enabled = !busy) { pick = it }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { vm.runSingle(context, pick) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.weight(1f),
            ) { Text("60 s on " + pick.label, color = Ink) }

            Button(
                onClick = { vm.runEndurance(context, pick) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Warn),
                modifier = Modifier.weight(1f),
            ) { Text("10 min on " + pick.label, color = Ink) }
        }

        Text(
            "The variant is settled, so checking one configuration should not " +
                "mean running six. 60 s matches a Mode A row exactly and is what " +
                "reproduces a baseline. 10 minutes is Mode B, which answers the " +
                "thermal question: the frame rate in the last bucket, not the mean.",
            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun CameraFeed(vm: BenchmarkViewModel, executor: java.util.concurrent.ExecutorService) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .border(1.dp, Color(0xFF24354A), RoundedCornerShape(6.dp)),
        factory = { ctx ->
            val view = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            bindCamera(ctx, view, context as LifecycleOwner, vm, executor)
            view
        },
    )
}

private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    vm: BenchmarkViewModel,
    executor: java.util.concurrent.ExecutorService,
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
            // Dropping stale frames is the correct behaviour for a live path:
            // a late correction is worse than a missed one (4.4).
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(executor) { proxy ->
            val t0 = SystemClock.uptimeMillis()
            try {
                // Preparation is now the buffer copy and nothing else. The
                // rotation goes to MediaPipe with the frame; see PoseEngine.
                val bitmap = proxy.toBitmap()
                vm.onFrame(bitmap, SystemClock.uptimeMillis() - t0, proxy.imageInfo.rotationDegrees)
            } catch (_: Throwable) {
                // A single bad frame is not a reason to stop a ten-minute run.
            } finally {
                proxy.close()
            }
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }, androidx.core.content.ContextCompat.getMainExecutor(context))
}

@Composable
private fun StatusPanel(state: UiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .background(Color(0xFF13273D), RoundedCornerShape(6.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(state.phase.name, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (state.configTotal > 0) {
                Text(
                    state.configLabel + "  (" + state.configIndex + "/" + state.configTotal + ")",
                    color = Paper, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        Text(
            state.message, color = Muted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("fps", String.format(java.util.Locale.US, "%.1f", state.liveFps))
            Stat("infer", state.lastInferMs.toString() + " ms")
            Stat("frames", state.frames.toString())
            Stat("thermal", state.thermal.toString())
            Stat("batt", String.format(java.util.Locale.US, "%.1f", state.batteryC) + "C")
        }

        if (state.poseRate >= 0.0) {
            val ok = state.poseRate >= 0.95
            Text(
                "pose " + String.format(java.util.Locale.US, "%.3f", state.poseRate) +
                    (if (ok) "   a pose was found in every frame"
                     else "   LOW: check the framing, and the camera rotation"),
                color = if (ok) Accent else Warn,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        if (state.phaseRemainingS > 0) {
            Text(
                state.phaseRemainingS.toString() + " s remaining in this phase",
                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
        state.outputPath?.let {
            Text(
                "CSV written to:\n" + it,
                color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Paper, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ConfigPicker(selected: Config, enabled: Boolean, onPick: (Config) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ALL_CONFIGS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { config ->
                    val on = config == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .background(
                                if (on) Accent else Color(0xFF13273D),
                                RoundedCornerShape(4.dp),
                            )
                            .clickable(enabled = enabled) { onPick(config) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            config.label,
                            color = if (on) Ink else Paper,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

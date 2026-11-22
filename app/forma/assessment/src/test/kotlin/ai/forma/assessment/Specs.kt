package ai.forma.assessment

/**
 * Loads the real specification files from docs/specs.
 *
 * Not copies. The module's test resources point at the specification
 * directory itself, so a specification edit that breaks a validation rule
 * fails this build. That is the only arrangement in which the rules listed in
 * SCHEMA.md stay true of the files on disk rather than true of a snapshot
 * taken when the test was written.
 */
object Specs {

    fun text(name: String): String =
        Specs::class.java.getResource("/$name")?.readText()
            ?: error("specification '$name' not on the test classpath")

    fun squat() = SpecLoader.load(text("bodyweight-squat.json"))
    fun pushUp() = SpecLoader.load(text("push-up.json"))
    fun plank() = SpecLoader.load(text("plank.json"))

    fun all() = listOf(squat(), pushUp(), plank())

    fun guidance() = GuidanceLoader.load(text("guidance.json"))

    /**
     * A specification that still calibrates.
     *
     * None of the three MVP files does any more: all of their thresholds
     * became absolute on 2026-11-04 when the depth criterion moved to the
     * published one and the calibration step was removed from the product.
     * The format still supports calibration, the loader still enforces the
     * five rules that govern it, and [CalibrationRunner] still exists, so
     * the machinery needs something to be tested against. This is that
     * something, and it lives here rather than in docs/specs because the
     * application's assets are taken from that directory and a test fixture
     * must not reach a handset.
     */
    fun calibratedText(): String = """
    {
      "id": "calibrated-example",
      "version": "1.0.0",
      "display_name": "Calibrated example",
      "status": "draft",
      "landmarks": [
        "LEFT_SHOULDER", "RIGHT_SHOULDER",
        "LEFT_HIP", "RIGHT_HIP",
        "LEFT_KNEE", "RIGHT_KNEE",
        "LEFT_ANKLE", "RIGHT_ANKLE"
      ],
      "rep_driver": {
        "signal": { "fn": "angle", "args": ["LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE"], "aggregate": "mean_of_sides" },
        "reports": { "type": "count" },
        "direction": "decreasing_is_descent",
        "smoothing": { "type": "median", "window_frames": 3 },
        "hysteresis": {
          "descent_enter": { "type": "relative", "calibration_id": "standing_knee_angle", "offset": -20.0 },
          "ascent_exit": { "type": "relative", "calibration_id": "standing_knee_angle", "offset": -12.0 }
        },
        "min_rep_ms": 800,
        "max_rep_ms": 8000
      },
      "phases": [
        { "name": "standing", "entered_when": "driver >= ascent_exit" },
        { "name": "descent", "entered_when": "driver < descent_enter and driver decreasing" },
        { "name": "bottom", "entered_when": "driver at local minimum, within 10 degrees of it" },
        { "name": "ascent", "entered_when": "driver increasing and below ascent_exit" }
      ],
      "calibration": {
        "id": "example_range",
        "safe_unsupervised": true,
        "rationale": "Both holds are static positions the user chooses for themselves.",
        "measures": [
          {
            "id": "standing_knee_angle",
            "instruction": "Stand up straight and hold still.",
            "instruction_asset": "say_squat_standing_knee_angle_en.wav",
            "signal": { "fn": "angle", "args": ["LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE"], "aggregate": "mean_of_sides" },
            "statistic": "median",
            "hold_ms": 1500,
            "stability_tolerance": 4.0,
            "plausible_range": [155.0, 185.0]
          },
          {
            "id": "max_squat_depth_knee_angle",
            "instruction": "Squat down as far as is comfortable, and hold there.",
            "instruction_asset": "say_squat_max_squat_depth_knee_angle_en.wav",
            "signal": { "fn": "angle", "args": ["LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE"], "aggregate": "mean_of_sides" },
            "statistic": "min_sustained",
            "hold_ms": 1500,
            "stability_tolerance": 6.0,
            "plausible_range": [45.0, 140.0]
          }
        ],
        "on_implausible": "reject_and_repeat"
      },
      "faults": [
        {
          "code": "SQ_DEPTH_INSUFFICIENT",
          "description": "Shallower than the depth this user demonstrated.",
          "coaching": { "fix": "A fixture. Sit back further and go slower." },
          "phases": ["bottom"],
          "measure": { "fn": "angle", "args": ["LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE"], "aggregate": "mean_of_sides" },
          "operator": "gt",
          "threshold": { "type": "relative", "calibration_id": "max_squat_depth_knee_angle", "factor": 1.15 },
          "min_frames": 2,
          "provenance": {
            "status": "taxonomy_confirmed",
            "taxonomy_source": "Cook G, Burton L, Hoogenboom B, Part 1, as in the squat.",
            "plain_source": "A fixture, standing in for the Functional Movement Screen.",
            "threshold_source": null,
            "note": "A fixture. Not a claim about any exercise."
          }
        }
      ],
      "cues": {
        "SQ_DEPTH_INSUFFICIENT": { "en": { "asset": "cue_squat_depth_en.wav", "text": "A little deeper." } }
      },
      "trigger": {
        "confidence_floor": 0.6,
        "debounce_ms": 400,
        "min_gap_ms": 2500,
        "max_cues_per_set": 6,
        "max_consecutive_same_cue": 2,
        "priority": ["SQ_DEPTH_INSUFFICIENT"],
        "silence_is_valid_output": true
      },
      "framing": {
        "view": "sagittal",
        "distance_m": [2.0, 4.0],
        "camera_height_m": [0.5, 1.2],
        "min_visibility": 0.6,
        "required_visible": ["LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE", "LEFT_SHOULDER"],
        "guidance": "Turn side on to the camera.",
        "guidance_asset": "say_squat_framing_en.wav",
        "reminders": ["Please keep your whole body in frame"]
      }
    }
    """.trimIndent()

    fun calibrated() = SpecLoader.load(calibratedText())

    /** A plausible calibration for [calibrated], used where a set must start. */
    val exampleCalibration = mapOf(
        "standing_knee_angle" to 175.0,
        "max_squat_depth_knee_angle" to 70.0,
    )
}

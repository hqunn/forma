package ai.forma.app

import ai.forma.assessment.ExerciseSpec
import ai.forma.assessment.FaultSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The nine screens, laid out as the design file lays them out.
 *
 * Two rules run through all of them and are worth stating once. Colour means
 * state and nothing else, so nothing is tinted for decoration. And no screen
 * shows a number the system did not measure: there is no score, no streak and
 * no percentage anywhere in this file.
 */

private fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

/** `92.4` seconds as `1:32`. Empty where no duration was recorded. */
fun clockLength(seconds: Double): String {
    if (seconds <= 0.0) return ""
    val t = seconds.toInt()
    return "%d:%02d".format(t / 60, t % 60)
}

/** Shown on the settings screen, and nowhere a trainee has to read it. */
const val APP_VERSION = "1.0"

/** A light screen with the standard gutter and a scroll. */
@Composable
private fun Page(
    dark: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(if (dark) T.dark else T.base)
            .verticalScroll(rememberScrollState())
            .padding(T.screen)
            .padding(top = T.s6, bottom = T.s6),
        verticalArrangement = Arrangement.spacedBy(T.s4),
        content = content,
    )
}

// ---------------------------------------------------------------- S1 Home

@Composable
fun HomeScreen(
    last: SetRecord?,
    onStart: () -> Unit,
    onHistory: () -> Unit,
    onOpenLast: (SetRecord) -> Unit,
    onSettings: () -> Unit,
) = Page {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_forma_mark),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
            )
            Text(
                "FORMA", color = T.ink900, fontSize = Type.title,
                fontWeight = Type.bold, letterSpacing = 3.sp,
                modifier = Modifier.padding(start = T.s3),
            )
        }
        RoundButton(R.drawable.ic_settings, "Settings", onClick = onSettings)
    }
    Spacer(Modifier.padding(top = T.s5))
    Text(
        "Ready when you are.",
        color = T.ink900, fontSize = Type.title, fontWeight = Type.bold, lineHeight = 40.sp,
    )
    Text(
        "Place the phone two to four metres away, side on, and put your headphones in.",
        color = T.ink500, fontSize = Type.bodySm, lineHeight = 24.sp,
    )
    Spacer(Modifier.padding(top = T.s3))
    PrimaryButton("Start a set", onClick = onStart)
    SecondaryButton("History", onClick = onHistory)

    if (last != null) {
        Spacer(Modifier.padding(top = T.s4))
        SectionLabel("Last session")
        // The card opens the set it describes. Somebody who has just
        // finished training and picked the phone back up is far more
        // likely to want this one set than the list of every set, and
        // routing them through History made the common case the long way
        // round for no reason beyond the list existing.
        Card(onClick = { onOpenLast(last) }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(T.s1)) {
                    Text(last.exercise, color = T.ink900,
                        fontSize = Type.body, fontWeight = Type.semi)
                    Text(
                        prettyDay(last.day) + ", " + last.clock + "  ·  " + last.result,
                        color = T.ink500, fontSize = Type.bodySm,
                    )
                    Text(
                        if (last.cuesSpoken == 0) "no cues spoken"
                        else last.cuesSpoken.toString() + " cues spoken",
                        color = if (last.cuesSpoken == 0) T.ok else T.fault,
                        fontSize = Type.bodySm,
                    )
                }
                Text("›", color = T.ink300, fontSize = Type.heading)
            }
        }
    }
}

// ------------------------------------------------- S2 Exercise selection

@Composable
fun ExerciseScreen(
    entries: List<ExerciseEntry>,
    onBack: () -> Unit,
    onPick: (ExerciseEntry) -> Unit,
) = Page {
    BackRow("Home", onClick = onBack)
    Text("Choose an exercise", color = T.ink900,
        fontSize = Type.title, fontWeight = Type.bold)
    Spacer(Modifier.padding(top = T.s1))

    for (e in entries) {
        Card(onClick = { onPick(e) }) {
            Column(verticalArrangement = Arrangement.spacedBy(T.s2)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(e.displayName, color = T.ink900,
                        fontSize = Type.body, fontWeight = Type.semi)
                    Tag(e.kind, fill = T.base)
                }
                Text(
                    e.checks,
                    color = T.ink500, fontSize = Type.bodySm,
                )
            }
        }
    }

}

// ----------------------------------------------------------- S3 Set-up

/**
 * Everything on the set-up screen except the preview itself.
 *
 * The preview is composed by the caller, once for the whole training path,
 * and this draws below it. Keeping the two apart is what stops the camera
 * being rebound when the set starts.
 *
 * **This screen advises; it does not refuse.** It used to hold a pass-fail
 * checklist and keep the start control disabled until both lines went
 * green. That is wrong twice over. It reads as the product deciding whether
 * the trainee is allowed to train, and the check itself is nearly
 * worthless: the trainee has to walk back to the phone to press start, so
 * the framing being graded is the framing of somebody standing next to the
 * camera rather than where they will be. The conditions are now shown as
 * requests and starting is always one tap.
 */
@Composable
fun SetupOverlay(
    state: AppState,
    reminders: List<String>,
    onGuide: () -> Unit,
    onStart: () -> Unit,
) {
    // Anchored to the bottom rather than offset from the top. The preview
    // occupies the top of the same Box; letting this column grow upward from
    // the bottom edge means neither has to know the other's height.
    Column(
        Modifier.fillMaxSize().padding(T.screen).padding(bottom = T.s6),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (state.framingOk) T.ok else T.warn, T.rS)
                .padding(T.s4),
        ) {
            // Amber, not red. The trainee walking into position has not
            // failed at anything; they are being asked to move.
            Text(
                if (state.framingOk) "You are in frame" else state.message,
                color = T.ink900, fontSize = Type.subhead, fontWeight = Type.semi,
            )
        }

        Spacer(Modifier.height(T.s4))
        Column(verticalArrangement = Arrangement.spacedBy(T.s2)) {
            for (line in reminders) BulletLine(line, onDark = true)
        }

        Spacer(Modifier.height(T.s5))
        SecondaryButton("How to do this", onDark = true, onClick = onGuide)
        Spacer(Modifier.height(T.s3))
        PrimaryButton("Start set", onClick = onStart)
    }
}

/**
 * What a good repetition looks like, shown before the set.
 *
 * Opened automatically the first time an exercise is chosen and from the
 * set-up screen after that. The trainee can switch it off per exercise,
 * because somebody who knows the squat may still want the plank explained.
 *
 * Everything on it comes from the specification: the setting-up sentence,
 * the reminders, and one entry per fault headed by the cue they would
 * actually hear. Adding a fault to a file adds a line here.
 */
@Composable
fun GuideSheet(
    entry: ExerciseEntry,
    hidden: Boolean,
    onHiddenChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(T.dark)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(T.screen)
                .padding(top = T.s6, bottom = T.s6),
            verticalArrangement = Arrangement.spacedBy(T.s4),
        ) {
            Text(
                entry.displayName,
                color = T.base, fontSize = Type.heading, fontWeight = Type.bold,
            )

            Column(verticalArrangement = Arrangement.spacedBy(T.s2)) {
                for (fig in listOfNotNull(entry.figure, entry.figureSecond)) {
                    Image(
                        painter = painterResource(fig),
                        contentDescription = "A photograph of a correct " +
                            entry.displayName.lowercase(),
                        // Crop rather than Fit: every picture is cut to four
                        // by three before it ships, so the box is the shape
                        // they already are and nothing is letterboxed.
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(T.rM),
                    )
                }
                entry.figureCredit?.let {
                    Text(it, color = T.ink500, fontSize = Type.caption)
                }
            }

            Card {
                Column(verticalArrangement = Arrangement.spacedBy(T.s3)) {
                    SectionLabel("Setting up", T.teal)
                    Text(
                        entry.spec.framing.guidance,
                        color = T.ink700, fontSize = Type.bodySm, lineHeight = 24.sp,
                    )
                    for (line in entry.spec.framing.reminders) BulletLine(line)
                }
            }

            Card {
                Column(verticalArrangement = Arrangement.spacedBy(T.s4)) {
                    SectionLabel("Doing it well", T.teal)
                    for (f in entry.spec.faults) {
                        Column(verticalArrangement = Arrangement.spacedBy(T.s2)) {
                            Text(
                                entry.spec.cues[f.code]?.text?.trimEnd('.')
                                    ?: prettyFault(f.code),
                                color = T.ink900, fontSize = Type.body,
                                fontWeight = Type.semi,
                            )
                            Text(
                                f.fix,
                                color = T.ink700, fontSize = Type.bodySm,
                                lineHeight = 24.sp,
                            )
                        }
                    }
                }
            }

            Card {
                CheckBoxRow(hidden, "Do not show this again", onHiddenChange)
            }

            PrimaryButton("Got it", onClick = onClose)
        }
    }
}

/**
 * How much of the screen the camera frame takes.
 *
 * A fraction rather than a height, so it is a camera frame on every handset
 * rather than a correct one on this handset. The overlay below grows upward
 * from the bottom edge, so the two cannot collide whatever the screen.
 */
val PREVIEW_FRACTION = 0.52f


// ----------------------------------------------------- S4 Live session

@Composable
fun LiveScreen(state: AppState, spec: ExerciseSpec?, onEnd: () -> Unit) {
    val holding = spec?.repDriver?.reports?.isDuration == true
    Column(
        Modifier
            .fillMaxSize()
            .background(T.dark)
            .padding(T.screen)
            .padding(top = T.s5, bottom = T.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            state.exerciseName.uppercase(),
            color = T.tealBright, fontSize = Type.label,
            fontWeight = Type.semi, letterSpacing = 1.sp,
        )

        Spacer(Modifier.weight(1f))

        Text(
            if (holding) String.format(java.util.Locale.US, "%.0f", state.holdMs / 1000.0)
            else state.repCount.toString(),
            color = T.base, fontSize = Type.display, fontWeight = Type.bold,
        )
        Text(
            if (holding) "seconds held" else "repetitions",
            color = T.ink300, fontSize = Type.body,
        )

        Spacer(Modifier.padding(top = T.s5))
        val tracking = state.poseRate > 0.5
        Row(
            Modifier
                .background(if (tracking) T.darkRaised else T.warn, T.pill)
                .padding(horizontal = T.s4, vertical = T.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(if (tracking) T.ok else T.ink900)
            Text(
                if (tracking) "Tracking" else "Not tracking",
                color = if (tracking) T.base else T.ink900,
                fontSize = Type.bodySm,
                modifier = Modifier.padding(start = T.s2),
            )
        }

        if (state.lastCueText != null) {
            Spacer(Modifier.padding(top = T.s4))
            Text(
                "“" + state.lastCueText + "”",
                color = T.fault, fontSize = Type.subhead, fontWeight = Type.semi,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.weight(1f))
        SecondaryButton("End set", onDark = true, onClick = onEnd)
    }
}

// ------------------------------------------------------- S5 Set summary

@Composable
fun SummaryScreen(
    state: AppState,
    spec: ExerciseSpec?,
    onRepeat: () -> Unit,
    onFinish: () -> Unit,
) {
    val holding = spec?.repDriver?.reports?.isDuration == true
    // The two controls are pinned to the bottom edge and the result scrolls
    // above them. Letting the whole screen scroll put them wherever the
    // content happened to end, which on a short set is the middle of the
    // display and nowhere near a thumb.
    Column(
        Modifier
            .fillMaxSize()
            .background(T.dark)
            .padding(T.screen)
            .padding(top = T.s5, bottom = T.s6),
    ) {
      Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(T.s4),
      ) {
        SectionLabel("Set complete", T.tealBright)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (holding) String.format(java.util.Locale.US, "%.0f", state.holdMs / 1000.0)
                else state.repCount.toString(),
                color = T.base, fontSize = Type.display, fontWeight = Type.bold,
            )
            Column(Modifier.padding(start = T.s4, bottom = T.s2)) {
                Text(if (holding) "seconds held" else "repetitions",
                    color = T.base, fontSize = Type.body)
                Text(
                    if (state.durationS > 0.0)
                        clockLength(state.durationS) + "  ·  " + state.exerciseName
                    else state.exerciseName,
                    color = T.ink300, fontSize = Type.bodySm,
                )
            }
        }

        Spacer(Modifier.padding(top = T.s2))
        SectionLabel("Cues spoken", T.ink300)
        if (state.cuesSpoken == 0) {
            Card(fill = T.darkRaised) {
                Text(
                    "Nothing to correct.",
                    color = T.base, fontSize = Type.bodySm,
                )
            }
        } else {
            val counts = state.latencies.groupingBy { it.code }.eachCount()
            for ((code, n) in counts) {
                val text = spec?.cues?.get(code)?.text ?: code
                Card(fill = T.darkRaised) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text.trimEnd('.'), color = T.base,
                            fontSize = Type.body, fontWeight = Type.semi)
                        Text("×" + n, color = T.fault,
                            fontSize = Type.body, fontWeight = Type.semi)
                    }
                }
            }
        }

        if (state.repsWithFault > state.cuesSpoken) {
            NoteBox(
                "Noticed, not spoken",
                state.repsWithFault.toString() + " repetitions had something to correct. " +
                    "You were told " + state.cuesSpoken + " times, so the set stayed " +
                    "quiet enough to train in.",
                accent = T.warn,
            )
        }

      }

        Spacer(Modifier.height(T.s4))
        PrimaryButton("Repeat set", onClick = onRepeat)
        SecondaryButton("Finish", onDark = true, onClick = onFinish)
    }
}

// --------------------------------------------------- S6 Session history

@Composable
fun HistoryScreen(
    records: List<SetRecord>,
    onBack: () -> Unit,
    onOpen: (SetRecord) -> Unit,
) = Page {
    BackRow("Home", onClick = onBack)
    Text("History", color = T.ink900, fontSize = Type.title, fontWeight = Type.bold)

    if (records.isEmpty()) {
        Card {
            Text(
                "Nothing here yet. Your first set appears once you finish it.",
                color = T.ink500, fontSize = Type.bodySm,
            )
        }
        return@Page
    }

    var lastDay = ""
    for (r in records) {
        if (r.day != lastDay) {
            Spacer(Modifier.padding(top = T.s2))
            SectionLabel(prettyDay(r.day))
            lastDay = r.day
        }
        Card(onClick = { onOpen(r) }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(T.s1)) {
                    Text(r.exercise, color = T.ink900,
                        fontSize = Type.body, fontWeight = Type.semi)
                    Text(
                        listOf(r.clock, r.result, r.clockLength)
                            .filter { it.isNotEmpty() }
                            .joinToString("  ·  "),
                        color = T.ink500, fontSize = Type.bodySm,
                    )
                }
                Text(
                    if (r.cuesSpoken == 0) "0 cues" else r.cuesSpoken.toString() + " cues",
                    color = if (r.cuesSpoken == 0) T.ink300 else T.fault,
                    fontSize = Type.bodySm,
                )
            }
        }
    }
}

/**
 * `20261122` as a person would say it: today, yesterday, or the date.
 *
 * A history list is read by somebody placing a set in their own week, and
 * 22/11/2026 makes them do the arithmetic. The year appears only when it is
 * not the current one, because that is the only time it carries information.
 */
private fun prettyDay(d: String): String {
    if (d.length != 8) return d
    val day = try {
        java.time.LocalDate.of(
            d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt(),
        )
    } catch (_: Exception) {
        return d
    }
    val today = java.time.LocalDate.now()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> day.dayOfMonth.toString() + " " + monthName(day.monthValue)
        else -> day.dayOfMonth.toString() + " " + monthName(day.monthValue) + " " + day.year
    }
}

private fun monthName(m: Int) = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
).getOrElse(m - 1) { m.toString() }

// ---------------------------------------------------- S7 Session detail

@Composable
fun DetailScreen(
    record: SetRecord,
    spec: ExerciseSpec?,
    /** Where back goes, which is wherever they came in from. */
    backLabel: String,
    onBack: () -> Unit,
    onFault: (FaultSpec) -> Unit,
) = Page {
    BackRow(backLabel, onClick = onBack)
    Text(record.exercise, color = T.ink900, fontSize = Type.heading, fontWeight = Type.bold)
    Text(prettyDay(record.day) + ", " + record.clock,
        color = T.ink500, fontSize = Type.bodySm)

    val depth = spec?.faults?.firstOrNull { it.code.endsWith("DEPTH_INSUFFICIENT") }
    if (depth != null) {
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(T.s2)) {
                SectionLabel("How depth is judged", T.teal)
                Text(
                    "Femur below horizontal  ·  hip at or under the knee",
                    color = T.ink900, fontSize = Type.bodySm,
                )
                Text(
                    "The same standard for everyone. Nothing to set up.",
                    color = T.ink500, fontSize = Type.caption, lineHeight = 18.sp,
                )
            }
        }
    }

    Spacer(Modifier.padding(top = T.s2))
    SectionLabel("This set")
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(T.s3)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    listOf(record.result, record.clockLength)
                        .filter { it.isNotEmpty() }
                        .joinToString("  ·  "),
                    color = T.ink900, fontSize = Type.body, fontWeight = Type.semi,
                )
                Text(
                    if (record.cuesSpoken == 0) "0 cues"
                    else record.cuesSpoken.toString() + " cues",
                    color = if (record.cuesSpoken == 0) T.ink300 else T.fault,
                    fontSize = Type.bodySm,
                )
            }
            // A hold has no repetitions, so nothing lands in the per-rep
            // breakdown. What it does have is the corrections it spoke, and
            // that is the honest count to show for it.
            val counts =
                if (record.faultCounts.isNotEmpty()) record.faultCounts
                else record.cueCounts
            if (counts.isEmpty()) {
                Text("Nothing needed correcting.",
                    color = T.ok, fontSize = Type.bodySm)
            } else {
                for ((code, n) in counts) {
                    val f = spec?.faults?.firstOrNull { it.code == code }
                    val label = spec?.cues?.get(code)?.text?.trimEnd('.') ?: code
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            Modifier.background(T.base, T.rS)
                                .padding(horizontal = T.s3, vertical = T.s2)
                        ) {
                            Text(
                                label + "  ×" + n,
                                color = if (f != null) T.teal else T.ink500,
                                fontSize = Type.caption,
                            )
                        }
                        if (f != null) {
                            Text("why ›", color = T.teal, fontSize = Type.caption,
                                modifier = Modifier
                                    .padding(start = T.s2)
                                    .then(Modifier)
                                    .let { it }
                                    .clickableText { onFault(f) })
                        }
                    }
                }
            }
        }
    }

}

// ------------------------------------------------------ S8 Fault detail

@Composable
fun FaultScreen(fault: FaultSpec, cue: String?, onBack: () -> Unit) = Page {
    BackRow("Set", onClick = onBack)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            prettyFault(fault.code),
            color = T.ink900, fontSize = Type.heading, fontWeight = Type.bold,
        )
    }

    if (cue != null) {
        Box(Modifier.fillMaxWidth().background(T.fault, T.rM).padding(T.s4)) {
            Column(verticalArrangement = Arrangement.spacedBy(T.s2)) {
                SectionLabel("What you heard", T.base)
                Text("“" + cue.trimEnd('.') + "”", color = T.base,
                    fontSize = Type.subhead, fontWeight = Type.semi)
            }
        }
    }

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(T.s2)) {
            SectionLabel("What it means", T.ink500)
            Text(fault.description, color = T.ink700,
                fontSize = Type.bodySm, lineHeight = 24.sp)
        }
    }

    Card(border = T.ok, fill = T.base) {
        Column(verticalArrangement = Arrangement.spacedBy(T.s2)) {
            SectionLabel("What to try next time", T.ok)
            Text(fault.fix, color = T.ink700,
                fontSize = Type.bodySm, lineHeight = 24.sp)
        }
    }

    // The citation itself is not shown. It is a journal reference carrying
    // authors, a volume and a quoted criterion, written for the report, and
    // this card used to print its first 120 characters and stop wherever
    // that landed: for the squat, three surnames; for the plank, a clause
    // ending in "and". plain_source is the same claim in a sentence.
    Card(border = T.teal, fill = T.base) {
        Column(verticalArrangement = Arrangement.spacedBy(T.s2)) {
            SectionLabel("Where this comes from", T.teal)
            Text(
                fault.provenance.plainSource.orEmpty(),
                color = T.ink900, fontSize = Type.bodySm, lineHeight = 24.sp,
            )
            Text(
                "Defined by qualified trainers, not by this app.",
                color = T.ink500, fontSize = Type.caption, lineHeight = 18.sp,
            )
        }
    }

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(T.s2)) {
            SectionLabel("How it is measured", T.ink500)
            Text(measuredAs(fault), color = T.ink700,
                fontSize = Type.bodySm, lineHeight = 24.sp)
        }
    }

}

internal fun prettyFault(code: String): String = when {
    code.endsWith("DEPTH_INSUFFICIENT") -> "Not deep enough"
    code.endsWith("FORWARD_LEAN") -> "Forward lean"
    code.endsWith("HIP_SAG") -> "Hips dropped"
    code.endsWith("HIP_PIKE") -> "Hips raised"
    else -> code
}

private fun measuredAs(f: FaultSpec): String {
    val where = if (f.phases.size >= 4) "throughout the movement"
    else "during the " + f.phases.joinToString(" and the ")
    val sure = when {
        f.minFrames >= 22 -> ", and only once it has held for about a second"
        f.minFrames >= 9 -> ", and only once it has held rather than flickered"
        else -> ", confirmed over several readings so one bad one cannot trigger it"
    }
    return "Watched for " + where + sure + "."
}

// ---------------------------------------------------------- S9 Settings

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onTestSound: () -> Unit,
) = Page {
    BackRow("Home", onClick = onBack)
    Text("Settings", color = T.ink900, fontSize = Type.title, fontWeight = Type.bold)

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(T.s3)) {
            SectionLabel("Sound")
            Text(
                "FORMA corrects you out loud. Check you can hear it before you " +
                    "start, with your headphones in and the volume where you want it.",
                color = T.ink500, fontSize = Type.bodySm, lineHeight = 22.sp,
            )
            PrimaryButton("Play a test cue", onClick = onTestSound)
        }
    }

    Spacer(Modifier.padding(top = T.s2))
    Footnote("FORMA " + APP_VERSION)
}


/** Shown instead of a screen when the camera permission has not been given. */
@Composable
fun PermissionScreen(onAsk: () -> Unit) = Page {
    Spacer(Modifier.padding(top = T.s7))
    Text("FORMA needs the camera", color = T.ink900,
        fontSize = Type.title, fontWeight = Type.bold, lineHeight = 40.sp)
    Text(
        "FORMA watches you train through the camera. Nothing is recorded and " +
            "nothing leaves your phone.",
        color = T.ink500, fontSize = Type.bodySm, lineHeight = 24.sp,
    )
    Spacer(Modifier.padding(top = T.s3))
    PrimaryButton("Allow the camera", onClick = onAsk)
}

/** Shown when a specification will not load, instead of a half-built screen. */
@Composable
fun ErrorScreen(message: String, onBack: () -> Unit) = Page {
    BackRow("Home", onClick = onBack)
    Text("That did not load", color = T.ink900,
        fontSize = Type.title, fontWeight = Type.bold)
    Card(border = T.fault, fill = T.base) {
        Text(message, color = T.ink700, fontSize = Type.bodySm, lineHeight = 22.sp)
    }
}


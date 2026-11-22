package ai.forma.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The handful of shapes every screen is built from.
 *
 * Kept in one file so that a card on the history screen and a card on the
 * summary screen cannot drift apart. The design file enforces this with
 * bound tokens; here it is enforced by there being one definition.
 */

/** A small upper-case label above a group. */
@Composable
fun SectionLabel(text: String, color: Color = T.ink500) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = Type.label,
        fontWeight = Type.semi,
        letterSpacing = 1.sp,
    )
}

/** The default container: a tinted block with generous padding. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    fill: Color = T.tint,
    shape: Shape = T.rM,
    border: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var m = modifier.fillMaxWidth().background(fill, shape)
    if (border != null) m = m.border(1.dp, border, shape)
    if (onClick != null) m = m.clickable { onClick() }
    Box(m.padding(T.s4)) { content() }
}

/** The one action a screen most wants the user to take. */
@Composable
fun PrimaryButton(
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (enabled) T.teal else T.ink100
    val fg = if (enabled) T.base else T.ink300
    Box(
        modifier
            .fillMaxWidth()
            .background(bg, T.rS)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = T.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = Type.body, fontWeight = Type.semi)
    }
}

/** The alternative action, outlined rather than filled. */
@Composable
fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
    onClick: () -> Unit,
) {
    val line = if (onDark) T.ink500 else T.ink100
    val fg = if (onDark) T.base else T.ink900
    Box(
        modifier
            .fillMaxWidth()
            .border(1.dp, line, T.rS)
            .clickable { onClick() }
            .padding(vertical = T.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = Type.body, fontWeight = Type.semi)
    }
}

/** A state marker: a dot and a word, which is the whole vocabulary. */
@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(color, T.pill))
}

/**
 * One line of advice.
 *
 * Deliberately not [CheckLine]: a bullet states a condition, a dot grades
 * it. Framing is advice now, and advice that turns red while the trainee is
 * still walking into position reads as failure rather than help.
 */
@Composable
fun BulletLine(text: String, onDark: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "·",
            color = if (onDark) T.tealBright else T.teal,
            fontSize = Type.body, fontWeight = Type.bold,
        )
        Text(
            text,
            // Muted on dark. White here competes with the correction box
            // above it, and these lines are the standing conditions rather
            // than the thing to read first.
            color = if (onDark) T.ink300 else T.ink700,
            fontSize = Type.bodySm,
            lineHeight = 22.sp,
            modifier = Modifier.padding(start = T.s3),
        )
    }
}

/**
 * A box the trainee ticks, with the whole row as the target.
 *
 * The box itself is 24dp, which is below the tap floor, so the row carries
 * the click and is padded out to [TAP].
 */
@Composable
fun CheckBoxRow(checked: Boolean, label: String, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TAP)
            .clickable(onClickLabel = label) { onToggle(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(T.rS)
                .background(if (checked) T.teal else T.tint)
                .border(2.dp, if (checked) T.teal else T.ink300, T.rS),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", color = T.base, fontSize = Type.bodySm,
                    fontWeight = Type.bold)
            }
        }
        Text(
            label,
            color = T.ink700, fontSize = Type.bodySm,
            modifier = Modifier.padding(start = T.s3),
        )
    }
}

/** One line of a checklist, with its state carried by the dot. */
@Composable
fun CheckLine(ok: Boolean, text: String, onDark: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (ok) T.ok else T.warn)
        Text(
            text,
            color = if (onDark) T.base else T.ink700,
            fontSize = Type.bodySm,
            modifier = Modifier.padding(start = T.s3),
        )
    }
}

/**
 * A qualification attached to a number. Bordered in the colour of the state
 * it qualifies, because a caveat set in body text is a caveat nobody reads.
 */
@Composable
fun NoteBox(title: String, body: String, accent: Color = T.warn) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, accent, T.rS)
            .padding(T.s4),
        verticalArrangement = Arrangement.spacedBy(T.s2),
    ) {
        Text(title, color = accent, fontSize = Type.body, fontWeight = Type.semi)
        Text(body, color = T.ink500, fontSize = Type.caption, lineHeight = 18.sp)
    }
}

/** A small rounded tag, for a code or a category. */
@Composable
fun Tag(text: String, fill: Color = T.tint, fg: Color = T.ink500) {
    Box(Modifier.background(fill, T.rS).padding(horizontal = T.s3, vertical = 6.dp)) {
        Text(text, color = fg, fontSize = Type.caption, fontWeight = Type.medium)
    }
}

/** The footnote at the bottom of a screen, saying what it does not claim. */
@Composable
fun Footnote(text: String, onDark: Boolean = false) {
    Text(
        text,
        color = if (onDark) T.ink500 else T.ink300,
        fontSize = Type.caption,
        lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A back affordance.
 *
 * Sized to [TAP], not to its text. The user is often standing, sometimes out
 * of breath, and a control that is only as big as the word on it is a control
 * they have to aim at.
 */
@Composable
fun BackRow(label: String, onDark: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = TAP)
            .clip(T.rS)
            .clickable { onClick() }
            .padding(end = T.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹",
            color = if (onDark) T.base else T.ink700,
            fontSize = Type.heading,
            fontWeight = Type.regular,
        )
        Text(
            label,
            color = if (onDark) T.ink300 else T.ink500,
            fontSize = Type.body,
            fontWeight = Type.medium,
            modifier = Modifier.padding(start = T.s3),
        )
    }
}

/**
 * The smallest a control may be before it stops being reliably tappable.
 *
 * The platform's own guidance is 48dp. Every control in this interface is at
 * least that in both directions, which is the floor rather than the target:
 * the trainee is standing, often several metres from where they set the phone
 * down, and frequently out of breath when they pick it up again.
 */
val TAP = 56.dp

/**
 * A circular control, sized for a thumb.
 *
 * Takes a drawable rather than a character. A glyph the device's font does
 * not carry renders as an empty box, and these controls have no text beside
 * them to fall back on.
 */
@Composable
fun RoundButton(
    @DrawableRes icon: Int,
    description: String,
    onDark: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(TAP)
            .background(if (onDark) T.darkRaised else T.tint, T.pill)
            .clickable(onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = if (onDark) T.base else T.ink700,
            modifier = Modifier.size(26.dp),
        )
    }
}

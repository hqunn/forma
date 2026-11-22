package ai.forma.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design tokens from the Figma file, transcribed rather than reinvented.
 *
 * The prototype binds every colour, space and radius to a variable instead of
 * typing a literal into a shape, so the values below are the file's own. A
 * colour that is not in this list does not appear in the design, and adding
 * one here without adding it there puts the two out of step, which is the
 * failure this file exists to prevent.
 *
 * Collection `FORMA`, read 2026-11-05.
 */
object T {
    // ink: text and anything drawn on a light surface
    val ink900 = Color(0xFF0E2630)
    val ink700 = Color(0xFF16323D)
    val ink500 = Color(0xFF3D5A66)
    val ink300 = Color(0xFF7C97A2)
    val ink100 = Color(0xFFC7D6DC)

    // surface: the grounds things sit on
    val base = Color(0xFFFFFFFF)
    val tint = Color(0xFFF2F7F9)
    val dark = Color(0xFF0E2630)
    val darkRaised = Color(0xFF17313C)

    // brand
    val teal = Color(0xFF2E7D8F)
    val tealBright = Color(0xFF4FB3C9)

    // state. These three are the only colours that carry meaning, which is
    // what lets the screen be read at a glance from three metres.
    val fault = Color(0xFFE8663C)
    val ok = Color(0xFF3E9E7A)
    val warn = Color(0xFFD9A441)

    // space
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 24.dp
    val s6 = 32.dp
    val s7 = 48.dp

    // radius
    val rS = RoundedCornerShape(8.dp)
    val rM = RoundedCornerShape(14.dp)
    val rL = RoundedCornerShape(22.dp)
    val pill = RoundedCornerShape(999.dp)

    /** The gutter every screen holds, so nothing sits against the edge. */
    val screen = PaddingValues(horizontal = 24.dp)
}

/**
 * The type ramp. Sizes are large for the role they play, because the reading
 * condition that matters is a glance from two to four metres away.
 */
object Type {
    val display = 64.sp      // the repetition count, and nothing else
    val title = 34.sp        // a screen's own name
    val heading = 30.sp      // a fault's name
    val subhead = 22.sp      // the cue, quoted
    val body = 18.sp         // anything the user acts on
    val bodySm = 15.sp       // supporting detail
    val caption = 13.sp      // qualifications and provenance
    val label = 12.sp        // section labels, letter-spaced and upper case

    val bold = FontWeight.Bold
    val semi = FontWeight.SemiBold
    val medium = FontWeight.Medium
    val regular = FontWeight.Normal
}

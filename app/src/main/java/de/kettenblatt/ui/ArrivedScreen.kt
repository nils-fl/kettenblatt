package de.kettenblatt.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kettenblatt.data.Ride
import de.kettenblatt.data.Units
import de.kettenblatt.ui.theme.accents
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the rider sees on crossing the finish line.
 *
 * A screen rather than a banner over the map, because it is the one moment in a
 * ride with nothing left to steer by -- and because it has to outlive the
 * navigation service, which shuts itself down a minute after arriving. The
 * summary comes from the recorded ride, so the figures are what was actually
 * ridden rather than what was planned.
 */
@Composable
fun ArrivedScreen(
    ride: Ride,
    units: Units,
    /** False once the service has stopped itself; there is then nothing to go back to. */
    stillNavigating: Boolean,
    onDone: () -> Unit,
    onKeepRiding: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
            modifier = Modifier
                .padding(20.dp)
                // Held to a readable measure so the card does not stretch into a
                // band of text on a tablet or in landscape.
                .widthIn(max = 420.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CelebrationMark()

                Spacer(Modifier.height(20.dp))
                Text("Arrived", style = MaterialTheme.typography.headlineMedium)

                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append(ride.routeName)
                        if (ride.reversed) append(" · reversed")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatFinishedAt(ride.endedAtMs ?: ride.startedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))

                // Two by two rather than four across: these are the numbers worth
                // reading properly, not a glanceable strip like the ride panel.
                Row(Modifier.fillMaxWidth()) {
                    Stat("RIDDEN", formatDistance(ride.distanceM, units), Modifier.weight(1f))
                    Stat("MOVING", formatDuration(ride.movingMs / 1000), Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat("ASCENT", formatAscent(ride.ascentM, units), Modifier.weight(1f))
                    Stat("AVG SPEED", formatSpeed(ride.averageSpeedMps, units), Modifier.weight(1f))
                }

                // Coverage is the honest measure of whether the planned route was
                // followed, which distance alone does not tell you -- so a ride
                // with a shortcut in it says so rather than quietly reading as a
                // clean finish.
                if (ride.routeSegments > 0) {
                    Spacer(Modifier.height(22.dp))
                    Text(
                        coverageLine(ride),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(28.dp))

                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("Done", modifier = Modifier.padding(vertical = 4.dp))
                }

                // A loop can pass its own finish, and a rider may simply want to
                // carry on past it. Only offered while the service is still up.
                if (stillNavigating) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onKeepRiding, modifier = Modifier.fillMaxWidth()) {
                        Text("Keep riding")
                    }
                }
            }
        }
    }
}

/** The one flourish in the app: a spring the moment the card appears. */
@Composable
private fun CelebrationMark() {
    val scale = remember { Animatable(INITIAL_MARK_SCALE) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    Box(
        Modifier
            .scale(scale.value)
            .size(84.dp)
            .clip(CircleShape)
            // The same green the arrival banner uses, at a weight that reads as a
            // background rather than as another alert.
            .background(MaterialTheme.accents.arrived.copy(alpha = MARK_TINT_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Celebration,
            contentDescription = null,
            tint = MaterialTheme.accents.arrived,
            modifier = Modifier.size(44.dp),
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

/**
 * Coverage in words rather than as a bare percentage.
 *
 * Rounding alone would report 99.6% as "100%", which on the one ride where it
 * matters -- did I actually ride all of it? -- is the wrong answer.
 */
private fun coverageLine(ride: Ride): String {
    val percent = ride.coverage * 100
    return when {
        percent >= FULL_COVERAGE_PERCENT -> "The whole route, start to finish."
        else -> "${percent.roundToInt()}% of the route covered."
    }
}

/** Anything above this is the route ridden in full; the rest is GPS granularity. */
private const val FULL_COVERAGE_PERCENT = 99.5

private const val INITIAL_MARK_SCALE = 0.6f
private const val MARK_TINT_ALPHA = 0.16f

private fun formatFinishedAt(ms: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

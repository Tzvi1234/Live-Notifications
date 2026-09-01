package com.tzvi.kickoff.feature.island

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.core.model.IslandCutout
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.data.demo.DemoCatalogue
import com.tzvi.kickoff.ui.island.COLLAPSED_HEIGHT
import com.tzvi.kickoff.ui.island.DynamicIsland
import com.tzvi.kickoff.ui.island.IslandCutoutDetector
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme
import kotlin.math.roundToInt

/**
 * Line a ring up with the hole in your screen, and the island learns to sit around it.
 *
 * Everything is drawn against near-black so the panel edge disappears and the only two
 * things left to compare are the ring and the real camera. Detection fills the numbers in
 * where Android reports a cutout at all; the sliders are what actually settle it, because
 * the reported rectangle is the region the system reserves rather than the lens.
 */
@Composable
fun IslandCalibrationScreen(
    onBack: () -> Unit,
    viewModel: IslandCalibrationViewModel = hiltViewModel(),
) {
    val stored by viewModel.stored.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<IslandCutout?>(null) }

    LaunchedEffect(stored) {
        if (draft == null) draft = stored
    }

    val current = draft
    if (current == null) {
        Box(Modifier.fillMaxSize().background(CalibrationInk))
        return
    }

    IslandCalibrationContent(
        cutout = current,
        onChange = { draft = it },
        onSave = {
            viewModel.save(current)
            onBack()
        },
        onBack = onBack,
    )
}

@Composable
private fun IslandCalibrationContent(
    cutout: IslandCutout,
    onChange: (IslandCutout) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    val screenWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    var previewExpanded by remember { mutableStateOf(false) }
    var detectionFailed by remember { mutableStateOf(false) }

    // A real live match rather than a grey rectangle: crests and a scoreline are what the
    // island actually has to fit either side of the hole, and a placeholder hides that.
    val preview = remember {
        DemoCatalogue.match(DemoCatalogue.LIVE_MATCH_ID)?.let { match ->
            LiveActivity.MatchActivity(
                match = match,
                stage = LiveActivity.MatchActivity.Stage.LIVE,
                lineups = null,
                recentEvents = DemoCatalogue.liveEvents(),
                statistics = null,
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CalibrationInk),
    ) {
        TargetRing(cutout = cutout)

        AnimatedVisibility(
            visible = cutout.enabled,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(),
        ) {
            DynamicIsland(
                activity = preview,
                expanded = previewExpanded,
                onToggle = { previewExpanded = !previewExpanded },
                onOpenMatch = {},
                cutout = cutout,
                cameraCenterX = screenWidth * cutout.centerXFraction,
                pillTop = (cutout.centerYDp.dp - COLLAPSED_HEIGHT / 2).coerceAtLeast(0.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 120.dp, start = 8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
                // Capped so the panel can never grow over the ring it is there to place.
                .heightIn(max = 440.dp)
                .clip(KickoffShapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Camera calibration",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Move the ring until it sits exactly around the camera hole in your " +
                    "screen. The island then puts the score on one side of it and the clock " +
                    "on the other, instead of floating underneath.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Wrap around the camera",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Off, the island floats below the status bar as one pill.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = cutout.enabled,
                    onCheckedChange = { onChange(cutout.copy(enabled = it)) },
                )
            }

            OutlinedButton(
                onClick = {
                    val detected = IslandCutoutDetector.detect(view)
                    detectionFailed = detected == null
                    if (detected != null) onChange(detected)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CenterFocusStrong,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("Detect automatically")
            }
            if (detectionFailed) {
                Text(
                    text = "Android reports no cutout on this display, so the sliders are " +
                        "the only way. Set it by eye - it does not have to be perfect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CalibrationSlider(
                label = "Sideways",
                value = cutout.centerXFraction,
                valueRange = 0f..1f,
                readout = "${(cutout.centerXFraction * 100).roundToInt()}%",
                onChange = { onChange(cutout.copy(centerXFraction = it)) },
            )
            CalibrationSlider(
                label = "Down from the top",
                value = cutout.centerYDp.toFloat(),
                valueRange = IslandCutout.MIN_CENTER_Y.toFloat()..IslandCutout.MAX_CENTER_Y.toFloat(),
                readout = "${cutout.centerYDp} dp",
                onChange = { onChange(cutout.copy(centerYDp = it.roundToInt())) },
            )
            CalibrationSlider(
                label = "Circle size",
                value = cutout.diameterDp.toFloat(),
                valueRange = IslandCutout.MIN_DIAMETER.toFloat()..IslandCutout.MAX_DIAMETER.toFloat(),
                readout = "${cutout.diameterDp} dp",
                onChange = { onChange(cutout.copy(diameterDp = it.roundToInt())) },
            )

            TextButton(
                onClick = { previewExpanded = !previewExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (previewExpanded) "Collapse the preview" else "Preview it open")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("Save")
                }
            }
        }
    }
}

/** The ring the user aligns with the hardware, drawn in the display's own coordinates. */
@Composable
private fun TargetRing(cutout: IslandCutout) {
    Canvas(Modifier.fillMaxSize()) {
        val centerX = size.width * cutout.centerXFraction
        val centerY = cutout.centerYDp.dp.toPx()
        val radius = cutout.diameterDp.dp.toPx() / 2f
        val ring = if (cutout.enabled) RingActive else RingIdle

        drawCircle(
            color = ring,
            radius = radius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 2.dp.toPx()),
        )
        // A second, wider ring shows the clearance the island will actually leave.
        drawCircle(
            color = ring.copy(alpha = 0.25f),
            radius = radius + IslandCutout.CLEARANCE_DP.dp.toPx(),
            center = Offset(centerX, centerY),
            style = Stroke(width = 1.dp.toPx()),
        )
        // Crosshair, so the centre is findable even when the ring is bigger than the hole.
        val arm = radius + 14.dp.toPx()
        drawLine(
            color = ring.copy(alpha = 0.5f),
            start = Offset(centerX - arm, centerY),
            end = Offset(centerX + arm, centerY),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = ring.copy(alpha = 0.5f),
            start = Offset(centerX, centerY - arm),
            end = Offset(centerX, centerY + arm),
            strokeWidth = 1.dp.toPx(),
        )
    }
    if (!cutout.enabled) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = "Turn the switch on to see the island wrap around it",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 110.dp, start = 32.dp, end = 32.dp),
            )
        }
    }
}

@Composable
private fun CalibrationSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    readout: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = readout,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = valueRange)
    }
}

// Near-black rather than the theme's surface: the point of the screen is to compare the
// ring with the hardware, and any lighter ground puts a visible edge between them.
private val CalibrationInk = Color(0xFF07090A)
private val RingActive = Color(0xFF3FE56C)
private val RingIdle = Color(0xFF8A9A90)

@Preview(name = "Calibration")
@Composable
private fun IslandCalibrationPreview() {
    KickoffTheme(darkTheme = true) {
        Box(Modifier.clip(RoundedCornerShape(0.dp))) {
            IslandCalibrationContent(
                cutout = IslandCutout(enabled = true),
                onChange = {},
                onSave = {},
                onBack = {},
            )
        }
    }
}

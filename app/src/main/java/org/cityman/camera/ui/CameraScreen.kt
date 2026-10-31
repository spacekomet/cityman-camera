package org.cityman.camera.ui

import android.net.Uri
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import org.cityman.camera.processing.ProcessingOptions
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.cityman.camera.R
import org.cityman.camera.camera.CameraController
import org.cityman.camera.camera.CameraState
import org.cityman.camera.camera.CaptureMode
import org.cityman.camera.camera.PhotoFormat
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import java.util.Locale

private val BarWidth = 104.dp

@Composable
fun CameraScreen(
    controller: CameraController,
    iconRotation: Float,
    onOpenMedia: (Uri, Boolean) -> Unit,
    galleryApps: () -> List<Pair<String, String>> = { emptyList() },
) {
    val st by controller.state.collectAsStateWithLifecycle()
    var openDial by remember { mutableStateOf<DialKind?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showFullSettings by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(iconRotation, tween(250), label = "iconRotation")

    // Accent colour: Android's dynamic accent (light tone, for the black UI) or amber.
    val dynamicAccent = if (android.os.Build.VERSION.SDK_INT >= 31) colorResource(android.R.color.system_accent1_300) else Amber
    LaunchedEffect(st.systemAccent, dynamicAccent) {
        Accent = if (st.systemAccent) dynamicAccent else Amber
        controller.peakingColor = Accent.toArgb()
    }

    // A dial belongs to one camera: close it when the camera or mode changes.
    LaunchedEffect(st.caps?.id, st.mode) { openDial = null }
    LaunchedEffect(st.message) {
        if (st.message != null) {
            delay(4000)
            controller.clearMessage()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val w = maxWidth
        val h = maxHeight
        val portrait = h > w
        val preview = st.previewSize
        val sensorAspect = if (preview != null) preview.width.toFloat() / preview.height else 4f / 3f
        // The viewfinder shows the preview turned to the screen: tall in portrait.
        val aspect = if (portrait) 1f / sensorAspect else sensorAspect
        val vfWidth = minOf(w, h * aspect)
        val vfHeight = vfWidth / aspect

        Viewfinder(
            controller = controller,
            state = st,
            showGrid = st.grid,
            onTap = { x, y ->
                if (openDial != null || showSettings) {
                    openDial = null
                    showSettings = false
                } else {
                    controller.tapToFocus(x, y)
                }
            },
            modifier = Modifier.align(if (portrait) Alignment.TopCenter else Alignment.CenterStart).size(vfWidth, vfHeight),
        )

        // Manual control chips: along the bottom in landscape, above the shutter bar in portrait
        // (hidden there while a dial is open, as the ring covers them).
        if (!portrait || openDial == null) {
            ControlChips(
                state = st,
                open = openDial,
                compact = portrait,
                onClick = { kind -> openDial = if (openDial == kind) null else kind; showSettings = false },
                onReset = { kind -> controller.setControls(Dials.reset(kind, st.controls)) },
                modifier = if (portrait) {
                    Modifier.align(Alignment.BottomCenter).padding(bottom = BarWidth + 4.dp)
                } else {
                    Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 6.dp)
                },
            )
        }

        // Top-left: settings and status.
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundButton(onClick = { showSettings = !showSettings; openDial = null }) {
                GlyphIcon(Glyph.SETTINGS, Modifier.rotate(rotation), size = 26.dp)
            }
            if (st.mode == CaptureMode.PHOTO && st.caps?.rawSize != null) {
                // Quick switch for multi-frame processing.
                Badge(
                    "MF",
                    on = st.multiFrame,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { controller.setMultiFrame(!st.multiFrame) },
                )
            }
            if (st.saveDng && st.mode == CaptureMode.PHOTO && !st.multiFrame) Badge("RAW")
            // Exposure / white balance lock, both modes.
            Badge(
                "AE",
                on = st.aeLock,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { controller.setAeLock(!st.aeLock) },
            )
            if (st.mode == CaptureMode.VIDEO && st.timelapse > 1) Badge("${st.timelapse}×")
            if (st.multiFrame && st.still && st.mode == CaptureMode.PHOTO) Badge(stringResource(R.string.badge_still))
            if (st.focusPoint != null) Badge(stringResource(R.string.badge_locked))
        }

        // Arc dial around the shutter button.
        val dial = openDial
        val caps = st.caps
        if (dial != null && caps != null) {
            val spec = remember(dial, caps.id) { Dials.spec(dial, caps) }
            if (spec != null) {
                val center = with(density) {
                    if (portrait) Offset((w / 2).toPx(), (h - BarWidth / 2).toPx())
                    else Offset((w - BarWidth / 2).toPx(), (h / 2).toPx())
                }
                val radius = with(density) { (minOf(w, h) * 0.40f).toPx() }
                ArcDial(
                    spec = spec,
                    index = Dials.indexOf(spec, st.controls),
                    center = center,
                    radius = radius,
                    // Landscape: auto at the top, ring to the left. Portrait: auto on the left, ring above.
                    startAngle = if (portrait) 180f else 270f,
                    sweep = if (portrait) 180f else -180f,
                    onIndex = { i -> controller.setControls(Dials.apply(dial, spec.stops[i], st.controls)) },
                    onDismiss = { openDial = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Zoom presets within thumb reach: above the shutter (portrait) or beside it (landscape).
        val caps2 = st.caps
        if (openDial == null && caps2 != null && caps2.maxZoom > 1.5f) {
            ZoomButtons(
                zoom = st.zoom,
                maxZoom = caps2.maxZoom,
                vertical = !portrait,
                rotation = rotation,
                onZoom = { controller.setZoom(it) },
                modifier = if (portrait) {
                    Modifier.align(Alignment.BottomCenter).padding(bottom = BarWidth + 84.dp)
                } else {
                    Modifier.align(Alignment.CenterEnd).padding(end = BarWidth + 10.dp)
                },
            )
        }

        ShutterBar(
            state = st,
            portrait = portrait,
            dialOpen = openDial != null,
            rotation = rotation,
            onShutter = { controller.shutter() },
            onSwitch = { controller.switchCamera() },
            onMode = { controller.setMode(if (st.mode == CaptureMode.PHOTO) CaptureMode.VIDEO else CaptureMode.PHOTO) },
            onGallery = { st.lastUri?.let { onOpenMedia(it, st.lastIsVideo) } },
            modifier = if (portrait) {
                Modifier.align(Alignment.BottomCenter).height(BarWidth).fillMaxWidth()
            } else {
                Modifier.align(Alignment.CenterEnd).width(BarWidth).fillMaxHeight()
            },
        )

        if (st.recording) RecordingTimer(st.recordingSince, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        if (st.countdown > 0) {
            BasicText(
                st.countdown.toString(),
                style = TextStyle(color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light),
                modifier = Modifier.align(if (portrait) Alignment.Center else Alignment.Center).rotate(rotation),
            )
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 64.dp),
        ) {
            QuickPanel(
                state = st,
                onTimelapse = { controller.setTimelapse(it) },
                onStabilise = { controller.setStabilise(it) },
                onGrid = { controller.setGrid(it) },
                onDng = { controller.setSaveDng(it) },
                onMultiFrame = { controller.setMultiFrame(it) },
                onTimer = { controller.setTimer(it) },
                onMore = { showSettings = false; showFullSettings = true },
            )
        }

        if (showFullSettings) {
            SettingsScreen(controller, st, galleryApps, onClose = { showFullSettings = false })
        }

        st.message?.let { msg ->
            BasicText(
                msg,
                style = TextStyle(color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .widthIn(max = 420.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Viewfinder(
    controller: CameraController,
    state: CameraState,
    showGrid: Boolean,
    onTap: (Float, Float) -> Unit,
    modifier: Modifier,
) {
    val zoom by rememberUpdatedState(state.zoom)
    val tap by rememberUpdatedState(onTap)
    val flash = remember { Animatable(0f) }
    LaunchedEffect(state.shutterCount) {
        if (state.shutterCount > 0) {
            flash.snapTo(0.7f)
            flash.animateTo(0f, tween(220))
        }
    }
    Box(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o -> tap(o.x / size.width, o.y / size.height) },
                    onLongPress = { controller.clearFocusPoint() },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange != 1f) controller.setZoom(zoom * zoomChange)
                }
            },
    ) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(controller.surfaceCallback) } },
            modifier = Modifier.fillMaxSize(),
        )
        AnalysisOverlay(controller, state)
        if (state.level) LevelOverlay(controller)
        if (showGrid) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.35f)
                for (f in listOf(1f / 3f, 2f / 3f)) {
                    drawLine(c, Offset(size.width * f, 0f), Offset(size.width * f, size.height), 1.dp.toPx())
                    drawLine(c, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1.dp.toPx())
                }
            }
        }
        state.focusPoint?.let { (fx, fy) ->
            val color = when (state.live.afState) {
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> Accent
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> RecordRed
                else -> Color.White
            }
            Canvas(Modifier.fillMaxSize()) {
                val p = Offset(fx * size.width, fy * size.height)
                drawCircle(color, radius = 30.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
                drawCircle(color, radius = 3.dp.toPx(), center = p)
            }
        }
        if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = flash.value)))
    }
}

/** 1× / 2× / 4× buttons; the one in use shows the exact zoom while pinching. */
@Composable
private fun ZoomButtons(
    zoom: Float,
    maxZoom: Float,
    vertical: Boolean,
    rotation: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier,
) {
    val presets = listOf(1f, 2f, 4f).filter { it <= maxZoom + 0.01f }
    val content: @Composable () -> Unit = {
        presets.forEachIndexed { i, p ->
            val next = presets.getOrNull(i + 1) ?: Float.MAX_VALUE
            val active = zoom >= p - 0.01f && zoom < next - 0.01f
            val label = if (active && kotlin.math.abs(zoom - p) > 0.05f) String.format(Locale.US, "%.1f×", zoom) else "${p.toInt()}×"
            Box(
                Modifier
                    .size(if (active) 44.dp else 38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(if (active) 1.5.dp else 0.dp, if (active) Accent else Color.Transparent, CircleShape)
                    .clickable { onZoom(p) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label,
                    style = TextStyle(color = if (active) Accent else Color.White, fontSize = if (active) 13.sp else 12.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.rotate(rotation),
                )
            }
        }
    }
    if (vertical) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** Horizon line: follows the real horizon, accent coloured when the phone is level. */
@Composable
private fun LevelOverlay(controller: CameraController) {
    val tilt by controller.tilt.collectAsStateWithLifecycle()
    val t = tilt ?: return
    if (kotlin.math.abs(t) > 25f) return
    val level = kotlin.math.abs(t) < 1f
    Canvas(Modifier.fillMaxSize()) {
        val len = size.minDimension * 0.32f
        val c = center
        rotate(t, c) {
            val color = if (level) Accent else Color.White.copy(alpha = 0.85f)
            drawLine(color, Offset(c.x - len, c.y), Offset(c.x - len * 0.25f, c.y), 2.dp.toPx())
            drawLine(color, Offset(c.x + len * 0.25f, c.y), Offset(c.x + len, c.y), 2.dp.toPx())
        }
        // Fixed reference marks at the screen's horizontal.
        val mark = Color.White.copy(alpha = 0.5f)
        drawLine(mark, Offset(c.x - len - 18.dp.toPx(), c.y), Offset(c.x - len - 6.dp.toPx(), c.y), 2.dp.toPx())
        drawLine(mark, Offset(c.x + len + 6.dp.toPx(), c.y), Offset(c.x + len + 18.dp.toPx(), c.y), 2.dp.toPx())
    }
}

/** Zebra / focus peaking marks over the viewfinder, and the histogram. */
@Composable
private fun AnalysisOverlay(controller: CameraController, state: CameraState) {
    if (state.mode != CaptureMode.PHOTO) return
    if (!state.showHistogram && !state.zebra && !state.peaking) return
    val analysis by controller.analysis.collectAsStateWithLifecycle()
    val a = analysis ?: return
    val bmp = a.overlay
    if (bmp != null && (state.zebra || state.peaking)) {
        val front = state.caps?.front == true
        val rot = state.previewRotation
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val m = android.graphics.Matrix()
                m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                m.postRotate(rot.toFloat())
                val rw = if (rot % 180 == 0) bmp.width else bmp.height
                val rh = if (rot % 180 == 0) bmp.height else bmp.width
                m.postScale((if (front) -1f else 1f) * size.width / rw, size.height / rh)
                m.postTranslate(size.width / 2f, size.height / 2f)
                canvas.nativeCanvas.drawBitmap(bmp, m, null)
            }
        }
    }
    if (state.showHistogram) {
        Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.TopEnd) {
            Canvas(
                Modifier
                    .size(width = 128.dp, height = 56.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(4.dp),
            ) {
                val n = a.histogram.size
                val bw = size.width / n
                for (i in 0 until n) {
                    val h = a.histogram[i] * size.height
                    drawRect(
                        if (i >= n - 2 && a.clipped > 0.01f) RecordRed else Color.White.copy(alpha = 0.85f),
                        topLeft = Offset(i * bw, size.height - h),
                        size = androidx.compose.ui.geometry.Size(bw * 0.9f, h),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlChips(
    state: CameraState,
    open: DialKind?,
    compact: Boolean,
    onClick: (DialKind) -> Unit,
    onReset: (DialKind) -> Unit,
    modifier: Modifier,
) {
    val caps = state.caps
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (kind in DialKind.entries) {
            val enabled = caps != null && Dials.spec(kind, caps) != null &&
                !(kind == DialKind.EV && Dials.evLocked(state.controls))
            val (value, live) = Dials.chipText(kind, caps, state.controls, state.live)
            val manual = when (kind) {
                DialKind.WB -> state.controls.wbKelvin != null
                DialKind.FOCUS -> state.controls.focusDiopters != null
                DialKind.ISO -> state.controls.iso != null
                DialKind.SHUTTER -> state.controls.exposureNs != null
                DialKind.EV -> state.controls.evSteps != 0
            }
            val selected = open == kind
            Column(
                Modifier
                    .width(if (compact) 76.dp else 78.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .combinedClickable(
                        enabled = enabled,
                        onClick = { onClick(kind) },
                        onLongClick = { onReset(kind) },
                    )
                    .padding(vertical = 5.dp)
                    .alpha(if (enabled) 1f else 0.4f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    kind.title,
                    style = TextStyle(
                        color = if (selected || manual) Accent else Color.White.copy(alpha = 0.65f),
                        fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium,
                    ),
                )
                BasicText(
                    value,
                    style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = if (manual) FontWeight.SemiBold else FontWeight.Light),
                )
                BasicText(
                    live ?: " ",
                    style = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp),
                )
                Spacer(
                    Modifier.padding(top = 2.dp).width(22.dp).height(2.dp)
                        .background(if (manual) Accent else Color.Transparent),
                )
            }
        }
    }
}

/** Shutter, mode, camera switch and gallery: a column on the right in landscape, a row at the bottom in portrait. */
@Composable
private fun ShutterBar(
    state: CameraState,
    portrait: Boolean,
    dialOpen: Boolean,
    rotation: Float,
    onShutter: () -> Unit,
    onSwitch: () -> Unit,
    onMode: () -> Unit,
    onGallery: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.background(Color.Black.copy(alpha = 0.35f))) {
        val others = !dialOpen && !state.recording
        if (others && state.cameraCount > 1) {
            RoundButton(
                onClick = onSwitch,
                modifier = if (portrait) Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)
                else Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
            ) {
                GlyphIcon(Glyph.SWITCH, Modifier.rotate(rotation), size = 30.dp)
            }
        }
        if (others) {
            RoundButton(
                onClick = onMode,
                modifier = if (portrait) Modifier.align(Alignment.Center).offset(x = 92.dp)
                else Modifier.align(Alignment.Center).offset(y = (-96).dp),
            ) {
                GlyphIcon(if (state.mode == CaptureMode.PHOTO) Glyph.VIDEO else Glyph.PHOTO, Modifier.rotate(rotation), size = 30.dp)
            }
        }
        ShutterButton(
            mode = state.mode,
            recording = state.recording,
            busy = state.capturing,
            processing = state.processing,
            onClick = onShutter,
            modifier = Modifier.align(Alignment.Center),
        )
        if (others) {
            val thumb = state.thumbnail
            Box(
                Modifier
                    .then(
                        if (portrait) Modifier.align(Alignment.CenterStart).padding(start = 20.dp)
                        else Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                    )
                    .size(56.dp)
                    .rotate(rotation)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(enabled = state.lastUri != null, onClick = onGallery),
            ) {
                if (thumb != null) {
                    Image(thumb.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(mode: CaptureMode, recording: Boolean, busy: Boolean, processing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val spin = rememberInfiniteTransition(label = "processing")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(90), label = "shutterScale")
    Canvas(
        modifier
            .size(80.dp)
            .scale(scale)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = !busy, onClick = onClick),
    ) {
        val r = size.minDimension / 2
        drawCircle(Color.White, radius = r - 2.dp.toPx(), style = Stroke(4.dp.toPx()))
        if (processing) {
            val inset = 2.dp.toPx()
            drawArc(
                Accent, startAngle = angle, sweepAngle = 100f, useCenter = false,
                topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        when {
            recording -> {
                val s = r * 0.7f
                drawRoundRect(
                    RecordRed, topLeft = Offset(center.x - s / 2, center.y - s / 2),
                    size = androidx.compose.ui.geometry.Size(s, s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
            }
            mode == CaptureMode.VIDEO -> drawCircle(RecordRed, radius = r - 10.dp.toPx())
            else -> drawCircle(if (busy) Color.White.copy(alpha = 0.4f) else Color.White, radius = r - 10.dp.toPx())
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Badge(text: String, on: Boolean = true, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = TextStyle(color = if (on) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        modifier = modifier
            .background(if (on) Accent else Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .border(1.dp, if (on) Accent else Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun RecordingTimer(since: Long, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val secs = ((now - since) / 1000).coerceAtLeast(0)
    Row(
        modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(RecordRed, CircleShape))
        Spacer(Modifier.width(8.dp))
        BasicText(
            String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60),
            style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/** The few settings changed most often, in the same style as [SettingsScreen], which has the rest. */
@Composable
private fun QuickPanel(
    state: CameraState,
    onTimelapse: (Int) -> Unit,
    onStabilise: (Boolean) -> Unit,
    onGrid: (Boolean) -> Unit,
    onDng: (Boolean) -> Unit,
    onMultiFrame: (Boolean) -> Unit,
    onTimer: (Int) -> Unit,
    onMore: () -> Unit,
) {
    val photo = state.mode == CaptureMode.PHOTO
    val raw = state.caps?.rawSize != null
    val title = TextStyle(color = Color.White, fontSize = 18.sp)
    Column(
        Modifier
            .width(300.dp)
            .background(Color.Black.copy(alpha = 0.92f))
            .border(1.dp, Color.White.copy(alpha = 0.25f))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (photo && raw) Toggle(stringResource(R.string.settings_mf_title), null, state.multiFrame, titleStyle = title) { onMultiFrame(it) }
        if (photo) {
            Choice(
                title = stringResource(R.string.settings_timer_title),
                desc = null,
                options = listOf(0 to stringResource(R.string.settings_off), 3 to "3 s", 10 to "10 s"),
                selected = state.timerSeconds,
                horizontal = true,
                titleStyle = title,
                onSelect = onTimer,
            )
        }
        if (!photo) {
            Choice(
                title = stringResource(R.string.settings_timelapse),
                desc = null,
                options = listOf(0 to stringResource(R.string.settings_off), 5 to "5×", 10 to "10×", 30 to "30×"),
                selected = state.timelapse,
                horizontal = true,
                titleStyle = title,
                onSelect = onTimelapse,
            )
            Toggle(stringResource(R.string.settings_stabilise), null, state.stabilise, titleStyle = title) { onStabilise(it) }
        }
        Toggle(stringResource(R.string.settings_grid), null, state.grid, titleStyle = title) { onGrid(it) }
        if (photo && raw) Toggle(stringResource(R.string.settings_dng_short), null, state.saveDng, titleStyle = title) { onDng(it) }
        BasicText(
            stringResource(R.string.settings_more),
            style = TextStyle(color = Accent, fontSize = 17.sp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onMore).padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(
        text,
        style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, letterSpacing = 1.sp),
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = if (selected) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Accent else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .width(248.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 15.sp), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(width = 40.dp, height = 22.dp)
                .background(if (on) Accent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(11.dp))
                .padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(16.dp).background(if (on) Color.Black else Color.White, CircleShape))
        }
    }
}

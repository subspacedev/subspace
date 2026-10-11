// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.navigation

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/*
 * In-app navigation that moves like Android's own activity transitions: the predictive back of
 * the Settings app, and the default activity open/close animations. Numbers and sources:
 * docs/agent/research/2026-10-11-system-back-animation-spec.md; the geometry is BackGeometry.kt.
 */

private const val TAB_FADE_MILLIS = 200

/** `activity_open_enter`'s alpha: 83 ms linear after 50 ms. */
private const val OPEN_FADE_MILLIS = 83
private const val OPEN_FADE_DELAY_MILLIS = 50

/** `activity_close_exit`'s alpha: 83 ms linear after 35 ms. */
private const val CLOSE_FADE_MILLIS = 83
private const val CLOSE_FADE_DELAY_MILLIS = 35

/**
 * How long NavHost keeps both entries composed after a predictive pop. It seeks this hold by the
 * raw gesture progress and plays the rest after commit, so (1 - progress) x this is what is left:
 * 450 ms (the whole post-commit) up to progress 0.5, and the closing screen's 90 ms fade up to 0.9.
 */
private const val PREDICTIVE_HOLD_MILLIS = 900

/** How long to wait after the gesture ends for NavHost to pop, before calling it a cancel. */
private const val COMMIT_WAIT_MILLIS = 150L

/** `STIFFNESS_LOW` and `DAMPING_RATIO_LOW_BOUNCY`, the fling-bounce spring. */
private const val FLING_STIFFNESS = 200f
private const val FLING_DAMPING = 0.75f

/** Below this the closing screen has faded out and stops being composed, so it stops taking touches. */
private const val CLOSING_GONE_T = 0.2f

private const val MILLIS_PER_SECOND = 1000f

/**
 * EMPHASIZED / `fast_out_extra_slow_in`:
 * M 0,0 C 0.05,0, 0.133333,0.06 0.166666,0.4 C 0.208333,0.82 0.25,1 1,1.
 */
@Suppress("MagicNumber")
private val EmphasizedEasing: Easing =
    PathEasing(
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
            cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        },
    )

/** Which kind of transition NavHost is starting. */
internal enum class NavMotionStyle {
    /** A destination opens: the default activity open animation. */
    Push,

    /** Back without a gesture (Back key, in-app arrow, API < 34): the default activity close. */
    Pop,

    /** A predictive back gesture: NavHost only holds both screens; [NavMotion] draws the motion. */
    PredictivePop,

    /** Switching top-level tabs from the pill: a quick crossfade, not a hierarchy. */
    Fade,
}

/** Phase of a predictive back gesture. */
private enum class BackPhase { Gesture, Released, Committed }

/** Everything the frame of a predictive back depends on; replaced, never mutated. */
private data class BackState(
    val closingId: String?,
    val enteringId: String?,
    val initialTouchY: Float,
    val rawProgress: Float = 0f,
    val rawDy: Float = 0f,
    val fromRightEdge: Boolean = false,
    val edgeSwipe: Boolean = true,
    val progressVelocity: Float = 0f,
    val lastEventMillis: Long = 0L,
    val phase: BackPhase = BackPhase.Gesture,
    val t: Float = 0f,
    val spring: Float = FLING_SPRING_SCALE,
)

/** Where both screens are, in window pixels, and the closing screen's alpha. */
private data class BackFrame(
    val closing: Rect,
    val entering: Rect,
    val closingAlpha: Float,
)

/**
 * The NavHost's transitions, and the predictive back animation NavHost cannot draw itself.
 *
 * navigation-compose keeps the pop: its PredictiveBackHandler, the back stack, and the seekable
 * transition that keeps both entries composed while the finger is down. The visuals instead
 * follow the platform's own gesture state, [NavigationEventDispatcher.transitionState]: its
 * progress is already spring-smoothed by BackProgressAnimator (and springs back to 0 before a
 * cancel), so it goes through the BACK_GESTURE interpolator and nothing else. On commit this runs
 * its own 450 ms post-commit animation and fling bounce, like the system does.
 *
 * Z-order needs no help: NavHost 2.9 gives a pop's target `zIndex - 1` and a push's `+ 1`, so the
 * closing screen draws above the entering one, with [NavCard]'s scrim between them.
 */
@Stable
internal class NavMotion(
    private val navController: NavHostController,
    private val dispatcher: NavigationEventDispatcher?,
    density: Density,
) {
    private val offsetPx = with(density) { BACK_ENTERING_OFFSET_DP.dp.toPx() }
    private val marginPx = with(density) { BACK_MARGIN_DP.dp.toPx() }
    private val offsetInt = offsetPx.roundToInt()

    private var back: BackState? by mutableStateOf(null)

    /**
     * The entry a pill tap navigated to. A pill tap to Home pops the stack (`popUpTo` the start
     * destination, then `launchSingleTop`), so NavHost runs its *pop* lambdas for it, exactly as
     * it does for Back from Settings to Home. This is what tells the two apart.
     */
    private var tabSwitchEntryId: String? = null

    /** A pill tap: navigates to the top-level [value] and remembers that it was a tab switch. */
    fun switchTab(
        navController: NavHostController,
        value: String,
    ) {
        navController.navigateToTopLevel(value)
        tabSwitchEntryId = navController.currentBackStackEntry?.id
    }

    /** Forgets the pill tap once the user has moved on from the entry it landed on. */
    fun onCurrentEntry(entry: NavBackStackEntry?) {
        if (entry?.id != tabSwitchEntryId) tabSwitchEntryId = null
    }

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (forwardStyle() == NavMotionStyle.Fade) {
            fadeIn(tween(TAB_FADE_MILLIS))
        } else {
            slideInHorizontally(tween(BACK_COMMIT_MILLIS, easing = EmphasizedEasing)) { offsetInt } +
                fadeIn(tween(OPEN_FADE_MILLIS, OPEN_FADE_DELAY_MILLIS, LinearEasing))
        }
    }

    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (forwardStyle() == NavMotionStyle.Fade) {
            fadeOut(tween(TAB_FADE_MILLIS))
        } else {
            slideOutHorizontally(tween(BACK_COMMIT_MILLIS, easing = EmphasizedEasing)) { -offsetInt }
        }
    }

    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        when (popStyle()) {
            NavMotionStyle.Fade -> fadeIn(tween(TAB_FADE_MILLIS))
            NavMotionStyle.PredictivePop -> fadeIn(tween(PREDICTIVE_HOLD_MILLIS, easing = LinearEasing), 1f)
            else -> slideInHorizontally(tween(BACK_COMMIT_MILLIS, easing = EmphasizedEasing)) { -offsetInt }
        }
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        when (popStyle()) {
            NavMotionStyle.Fade -> fadeOut(tween(TAB_FADE_MILLIS))
            NavMotionStyle.PredictivePop -> fadeOut(tween(PREDICTIVE_HOLD_MILLIS, easing = LinearEasing), 1f)
            else ->
                slideOutHorizontally(tween(BACK_COMMIT_MILLIS, easing = EmphasizedEasing)) { offsetInt } +
                    fadeOut(tween(CLOSE_FADE_MILLIS, CLOSE_FADE_DELAY_MILLIS, LinearEasing))
        }
    }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.popStyle(): NavMotionStyle {
        val fromPill = targetState.id == tabSwitchEntryId && isTabSwitch()
        val gesture = dispatcher?.transitionState?.value is NavigationEventTransitionState.InProgress
        return when {
            fromPill -> NavMotionStyle.Fade
            gesture -> NavMotionStyle.PredictivePop
            else -> NavMotionStyle.Pop
        }
    }

    /** Follows the platform's back gestures for as long as the NavHost is composed. */
    suspend fun followBackGestures() {
        val dispatcher = dispatcher ?: return
        coroutineScope {
            dispatcher.transitionState.collect { state ->
                when (state) {
                    is NavigationEventTransitionState.InProgress ->
                        if (state.direction == NavigationEventTransitionState.TRANSITIONING_BACK) {
                            onGestureEvent(state.latestEvent)
                        }
                    NavigationEventTransitionState.Idle -> {
                        val released = back?.takeIf { it.phase == BackPhase.Gesture } ?: return@collect
                        back = released.copy(phase = BackPhase.Released)
                        launch { settle(released) }
                    }
                }
            }
        }
    }

    private fun onGestureEvent(event: NavigationEvent) {
        val now = if (event.frameTimeMillis > 0L) event.frameTimeMillis else System.currentTimeMillis()
        val previous =
            back?.takeIf { it.phase == BackPhase.Gesture }
                ?: BackState(
                    closingId = navController.currentBackStackEntry?.id,
                    enteringId = navController.previousBackStackEntry?.id,
                    initialTouchY = event.touchY,
                    rawProgress = event.progress,
                    lastEventMillis = now,
                )
        val elapsedSeconds = (now - previous.lastEventMillis) / MILLIS_PER_SECOND
        val velocity =
            if (elapsedSeconds > 0f) {
                (event.progress - previous.rawProgress) / elapsedSeconds
            } else {
                previous.progressVelocity
            }
        back =
            previous.copy(
                rawProgress = event.progress,
                rawDy = event.touchY - previous.initialTouchY,
                fromRightEdge = event.swipeEdge == NavigationEvent.EDGE_RIGHT,
                edgeSwipe = event.swipeEdge != NavigationEvent.EDGE_NONE,
                progressVelocity = velocity,
                lastEventMillis = now,
            )
    }

    /**
     * The gesture has ended: completed or cancelled, which the dispatcher does not say. A commit
     * makes NavHost pop the closing entry within a frame or two; a cancel leaves it current, and
     * by then the platform has already sprung the progress back to 0.
     */
    private suspend fun settle(released: BackState) {
        val committed =
            withTimeoutOrNull(COMMIT_WAIT_MILLIS) {
                snapshotFlow { navController.currentBackStackEntry?.id }.first { it != released.closingId }
            } != null
        if (!committed) {
            if (back?.phase == BackPhase.Released) back = null
            return
        }
        back = released.copy(phase = BackPhase.Committed)
        val startVelocity =
            flingStartVelocity(released.progressVelocity, released.edgeSwipe, released.rawProgress)
        coroutineScope {
            launch {
                animate(0f, 1f, animationSpec = tween(BACK_COMMIT_MILLIS, easing = LinearEasing)) { value, _ ->
                    updateCommit(released) { copy(t = value) }
                }
            }
            launch {
                animate(
                    initialValue = FLING_SPRING_SCALE,
                    targetValue = FLING_SPRING_SCALE,
                    initialVelocity = startVelocity,
                    animationSpec = spring(dampingRatio = FLING_DAMPING, stiffness = FLING_STIFFNESS),
                ) { value, _ -> updateCommit(released) { copy(spring = value) } }
            }
        }
        updateCommit(released) { null }
    }

    /** Changes the commit [released] started, unless a newer gesture has replaced it meanwhile. */
    private inline fun updateCommit(
        released: BackState,
        change: BackState.() -> BackState?,
    ) {
        val current = back
        if (current?.phase == BackPhase.Committed && current.closingId == released.closingId) back = current.change()
    }

    /** True once [entryId], the screen a committed gesture closed, has faded out. */
    fun isClosedAndFaded(entryId: String): Boolean {
        val state = back ?: return false
        return state.phase == BackPhase.Committed && entryId == state.closingId && state.t >= CLOSING_GONE_T
    }

    /** Scrim alpha over [entryId], or 0 unless it is the screen a gesture is uncovering. */
    fun scrimAlpha(
        entryId: String,
        maxScrim: Float,
    ): Float {
        val state = back
        return when {
            state == null || entryId != state.enteringId || entryId == state.closingId -> 0f
            state.phase == BackPhase.Committed -> postCommitScrimAlpha(maxScrim, state.t)
            else -> maxScrim
        }
    }

    /**
     * Places [entryId]'s layer: the closing screen only while NavHost is actually popping it (a
     * dirty editor's BackHandler takes the gesture, NavHost never seeks, and nothing moves), the
     * entering screen whenever it is composed during a gesture.
     */
    fun applyLayer(
        scope: GraphicsLayerScope,
        entryId: String,
        visibility: Transition<EnterExitState>,
        cornerRadiusPx: Float,
    ) {
        val state = back ?: return
        val closing = entryId == state.closingId
        val moving =
            if (closing) visibility.targetState == EnterExitState.PostExit else entryId == state.enteringId
        if (moving) scope.place(backFrame(state, scope.size, marginPx, offsetPx), closing, cornerRadiusPx)
    }
}

private fun GraphicsLayerScope.place(
    frame: BackFrame,
    closing: Boolean,
    cornerRadiusPx: Float,
) {
    val rect = if (closing) frame.closing else frame.entering
    val scale = if (size.width > 0f) rect.width / size.width else 1f
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = scale
    scaleY = scale
    translationX = rect.left
    translationY = rect.top
    if (closing) alpha = frame.closingAlpha
    // The visible radius is the device's window radius whatever the scale, as the shell's is.
    shape = RoundedCornerShape(if (scale > 0f) cornerRadiusPx / scale else 0f)
    clip = true
}

/** Where both screens are for [state], in a window of [size]. */
private fun backFrame(
    state: BackState,
    size: Size,
    marginPx: Float,
    offsetPx: Float,
): BackFrame {
    val p = backGestureInterpolation(state.rawProgress)
    val shrunk = closingRect(p, state.fromRightEdge, size, marginPx)
    val dy = verticalShift(state.rawDy, size, shrunk.height, marginPx)
    val closing = shrunk.translate(0f, dy)
    val entering = enteringRect(p, size, offsetPx).translate(0f, dy)
    if (state.phase != BackPhase.Committed) return BackFrame(closing, entering, 1f)
    val eased = EmphasizedEasing.transform(state.t)
    val closingTarget = postCommitClosingTarget(closing, size, offsetPx)
    return BackFrame(
        closing = flingBounced(lerp(closing, closingTarget, eased), state.spring),
        entering = flingBounced(lerp(entering, postCommitEnteringTarget(size), eased), state.spring),
        closingAlpha = postCommitClosingAlpha(state.t),
    )
}

/** A forward navigation between two top-level tabs is a tab switch; any other opens a screen. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardStyle(): NavMotionStyle =
    if (isTabSwitch()) NavMotionStyle.Fade else NavMotionStyle.Push

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.selectedTopLevelValue() != null &&
        targetState.destination.selectedTopLevelValue() != null

internal val LocalNavMotion = staticCompositionLocalOf<NavMotion?> { null }

/** The [NavMotion] for one NavHost, forgetting a pill tap once [currentEntry] moves on from it. */
@Composable
internal fun rememberNavMotion(
    navController: NavHostController,
    currentEntry: NavBackStackEntry?,
): NavMotion {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val density = LocalDensity.current
    val motion = remember(navController, dispatcher, density) { NavMotion(navController, dispatcher, density) }
    LaunchedEffect(motion) { motion.followBackGestures() }
    LaunchedEffect(currentEntry) { motion.onCurrentEntry(currentEntry) }
    return motion
}

/** A [NavHost] running [motion]'s transitions, with [motion] provided to every [NavCard]. */
@Composable
internal fun CardNavHost(
    navController: NavHostController,
    startDestination: Any,
    motion: NavMotion,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    CompositionLocalProvider(LocalNavMotion provides motion) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = modifier,
            enterTransition = motion.enter,
            exitTransition = motion.exit,
            popEnterTransition = motion.popEnter,
            popExitTransition = motion.popExit,
            builder = builder,
        )
    }
}

/** [composable], with the destination drawn as a [NavCard]. Every destination goes through this. */
internal inline fun <reified T : Any> NavGraphBuilder.cardComposable(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        val scope = this
        NavCard(entry) { scope.content(entry) }
    }
}

/**
 * Draws a destination as a window would be drawn: full size, with its own surface background
 * (MainActivity's `Surface` colour, so nothing changes at rest) and the status bar inset inside
 * it, so a shrinking screen takes its status bar strip with it. During a predictive back the
 * layer is placed by [NavMotion.applyLayer], and the screen being uncovered carries the scrim.
 */
@Composable
internal fun AnimatedVisibilityScope.NavCard(
    entry: NavBackStackEntry,
    content: @Composable () -> Unit,
) {
    val motion = LocalNavMotion.current
    val colors = MaterialTheme.colorScheme
    val maxScrim = maxScrimAlpha(darkTheme = colors.background.luminance() < HALF_LUMINANCE)
    val surface = colors.surface
    val view = LocalView.current
    val visibility = transition
    val faded by remember(motion, entry.id) { derivedStateOf { motion?.isClosedAndFaded(entry.id) == true } }
    Box(Modifier.fillMaxSize().drawWithContent { drawScrim(motion, entry.id, maxScrim) }) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { motion?.applyLayer(this, entry.id, visibility, view.windowCornerRadiusPx()) }
                .drawBehind {
                    // activity_open_enter's <extend> and its siblings: paint the background 96 dp
                    // past both sides, so a screen sliding in or out never leaves a strip of the
                    // one behind showing. Off screen at rest; predictive back clips it away.
                    val (left, right) = slideExtensionRects(size, BACK_ENTERING_OFFSET_DP.dp.toPx())
                    drawRect(surface, topLeft = left.topLeft, size = left.size)
                    drawRect(surface, topLeft = right.topLeft, size = right.size)
                }
                .background(surface)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            if (!faded) content()
        }
    }
}

private const val HALF_LUMINANCE = 0.5f

private fun ContentDrawScope.drawScrim(
    motion: NavMotion?,
    entryId: String,
    maxScrim: Float,
) {
    drawContent()
    val alpha = motion?.scrimAlpha(entryId, maxScrim) ?: 0f
    if (alpha > 0f) drawRect(Color.Black, alpha = alpha)
}

/** The device's window corner radius (API 31+), or square where the platform does not say. */
private fun View.windowCornerRadiusPx(): Float =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f
    } else {
        0f
    }

// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

private const val PUSH_MILLIS = 400
private const val POP_MILLIS = 400
private const val TAB_FADE_MILLIS = 200

/** The M3 emphasized curve, as its cubic-bezier approximation (0.2, 0, 0, 1). */
private const val EMPHASIZED_X1 = 0.2f
private val EmphasizedEasing = CubicBezierEasing(EMPHASIZED_X1, 0f, 0f, 1f)

/** What the transition NavHost is currently running looks like. */
internal enum class NavMotionStyle {
    /** A destination opens: the new card arrives over a still screen. */
    Push,

    /** Back: the current card leaves, uncovering a still screen. */
    Pop,

    /** Switching top-level tabs from the pill: a quick crossfade, not a hierarchy. */
    Fade,
}

/**
 * The NavHost's transitions plus the one fact a destination cannot see from inside its own
 * `AnimatedContentScope`: whether the transition it is part of is a push, a pop or a tab switch.
 *
 * NavHost's enter/exit/pop lambdas are the only place push and pop are told apart (its own
 * `isPop` is internal), so each lambda records the style here and [NavCard] reads it. The moving
 * card is drawn by [NavCard] in a graphics layer, not by these Enter/ExitTransitions, because an
 * EnterTransition cannot animate a clip shape; the transitions are [EnterTransition.None] /
 * [ExitTransition.None] for a card, which also keeps the screen beneath at full bounds, still.
 *
 * Z-order needs no help: NavHost 2.9 gives a pop's target `zIndex - 1` and a push's `+ 1`, so the
 * moving card is always drawn above the screen it covers or uncovers.
 */
@Stable
internal class NavMotion {
    var style: NavMotionStyle by mutableStateOf(NavMotionStyle.Push)
        private set

    /**
     * The entry a pill tap navigated to. A pill tap to Home pops the stack (`popUpTo` the start
     * destination, then `launchSingleTop`), so NavHost runs its *pop* lambdas for it, exactly as
     * it does for system Back from Settings to Home. This is what tells the two apart.
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
        if (forwardStyle() == NavMotionStyle.Fade) fadeIn(tween(TAB_FADE_MILLIS)) else EnterTransition.None
    }

    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (forwardStyle() == NavMotionStyle.Fade) fadeOut(tween(TAB_FADE_MILLIS)) else ExitTransition.None
    }

    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (popStyle() == NavMotionStyle.Fade) fadeIn(tween(TAB_FADE_MILLIS)) else EnterTransition.None
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (popStyle() == NavMotionStyle.Fade) fadeOut(tween(TAB_FADE_MILLIS)) else ExitTransition.None
    }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardStyle(): NavMotionStyle =
        (if (isTabSwitch()) NavMotionStyle.Fade else NavMotionStyle.Push).also { style = it }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.popStyle(): NavMotionStyle {
        val fromPill = targetState.id == tabSwitchEntryId && isTabSwitch()
        return (if (fromPill) NavMotionStyle.Fade else NavMotionStyle.Pop).also { style = it }
    }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
        initialState.destination.selectedTopLevelValue() != null &&
            targetState.destination.selectedTopLevelValue() != null
}

internal val LocalNavMotion = staticCompositionLocalOf<NavMotion?> { null }

/** The [NavMotion] for one NavHost, forgetting a pill tap once [currentEntry] moves on from it. */
@Composable
internal fun rememberNavMotion(currentEntry: NavBackStackEntry?): NavMotion {
    val motion = remember { NavMotion() }
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
        NavCard { scope.content(entry) }
    }
}

/**
 * Draws a destination as the card of [LocalNavMotion]'s current style: the entering card of a
 * push and the exiting card of a pop move along [cardTransformAt]; every other screen stays
 * square and still. The surface background keeps the screen beneath from showing through a card
 * whose own content is transparent; it is the colour of `MainActivity`'s `Surface`, so at rest
 * nothing changes.
 */
@Composable
internal fun AnimatedVisibilityScope.NavCard(content: @Composable () -> Unit) {
    val motion = LocalNavMotion.current
    val progress =
        transition.animateFloat(
            transitionSpec = { cardSpec(motion?.style, initialState, targetState) },
            label = "navCard",
        ) { state -> if (state == EnterExitState.Visible) 0f else 1f }
    val surface = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxSize().cardLayer(motion, transition, progress).background(surface)) {
        content()
    }
}

/**
 * Applies [cardTransformAt] in a graphics layer, so a frame of the motion costs a redraw, not a
 * recomposition or a relayout. Only the entering card of a push and the exiting card of a pop move.
 */
private fun Modifier.cardLayer(
    motion: NavMotion?,
    transition: Transition<EnterExitState>,
    progress: State<Float>,
): Modifier =
    graphicsLayer {
        val style = motion?.style
        val moving =
            if (transition.targetState == EnterExitState.PostExit) {
                style == NavMotionStyle.Pop
            } else {
                style == NavMotionStyle.Push
            }
        val p = if (moving) progress.value else 0f
        if (p > 0f) {
            val card = cardTransformAt(p)
            scaleX = card.scale
            scaleY = card.scale
            translationX = card.translationFraction * size.width
            shape = RoundedCornerShape(card.cornerDp.dp)
            clip = true
            shadowElevation = card.elevationDp.dp.toPx()
        }
    }

/**
 * A pop's exiting card runs linearly, because NavHost seeks it by gesture progress and
 * [cardTransformAt] already holds the curve; a push's entering card decelerates in on the
 * emphasized curve. Everything else snaps, so it adds nothing to the transition's length.
 */
private fun cardSpec(
    style: NavMotionStyle?,
    from: EnterExitState,
    to: EnterExitState,
): FiniteAnimationSpec<Float> =
    when {
        style == NavMotionStyle.Pop && to == EnterExitState.PostExit ->
            tween(POP_MILLIS, easing = LinearEasing)
        style == NavMotionStyle.Push && from == EnterExitState.PreEnter && to == EnterExitState.Visible ->
            tween(PUSH_MILLIS, easing = EmphasizedEasing)
        else -> snap()
    }

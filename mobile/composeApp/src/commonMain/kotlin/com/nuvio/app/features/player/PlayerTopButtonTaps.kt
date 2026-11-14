package com.nuvio.app.features.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/**
 * Taps for the player's top-right buttons (switch engine, watch party, lock, back).
 *
 * On phones the video surface is a native Android view, and taps over the video area did
 * not reliably reach those buttons through the normal Compose hit test. Instead, the
 * player's root box watches every touch on the Initial pass (which always reaches the
 * outermost box first, whatever sits on top) and, when a tap lands inside a registered
 * button's bounds, runs that button and consumes the touch so nothing underneath reacts.
 */
internal class PlayerTopButtonTaps {
    companion object {
        /**
         * One registry for the whole app. On Android the activity checks it in
         * dispatchTouchEvent, before any view (including the native video view) sees the
         * touch, so a tap on a top button can never be taken by the video surface.
         */
        val shared = PlayerTopButtonTaps()
    }

    val hasTargets: Boolean get() = targets.isNotEmpty()

    private class Target(var bounds: Rect, var onClick: () -> Unit)

    private val targets = LinkedHashMap<String, Target>()
    var rootCoordinates: LayoutCoordinates? = null

    fun register(key: String, bounds: Rect, onClick: () -> Unit) {
        val existing = targets[key]
        if (existing != null) {
            existing.bounds = bounds
            existing.onClick = onClick
        } else {
            targets[key] = Target(bounds, onClick)
        }
    }

    fun unregister(key: String, onClick: (() -> Unit)? = null) {
        // Only drop the entry if it still belongs to the caller, so a newly composed
        // button that re-registered under the same key is not removed by the old one.
        val existing = targets[key] ?: return
        if (onClick == null || existing.onClick === onClick) targets.remove(key)
    }

    fun hit(windowPosition: Offset, slopPx: Float): (() -> Unit)? =
        targets.values
            .filter { it.bounds.inflate(slopPx).contains(windowPosition) }
            .minByOrNull { (it.bounds.center - windowPosition).getDistanceSquared() }
            ?.onClick
}

internal val LocalPlayerTopButtonTaps = compositionLocalOf<PlayerTopButtonTaps?> { null }

/** Applied to the player's outermost box. */
internal fun Modifier.playerTopButtonTapRoot(taps: PlayerTopButtonTaps): Modifier =
    onGloballyPositioned { taps.rootCoordinates = it }
        .pointerInput(taps) {
            val extraPx = 10.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val root = taps.rootCoordinates ?: return@awaitEachGesture
                if (!root.isAttached) return@awaitEachGesture
                val windowDown = root.localToWindow(down.position)
                if (taps.hit(windowDown, extraPx) == null) return@awaitEachGesture
                // The touch belongs to a top button: nothing below may treat it as a tap,
                // long press or drag.
                down.consume()
                val slop = viewConfiguration.touchSlop * 3f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    event.changes.forEach { it.consume() }
                    if ((change.position - down.position).getDistance() > slop) {
                        // Finger slid away: swallow the rest of the gesture without acting.
                        while (true) {
                            val rest = awaitPointerEvent(PointerEventPass.Initial)
                            rest.changes.forEach { it.consume() }
                            if (rest.changes.none { it.pressed }) return@awaitEachGesture
                        }
                    }
                    if (change.changedToUpIgnoreConsumed()) {
                        val windowUp = root.localToWindow(change.position)
                        (taps.hit(windowUp, extraPx) ?: taps.hit(windowDown, extraPx))?.invoke()
                        return@awaitEachGesture
                    }
                }
            }
        }

/** Registers this element's on-screen bounds as a top button with the player's root. */
@Composable
internal fun Modifier.playerTopButtonTarget(key: String, onClick: () -> Unit): Modifier {
    val taps = LocalPlayerTopButtonTaps.current ?: return this
    val latestOnClick by rememberUpdatedState(onClick)
    val stableClick = remember { { latestOnClick() } }
    DisposableEffect(taps, key) {
        onDispose { taps.unregister(key, stableClick) }
    }
    return onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) taps.register(key, coordinates.boundsInWindow(), stableClick)
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// Design values transcribed from the design system's floating-chrome notes
// (26dp gesture-bar gap, 104dp content reservation); see StatusChip.kt for the
// same MagicNumber pattern.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.theme.ShapeCornerFull

/** `26dp`: the bar sits this far above the gesture bar, not merely flush with the navigation-bar inset. */
private val NAV_BAR_GESTURE_BAR_GAP = 26.dp

private val NAV_ITEM_HORIZONTAL_PADDING = 16.dp
private val NAV_ITEM_ICON_LABEL_GAP = 8.dp
private val NAV_ITEM_MIN_HEIGHT = 48.dp

/**
 * The vertical space [FloatingNavBar] needs behind it.
 *
 * Every screen that scrolls content beneath the floating nav bar must add this
 * as bottom content padding so the bar never covers the last item — the design
 * system's note: "content beneath reserves ~104dp of bottom padding". One
 * public constant so the number lives in one place.
 */
val FLOATING_NAV_CONTENT_BOTTOM_PADDING = 104.dp

/**
 * One destination in [FloatingNavBar].
 *
 * @param value the caller's own identifier (typically a route name), compared
 *   against [FloatingNavBar]'s `selected` and reported through `onSelect`.
 * @param labelRes the destination's name. Visible text only for the selected
 *   destination, but every unselected destination's contentDescription too.
 * @param icon the outline icon shown while unselected.
 * @param selectedIcon the filled icon shown while selected.
 */
data class NavPillItem(
    val value: String,
    @StringRes val labelRes: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
)

/**
 * The app's floating navigation chrome, built on the M3 Expressive
 * [HorizontalFloatingToolbar]: it floats [NAV_BAR_GESTURE_BAR_GAP] above the
 * gesture bar (plus the `navigationBars` inset) on `surfaceContainerHigh`.
 *
 * Only the selected destination shows its label, as a `primary`-filled pill
 * with its filled icon; the rest are icon-only. An unselected destination has
 * no [Text] in the composition, so its label is applied as an explicit
 * contentDescription instead: every destination stays reachable by the name a
 * sighted user sees. The selected pill's width change animates with the
 * theme's fast spatial spec.
 *
 * Screen placement and z-order are the caller's responsibility.
 *
 * @param items the destinations to render, in order.
 * @param selected the [NavPillItem.value] of the active destination.
 * @param onSelect invoked with a destination's value when tapped, including
 *   re-taps of the selected one — callers may want "tap current tab to scroll
 *   to top", so this component never suppresses them.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FloatingNavBar(
    items: List<NavPillItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier =
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = NAV_BAR_GESTURE_BAR_GAP),
        colors =
        FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        items.forEach { item ->
            NavBarDestination(
                item = item,
                isSelected = item.value == selected,
                onClick = { onSelect(item.value) },
            )
        }
    }
}

@Composable
private fun NavBarDestination(
    item: NavPillItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(item.labelRes)
    val colors = MaterialTheme.colorScheme
    val contentColor = if (isSelected) colors.onPrimary else colors.onSurfaceVariant

    Row(
        modifier =
        modifier
            .heightIn(min = NAV_ITEM_MIN_HEIGHT)
            .clip(ShapeCornerFull)
            .let { if (isSelected) it.background(colors.primary) else it }
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
            .let { if (!isSelected) it.semantics { contentDescription = label } else it }
            .animateContentSize(animationSpec = MaterialTheme.motionScheme.fastSpatialSpec())
            .padding(horizontal = NAV_ITEM_HORIZONTAL_PADDING),
        horizontalArrangement = Arrangement.spacedBy(NAV_ITEM_ICON_LABEL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Decorative: the Row's selectable semantics (merged with the explicit
        // contentDescription, or the visible Text) already name the destination once.
        Icon(
            painter = painterResource(if (isSelected) item.selectedIcon else item.icon),
            contentDescription = null,
            tint = contentColor,
        )
        if (isSelected) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = contentColor)
        }
    }
}

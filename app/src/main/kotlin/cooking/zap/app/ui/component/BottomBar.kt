package cooking.zap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.Routes

private val NavBarDark = Color(0xFF1F2937)
// Unread badge — brand amber-400, lighter than the orange nav icons so it
// reads as a distinct "alert" dot rather than blending into the iconography.
private val UnreadDotColor = Color(0xFFFBBF24)
private val NAV_HEIGHT = 50.dp
private val SIDE_ICON_SIZE = 21.dp  // every nav icon — the bar is flat now

enum class BottomTab(
    val route: String,
    val labelResId: Int,
    val selectedIcon: ImageVector?,
    val unselectedIcon: ImageVector?,
    val selectedIconRes: Int? = null,
    val unselectedIconRes: Int? = null
) {
    FEED(Routes.FEED, R.string.nav_feed, null, null, R.drawable.ic_flame, R.drawable.ic_flame_outline),
    RECIPES(Routes.RECIPES, R.string.nav_recipes, null, null, R.drawable.ic_nav_recipes, R.drawable.ic_nav_recipes),

    /**
     * One glyph for both states, matching iOS — its search tab is
     * `magnifyingglass` selected and unselected alike, with no filled/outline
     * pair to mirror. [RECIPES] already reuses a single drawable the same way.
     */
    SEARCH(Routes.SEARCH, R.string.nav_search, Icons.Default.Search, Icons.Default.Search),
    MESSAGES(Routes.DM_LIST, R.string.nav_messages, null, null, R.drawable.ic_nav_chat, R.drawable.ic_nav_chat_outline),
    NOTIFICATIONS(Routes.NOTIFICATIONS, R.string.nav_notifications, null, null, R.drawable.ic_nav_alert, R.drawable.ic_nav_alert_outline),

    /**
     * Drawer-only, never rendered in the bar — as on iOS, where the wallet
     * stays out of the tab bar for App Store review. Kept in the enum because
     * navigation still routes through it.
     */
    WALLET(Routes.WALLET, R.string.nav_wallet, null, null, R.drawable.ic_zc_wallet, R.drawable.ic_zc_wallet);

    companion object {
        /** The five tabs rendered in the bar, in display order. */
        val bottomBarTabs = listOf(FEED, RECIPES, SEARCH, MESSAGES, NOTIFICATIONS)

        /**
         * Read-only accounts hold no signing key and so cannot send DMs —
         * drop Messages, keep the rest. Mirrors iOS's
         * `bottomBarCases(watchOnly:)`.
         */
        fun bottomBarTabs(readOnly: Boolean) =
            if (readOnly) bottomBarTabs - MESSAGES else bottomBarTabs
    }
}

@Composable
fun WispBottomBar(
    currentRoute: String?,
    hasUnreadHome: Boolean,
    hasUnreadMessages: Boolean,
    hasUnreadNotifications: Boolean,
    isDarkTheme: Boolean = true,
    isZapAnimating: Boolean = false,
    isReplyAnimating: Boolean = false,
    notifSoundEnabled: Boolean = true,
    isReadOnly: Boolean = false,
    onTabSelected: (BottomTab) -> Unit
) {
    val navBarColor = if (isDarkTheme) NavBarDark else MaterialTheme.colorScheme.surface

    if (isReadOnly) {
        ReadOnlyBottomBar(
            currentRoute = currentRoute,
            hasUnreadHome = hasUnreadHome,
            hasUnreadNotifications = hasUnreadNotifications,
            isDarkTheme = isDarkTheme,
            isZapAnimating = isZapAnimating,
            isReplyAnimating = isReplyAnimating,
            notifSoundEnabled = notifSoundEnabled,
            onTabSelected = onTabSelected
        )
        return
    }

    // Flat five-item row — Feed · Recipes · Search · Messages · Notifications,
    // matching iOS. The elevated wallet circle that used to occupy the middle
    // slot is gone: the wallet lives in the drawer on both platforms now, and
    // Search takes the slot it vacated.
    val visibleTabs = BottomTab.bottomBarTabs

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NAV_HEIGHT)
                .background(navBarColor)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                visibleTabs.forEach { tab ->
                    SideNavItem(
                        tab = tab,
                        selected = currentRoute == tab.route,
                        hasUnread = when (tab) {
                            BottomTab.FEED -> hasUnreadHome
                            BottomTab.MESSAGES -> hasUnreadMessages
                            BottomTab.NOTIFICATIONS -> hasUnreadNotifications
                            else -> false
                        },
                        isZapAnimating = isZapAnimating,
                        isReplyAnimating = isReplyAnimating,
                        notifSoundEnabled = notifSoundEnabled,
                        modifier = Modifier.weight(1f),
                        onTabSelected = onTabSelected
                    )
                }
            }
        }
        // Colored spacer reserving the system nav-bar inset so the bar reads
        // as one surface down to the gesture area.
        Spacer(
            Modifier
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .background(navBarColor)
        )
    }
}

@Composable
private fun SideNavItem(
    tab: BottomTab,
    selected: Boolean,
    hasUnread: Boolean,
    isZapAnimating: Boolean,
    isReplyAnimating: Boolean,
    notifSoundEnabled: Boolean,
    modifier: Modifier = Modifier,
    onTabSelected: (BottomTab) -> Unit
) {
    val zapTint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .height(NAV_HEIGHT)
            .clickable { onTabSelected(tab) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.requiredSize(SIDE_ICON_SIZE),
            contentAlignment = Alignment.Center
        ) {
            if (tab == BottomTab.NOTIFICATIONS && isZapAnimating) {
                // Zap animation overrides the bell — lightning bolt.
                Icon(
                    painter = painterResource(R.drawable.ic_bolt),
                    contentDescription = stringResource(tab.labelResId),
                    tint = zapTint
                )
            } else if (tab.selectedIconRes != null) {
                Icon(
                    painter = painterResource(if (selected) tab.selectedIconRes else tab.unselectedIconRes!!),
                    contentDescription = stringResource(tab.labelResId),
                    tint = zapTint,
                    // The flame reads a touch small next to its siblings —
                    // bump it up in this one spot rather than inflating the
                    // shared drawable (which would also blow up the drawer's
                    // copy of the same icon). Must be requiredSize: plain
                    // size() gets clamped back to SIDE_ICON_SIZE by the
                    // parent box's fixed constraints.
                    modifier = if (tab == BottomTab.FEED) Modifier.requiredSize(24.dp) else Modifier
                )
            } else {
                Icon(
                    imageVector = if (selected) tab.selectedIcon!! else tab.unselectedIcon!!,
                    contentDescription = stringResource(tab.labelResId),
                    tint = zapTint,
                    // The custom ic_nav_* drawables use tight viewports, so
                    // their glyphs fill SIDE_ICON_SIZE edge to edge. A Material
                    // icon sits on a 24dp artboard with a ~20dp live area, so
                    // at the same box it draws visibly smaller than its
                    // neighbours. Size picked by measuring the rendered glyphs
                    // on device: the fork, chat and bell each come out 46px
                    // tall, and this box lands the magnifying glass on the
                    // same 46px. requiredSize for the same reason the flame
                    // uses it above — plain size() gets clamped by the parent.
                    modifier = Modifier.requiredSize(29.dp)
                )
            }

            if (hasUnread) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .background(color = UnreadDotColor, shape = CircleShape)
                )
            }

            if (tab == BottomTab.NOTIFICATIONS) {
                val zeroFootprintModifier = Modifier
                    .size(120.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = 0, minHeight = 0)
                        )
                        layout(0, 0) {
                            placeable.place(-placeable.width / 2, -placeable.height / 2)
                        }
                    }
                ZapBurstEffect(
                    isActive = isZapAnimating,
                    modifier = zeroFootprintModifier,
                    soundEnabled = notifSoundEnabled
                )
                IcqFlowerBurstEffect(
                    isActive = isReplyAnimating,
                    modifier = zeroFootprintModifier,
                    soundEnabled = notifSoundEnabled
                )
            }
        }
    }
}

// Read-only layout: FEED, RECIPES, SEARCH, NOTIFICATIONS — no MESSAGES,
// since a read-only account has no key to sign DMs with. Wallet is
// drawer-only for every account.
@Composable
private fun ReadOnlyBottomBar(
    currentRoute: String?,
    hasUnreadHome: Boolean,
    hasUnreadNotifications: Boolean,
    isDarkTheme: Boolean = true,
    isZapAnimating: Boolean,
    isReplyAnimating: Boolean,
    notifSoundEnabled: Boolean,
    onTabSelected: (BottomTab) -> Unit
) {
    val navBarColor = if (isDarkTheme) NavBarDark else MaterialTheme.colorScheme.surface
    val visibleTabs = BottomTab.bottomBarTabs(readOnly = true)

    Column {
        NavigationBar(
            containerColor = navBarColor,
            modifier = Modifier
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .height(NAV_HEIGHT),
            windowInsets = WindowInsets(0)
        ) {
            visibleTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                val hasUnread = when (tab) {
                    BottomTab.FEED -> hasUnreadHome
                    BottomTab.NOTIFICATIONS -> hasUnreadNotifications
                    else -> false
                }
                NavigationBarItem(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = Color.Transparent
                    ),
                    icon = {
                        Box(
                            modifier = Modifier.requiredSize(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val zapTint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                            if (tab == BottomTab.NOTIFICATIONS && isZapAnimating) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_bolt),
                                    contentDescription = stringResource(tab.labelResId),
                                    tint = zapTint
                                )
                            } else if (tab.selectedIconRes != null) {
                                Icon(
                                    painter = painterResource(if (selected) tab.selectedIconRes else tab.unselectedIconRes!!),
                                    contentDescription = stringResource(tab.labelResId),
                                    tint = zapTint
                                )
                            } else {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon!! else tab.unselectedIcon!!,
                                    contentDescription = stringResource(tab.labelResId),
                                    tint = zapTint,
                                    // Same Material-vs-drawable sizing as the
                                    // full bar: a 24dp artboard draws visibly
                                    // smaller than the tight-viewport custom
                                    // drawables its neighbours use.
                                    modifier = Modifier.requiredSize(29.dp)
                                )
                            }
                            if (hasUnread) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.TopEnd)
                                        .offset(x = 2.dp, y = (-2).dp)
                                        .background(color = UnreadDotColor, shape = CircleShape)
                                )
                            }
                            if (tab == BottomTab.NOTIFICATIONS) {
                                val zeroFootprintModifier = Modifier
                                    .size(120.dp)
                                    .layout { measurable, constraints ->
                                        val placeable = measurable.measure(
                                            constraints.copy(minWidth = 0, minHeight = 0)
                                        )
                                        layout(0, 0) {
                                            placeable.place(-placeable.width / 2, -placeable.height / 2)
                                        }
                                    }
                                ZapBurstEffect(isActive = isZapAnimating, modifier = zeroFootprintModifier, soundEnabled = notifSoundEnabled)
                                IcqFlowerBurstEffect(isActive = isReplyAnimating, modifier = zeroFootprintModifier, soundEnabled = notifSoundEnabled)
                            }
                        }
                    },
                    label = null
                )
            }
        }
    }
}

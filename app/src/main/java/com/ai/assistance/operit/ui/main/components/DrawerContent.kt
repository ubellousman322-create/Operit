package com.ai.assistance.operit.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.ShizukuAuthorizer
import com.ai.assistance.operit.core.tools.system.action.ActionListenerFactory
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.data.repository.WorkflowRepository
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.main.screens.ScreenRouteRegistry
import com.ai.assistance.operit.ui.main.navigation.NavigationEntrySpec
import com.ai.assistance.operit.ui.main.screens.Screen
import com.ai.assistance.operit.ui.theme.liquidGlass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SidebarPermissionStatus(
        val badgeTextResId: Int
)

private suspend fun resolveSidebarPermissionStatus(
        context: android.content.Context,
        preferredPermissionLevel: AndroidPermissionLevel?
): SidebarPermissionStatus {
        return when (preferredPermissionLevel) {
                null,
                AndroidPermissionLevel.STANDARD ->
                        SidebarPermissionStatus(
                                badgeTextResId = R.string.sidebar_status_normal
                        )
                AndroidPermissionLevel.DEBUGGER ->
                        when {
                                !ShizukuAuthorizer.isShizukuInstalled(context) ->
                                        SidebarPermissionStatus(
                                                badgeTextResId = R.string.status_not_installed
                                        )
                                !ShizukuAuthorizer.isShizukuServiceRunning() ->
                                        SidebarPermissionStatus(
                                                badgeTextResId = R.string.status_not_running
                                        )
                                ShizukuAuthorizer.hasShizukuPermission() ->
                                        SidebarPermissionStatus(
                                                badgeTextResId = R.string.sidebar_status_normal
                                        )
                                else ->
                                        SidebarPermissionStatus(
                                                badgeTextResId = R.string.unauthorized
                                        )
                        }
                AndroidPermissionLevel.ACCESSIBILITY,
                AndroidPermissionLevel.ADMIN,
                AndroidPermissionLevel.ROOT -> {
                        val permissionStatus =
                                ActionListenerFactory.getListener(context, preferredPermissionLevel)
                                        .hasPermission()
                        SidebarPermissionStatus(
                                badgeTextResId =
                                        if (permissionStatus.granted) {
                                                R.string.sidebar_status_normal
                                        } else {
                                                R.string.unauthorized
                                        }
                        )
                }
        }
}

/** Content for the expanded navigation drawer. Chat stays the primary destination. */
@Composable
fun DrawerContent(
        navItems: List<NavItem>,
        pluginEntries: List<NavigationEntrySpec>,
        selectedItem: NavItem?,
        selectedRouteId: String,
        isNetworkAvailable: Boolean,
        networkType: String,
        appearance: NavigationDrawerAppearance,
        topContentPadding: Dp? = null,
        scope: CoroutineScope,
        drawerState: androidx.compose.material3.DrawerState,
        onScreenSelected: (Screen) -> Unit,
        onNavigationEntrySelected: (NavigationEntrySpec) -> Unit
) {
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val resolvedTopContentPadding =
                topContentPadding
                        ?: WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val handleScreenSelection: (Screen) -> Unit = { screen ->
                scope.launch {
                        if (
                                drawerState.currentValue == DrawerValue.Open ||
                                        drawerState.targetValue == DrawerValue.Open
                        ) {
                                drawerState.close()
                        }
                        onScreenSelected(screen)
                }
        }

        Column(
                modifier =
                        Modifier.fillMaxHeight()
                                .padding(
                                        top = resolvedTopContentPadding,
                                        end = 8.dp,
                                        bottom = bottomInset
                                )
        ) {
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        CompactNavigationDrawerItem(
                                icon = NavItem.AiChat.icon,
                                label = stringResource(id = NavItem.AiChat.titleResId),
                                selected = selectedItem == NavItem.AiChat,
                                appearance = appearance,
                                onClick = { handleScreenSelection(Screen.AiChat) }
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        HorizontalDivider(color = appearance.dividerColor)
                        Spacer(modifier = Modifier.height(4.dp))
                        CompactNavigationDrawerItem(
                                icon = NavItem.Settings.icon,
                                label = "工具与设置",
                                selected = selectedRouteId == ScreenRouteRegistry.routeIdOf(Screen.ToolsAndSettings),
                                appearance = appearance,
                                onClick = { handleScreenSelection(Screen.ToolsAndSettings) }
                        )
                }
        }
}

/** Compact tablet rail: chat on top, tools and settings anchored at the bottom. */
@Composable
fun CollapsedDrawerContent(
        navItems: List<NavItem>,
        pluginEntries: List<NavigationEntrySpec>,
        selectedItem: NavItem?,
        selectedRouteId: String,
        isNetworkAvailable: Boolean,
        appearance: NavigationDrawerAppearance,
        onScreenSelected: (Screen) -> Unit,
        onNavigationEntrySelected: (NavigationEntrySpec) -> Unit
) {
        Column(
                modifier = Modifier.fillMaxHeight().fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                CollapsedSidebarButton(
                        icon = NavItem.AiChat.icon,
                        contentDescription = stringResource(id = NavItem.AiChat.titleResId),
                        selected = selectedItem == NavItem.AiChat,
                        appearance = appearance,
                        onClick = { onScreenSelected(Screen.AiChat) }
                )
                Spacer(modifier = Modifier.weight(1f))
                HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(0.58f),
                        color = appearance.dividerColor
                )
                Spacer(modifier = Modifier.height(10.dp))
                CollapsedSidebarButton(
                        icon = NavItem.Settings.icon,
                        contentDescription = "工具与设置",
                        selected = selectedRouteId == ScreenRouteRegistry.routeIdOf(Screen.ToolsAndSettings),
                        appearance = appearance,
                        onClick = { onScreenSelected(Screen.ToolsAndSettings) }
                )
        }
}

@Composable
private fun CollapsedSidebarButton(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        contentDescription: String,
        selected: Boolean,
        appearance: NavigationDrawerAppearance,
        onClick: () -> Unit
) {
        Surface(
                modifier = Modifier.size(44.dp).clip(CircleShape),
                color =
                        if (selected) appearance.selectedContainerColor.copy(alpha = 0.18f)
                        else Color.Transparent,
                shape = CircleShape
        ) {
                IconButton(onClick = onClick) {
                        Icon(
                                imageVector = icon,
                                contentDescription = contentDescription,
                                tint = if (selected) appearance.selectedContentColor else appearance.itemColor,
                                modifier = Modifier.size(24.dp)
                        )
                }
        }
}

@Composable
private fun NewSidebarTopContent(
        selectedItem: NavItem?,
        pluginEntries: List<NavigationEntrySpec>,
        selectedRouteId: String,
        brandName: String,
        isNetworkAvailable: Boolean,
        networkType: String,
        appearance: NavigationDrawerAppearance,
        navItems: List<NavItem>,
        activePackageCount: Int,
        workflowCount: Int,
        permissionStatus: SidebarPermissionStatus,
        onNavItemClick: (NavItem) -> Unit,
        onNavigationEntryClick: (NavigationEntrySpec) -> Unit
) {
        Spacer(modifier = Modifier.height(24.dp))

        SidebarInfoCard(
                brandName = brandName,
                isNetworkAvailable = isNetworkAvailable,
                networkType = networkType,
                appearance = appearance
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
                SidebarQuickActionCard(
                        modifier = Modifier.weight(1f),
                        icon = NavItem.Packages.icon,
                        label = stringResource(id = NavItem.Packages.titleResId),
                        badgeText = activePackageCount.toString(),
                        selected = selectedItem == NavItem.Packages,
                        appearance = appearance,
                        onClick = { onNavItemClick(NavItem.Packages) }
                )
                SidebarQuickActionCard(
                        modifier = Modifier.weight(1f),
                        icon = NavItem.ShizukuCommands.icon,
                        label = stringResource(id = R.string.sidebar_permission_short),
                        badgeText = stringResource(id = permissionStatus.badgeTextResId),
                        selected = selectedItem == NavItem.ShizukuCommands,
                        appearance = appearance,
                        onClick = { onNavItemClick(NavItem.ShizukuCommands) }
                )
                SidebarQuickActionCard(
                        modifier = Modifier.weight(1f),
                        icon = NavItem.Workflow.icon,
                        label = stringResource(id = NavItem.Workflow.titleResId),
                        badgeText = workflowCount.toString(),
                        selected = selectedItem == NavItem.Workflow,
                        appearance = appearance,
                        onClick = { onNavItemClick(NavItem.Workflow) }
                )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
                text = stringResource(id = R.string.nav_group_ai_features),
                style = MaterialTheme.typography.titleSmall,
                color = appearance.titleColor.copy(alpha = 0.82f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 28.dp, end = 20.dp, bottom = 2.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        navItems.forEach { item ->
                CompactNavigationDrawerItem(
                        icon = item.icon,
                        label = stringResource(id = item.titleResId),
                        selected = selectedItem == item,
                        appearance = appearance,
                        onClick = { onNavItemClick(item) }
                )
        }
        if (pluginEntries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                        text = stringResource(id = R.string.nav_group_plugins),
                        style = MaterialTheme.typography.titleSmall,
                        color = appearance.titleColor.copy(alpha = 0.82f),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 28.dp, end = 20.dp, bottom = 2.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                pluginEntries.forEach { entry ->
                        CompactNavigationDrawerItem(
                                icon = entry.icon,
                                label = entry.title,
                                selected = selectedRouteId == entry.routeId,
                                appearance = appearance,
                                onClick = { onNavigationEntryClick(entry) }
                        )
                }
        }
}

@Composable
private fun SidebarInfoCard(
        brandName: String,
        isNetworkAvailable: Boolean,
        networkType: String,
        appearance: NavigationDrawerAppearance
) {
        Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
                Text(
                        text = brandName,
                        style = MaterialTheme.typography.titleLarge.copy(
                                letterSpacing = 0.5.sp
                        ),
                        color = appearance.titleColor,
                        fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                        shape = RoundedCornerShape(50),
                        color = appearance.statusAvailableColor.copy(alpha = 0.12f)
                ) {
                        Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                                Box(
                                        modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(
                                                        if (isNetworkAvailable) Color(0xFF4CAF50)
                                                        else Color(0xFFEF5350)
                                                )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                        imageVector =
                                                if (isNetworkAvailable) Icons.Default.Wifi else Icons.Default.WifiOff,
                                        contentDescription = stringResource(id = R.string.network_status_label),
                                        tint = appearance.statusAvailableColor,
                                        modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                        text = networkType,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = appearance.statusAvailableColor,
                                        fontWeight = FontWeight.Medium
                                )
                        }
                }
        }
}

@Composable
private fun SidebarQuickActionCard(
        modifier: Modifier = Modifier,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        badgeText: String,
        selected: Boolean,
        appearance: NavigationDrawerAppearance,
        onClick: () -> Unit
) {
        val itemShape = RoundedCornerShape(12.dp)
        val selectedGlassOverlayColor =
                if (selected) {
                        appearance.selectedContainerColor.copy(alpha = 0.18f)
                } else {
                        Color.Transparent
                }
        val accentColor = appearance.selectedContentColor
        Surface(
                modifier =
                        modifier.height(76.dp)
                                .liquidGlass(
                                        enabled = appearance.buttonLiquidGlassEnabled,
                                        shape = itemShape,
                                        containerColor = appearance.buttonContainerColor,
                                        shadowElevation = if (selected) 4.dp else 2.dp,
                                        borderWidth = 0.5.dp,
                                        blurRadius = 16.dp,
                                        overlayAlphaBoost = 0.04f,
                                        enableLens = false
                                )
                                .clip(itemShape)
                                .background(selectedGlassOverlayColor),
                onClick = onClick,
                color =
                        if (appearance.buttonLiquidGlassEnabled) {
                                Color.Transparent
                        } else if (selected) {
                                appearance.selectedContainerColor
                        } else {
                                Color.Transparent
                        },
                shape = itemShape
        ) {
                Box(modifier = Modifier.fillMaxWidth().height(76.dp)) {
                        if (selected) {
                                Box(
                                        modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth(0.5f)
                                                .height(2.dp)
                                                .padding(bottom = 4.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(accentColor.copy(alpha = 0.7f))
                                )
                        }
                        SidebarQuickActionBadge(
                                text = badgeText,
                                appearance = appearance,
                                selected = selected,
                                modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 6.dp, end = 6.dp)
                        )
                        Column(
                                modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                        ) {
                                Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint =
                                                if (selected) appearance.selectedContentColor
                                                else appearance.itemColor,
                                        modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                                letterSpacing = 0.2.sp
                                        ),
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color =
                                                if (selected) appearance.selectedContentColor
                                                else appearance.itemColor,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                )
                        }
                }
        }
}

@Composable
private fun SidebarQuickActionBadge(
        text: String,
        appearance: NavigationDrawerAppearance,
        selected: Boolean,
        modifier: Modifier = Modifier
) {
        Surface(
                modifier = modifier,
                shape = RoundedCornerShape(50),
                color =
                        if (selected) {
                                appearance.selectedContentColor.copy(alpha = 0.16f)
                        } else {
                                appearance.selectedContainerColor.copy(alpha = 0.18f)
                        }
        ) {
                Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 0.3.sp
                        ),
                        color =
                                if (selected) appearance.selectedContentColor
                                else appearance.titleColor.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                )
        }
}

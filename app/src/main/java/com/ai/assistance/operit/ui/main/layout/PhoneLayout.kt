package com.ai.assistance.operit.ui.main.layout

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.main.NavigationTransitionSource
import com.ai.assistance.operit.ui.main.TopBarTitleContent
import com.ai.assistance.operit.ui.main.navigation.NavigationEntrySpec
import com.ai.assistance.operit.ui.main.navigation.RouteEntry
import com.ai.assistance.operit.ui.main.components.AppContent
import com.ai.assistance.operit.ui.main.components.DrawerContent
import com.ai.assistance.operit.ui.main.components.RightSidebarContent
import com.ai.assistance.operit.ui.main.components.rememberNavigationDrawerAppearance
import com.ai.assistance.operit.ui.main.screens.GestureStateHolder
import com.ai.assistance.operit.ui.main.screens.Screen
import com.ai.assistance.operit.ui.theme.waterGlass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Layout for phone devices with a modal navigation drawer */
@Composable
fun PhoneLayout(
        currentRouteEntry: RouteEntry,
        currentScreen: Screen,
        selectedItem: NavItem?,
        isLoading: Boolean,
        navItems: List<NavItem>,
        pluginSidebarEntries: List<NavigationEntrySpec>,
        selectedRouteId: String,
        isNetworkAvailable: Boolean,
        networkType: String,
        drawerWidth: Dp,
        navController: androidx.navigation.NavController,
        scope: CoroutineScope,
        drawerState: androidx.compose.material3.DrawerState,
        showFpsCounter: Boolean,
        enableNavigationAnimation: Boolean,
        navigationTransitionSource: NavigationTransitionSource,
        onScreenChange: (Screen) -> Unit,
        onDrawerItemSelected: (Screen) -> Unit,
        onNavigationEntrySelected: (NavigationEntrySpec) -> Unit,
        onRightSidebarEntrySelected: (String) -> Unit,
        navigateToTokenConfig: () -> Unit,
        canGoBack: Boolean,
        onGoBack: () -> Unit,
        isNavigatingBack: Boolean = false,
        topBarActions: @Composable RowScope.() -> Unit = {},
        topBarTitleContent: TopBarTitleContent? = null,
        /** 当前导航栈中仍存活的路由 screenKey（路由级 ViewModelStore 清理依据）。 */
        aliveScreenKeys: Set<String>
) {
        // 使用 updateTransition 来创建更复杂的动画
        val transition = updateTransition(drawerState.targetValue, label = "drawer_transition")
        val drawerTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        val drawerProgress by
                transition.animateFloat(
                        label = "drawerProgress",
                        transitionSpec = {
                                if (targetState == DrawerValue.Open) {
                                        spring(
                                                dampingRatio = Spring.DampingRatioLowBouncy,
                                                stiffness = 1000f
                                        )
                                } else {
                                        spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = 1000f
                                        )
                                }
                        }
                ) { state -> if (state == DrawerValue.Open) 1f else 0f }

        val rightDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val rightTransition = updateTransition(rightDrawerState.targetValue, label = "right_drawer_transition")
        val rightDrawerProgress by
                rightTransition.animateFloat(
                        label = "rightDrawerProgress",
                        transitionSpec = {
                                if (targetState == DrawerValue.Open) {
                                        spring(
                                                dampingRatio = Spring.DampingRatioLowBouncy,
                                                stiffness = 1000f
                                        )
                                } else {
                                        spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = 1000f
                                        )
                                }
                        }
                ) { state -> if (state == DrawerValue.Open) 1f else 0f }

        // 抽屉动画状态
        val isDrawerOpen =
                drawerState.currentValue == DrawerValue.Open ||
                        drawerState.targetValue == DrawerValue.Open
        val isRightDrawerOpen =
                rightDrawerState.currentValue == DrawerValue.Open ||
                        rightDrawerState.targetValue == DrawerValue.Open
        val totalDrawerProgress = drawerProgress + rightDrawerProgress
        val netShiftProgress = drawerProgress - rightDrawerProgress

        val contentTranslationX =
                if (enableNavigationAnimation) {
                        drawerWidth * (0.82f * netShiftProgress)
                } else {
                        drawerWidth * netShiftProgress
                }
        val contentTranslationY =
                if (enableNavigationAnimation) 12.dp * totalDrawerProgress else 0.dp
        val contentScale =
                if (enableNavigationAnimation) 1f - (0.08f * totalDrawerProgress) else 1f
        val contentRotationY =
                if (enableNavigationAnimation) (-7f * drawerProgress) + (7f * rightDrawerProgress) else 0f
        val contentCornerRadius =
                if (enableNavigationAnimation) 24.dp * totalDrawerProgress else 0.dp
        val contentShadowElevation =
                if (enableNavigationAnimation) 18.dp * totalDrawerProgress else 0.dp

        val drawerOffset = -drawerWidth * (1f - drawerProgress)
        val rightDrawerOffset = drawerWidth * (1f - rightDrawerProgress)
        val sidebarElevation =
                if (enableNavigationAnimation) 16.dp * drawerProgress
                else 3.dp * drawerProgress
        val drawerScale =
                if (enableNavigationAnimation) 0.92f + (0.08f * drawerProgress)
                else 1f
        val drawerContentAlpha =
                if (enableNavigationAnimation) 0.72f + (0.28f * drawerProgress)
                else 0.8f + (0.2f * drawerProgress)
        val rightSidebarElevation =
                if (enableNavigationAnimation) 16.dp * rightDrawerProgress
                else 3.dp * rightDrawerProgress
        val rightDrawerScale =
                if (enableNavigationAnimation) 0.92f + (0.08f * rightDrawerProgress)
                else 1f
        val rightDrawerContentAlpha =
                if (enableNavigationAnimation) 0.72f + (0.28f * rightDrawerProgress)
                else 0.8f + (0.2f * rightDrawerProgress)
        val scrimColor = Color.Transparent

        // 侧边栏相关拖拽状态
        var currentDrag by remember { mutableStateOf(0f) }
        var verticalDrag by remember { mutableStateOf(0f) }
        val dragThreshold = 40f

        val drawerAppearance = rememberNavigationDrawerAppearance()
        val drawerShape =
                MaterialTheme.shapes.medium.copy(
                        topEnd = CornerSize(16.dp),
                        bottomEnd = CornerSize(16.dp),
                        topStart = CornerSize(0.dp),
                        bottomStart = CornerSize(0.dp)
                )
        val rightDrawerShape =
                MaterialTheme.shapes.medium.copy(
                        topStart = CornerSize(16.dp),
                        bottomStart = CornerSize(16.dp),
                        topEnd = CornerSize(0.dp),
                        bottomEnd = CornerSize(0.dp)
                )

        // 拖拽状态 - 用于控制抽屉拉出和关闭
        val draggableState = rememberDraggableState { delta ->
                // 如果内部手势已被消费，则不处理全局拖拽
                if (!GestureStateHolder.isChatScreenGestureConsumed) {
                        currentDrag += delta
                        val horizontalDominant = Math.abs(currentDrag) > Math.abs(verticalDrag)
                        val leftDrawerOpenNow =
                                drawerState.currentValue == DrawerValue.Open ||
                                        drawerState.targetValue == DrawerValue.Open
                        val rightDrawerOpenNow =
                                rightDrawerState.currentValue == DrawerValue.Open ||
                                        rightDrawerState.targetValue == DrawerValue.Open

                        if (!leftDrawerOpenNow && !rightDrawerOpenNow &&
                                        currentDrag > dragThreshold &&
                                        horizontalDominant
                        ) {
                                scope.launch {
                                        drawerState.open()
                                        currentDrag = 0f
                                        verticalDrag = 0f
                                }
                        }

                        if (!leftDrawerOpenNow && !rightDrawerOpenNow &&
                                        currentDrag < -dragThreshold &&
                                        horizontalDominant
                        ) {
                                scope.launch {
                                        rightDrawerState.open()
                                        currentDrag = 0f
                                        verticalDrag = 0f
                                }
                        }

                        if (leftDrawerOpenNow && currentDrag < -dragThreshold) {
                                scope.launch {
                                        drawerState.close()
                                        currentDrag = 0f
                                        verticalDrag = 0f
                                }
                        }

                        if (rightDrawerOpenNow && currentDrag > dragThreshold) {
                                scope.launch {
                                        rightDrawerState.close()
                                        currentDrag = 0f
                                        verticalDrag = 0f
                                }
                        }
                }
        }

        // 使用Box布局来手动控制抽屉和内容的位置关系
        Box(
                modifier =
                        Modifier.fillMaxSize()
                                .draggable(
                                        state = draggableState,
                                        orientation = Orientation.Horizontal,
                                        onDragStarted = {
                                                currentDrag = 0f
                                                verticalDrag = 0f
                                        },
                                        onDragStopped = {
                                                currentDrag = 0f
                                                verticalDrag = 0f
                                        }
                                )
                                .draggable(
                                        state =
                                                rememberDraggableState { delta ->
                                                        verticalDrag += delta
                                                },
                                        orientation = Orientation.Vertical,
                                        onDragStarted = { /* 不需要额外操作 */},
                                        onDragStopped = { /* 不需要额外操作 */}
                                )
        ) {
                // 主内容区域 - 使用自定义布局修饰符优化性能
                // 该修饰符只会影响布局，不会触发内容重组
                Surface(
                    modifier =
                            Modifier.fillMaxSize()
                                    .graphicsLayer {
                                            translationX = contentTranslationX.toPx()
                                            translationY = contentTranslationY.toPx()
                                            scaleX = contentScale
                                            scaleY = contentScale
                                            rotationY = contentRotationY
                                            transformOrigin =
                                                    if (rightDrawerProgress > drawerProgress) {
                                                            TransformOrigin(1f, 0.5f)
                                                    } else {
                                                            TransformOrigin(0f, 0.5f)
                                                    }
                                    }
                                    .zIndex(1f),
                    shape = RoundedCornerShape(contentCornerRadius),
                    color = Color.Transparent,
                    shadowElevation = contentShadowElevation
                ) {
                    // 普通调用AppContent，但由于我们的优化，它不会在动画时重组
                    AppContent(
                        currentRouteEntry = currentRouteEntry,
                        currentScreen = currentScreen,
                        selectedItem = selectedItem,
                        useTabletLayout = false,
                        isTabletSidebarExpanded = false,
                        isLoading = isLoading,
                        navController = navController,
                        scope = scope,
                        drawerState = drawerState,
                        showFpsCounter = showFpsCounter,
                        enableNavigationAnimation = enableNavigationAnimation,
                        navigationTransitionSource = navigationTransitionSource,
                        onScreenChange = onScreenChange,
                        onToggleSidebar = { /* Not used in phone layout */},
                        navigateToTokenConfig = navigateToTokenConfig,
                        canGoBack = canGoBack,
                        onGoBack = onGoBack,
                        isNavigatingBack = isNavigatingBack,
                        actions = topBarActions,
                        titleContent = topBarTitleContent,
                        aliveScreenKeys = aliveScreenKeys
                    )
                }

                // // 添加一个小方块，填充圆角和工具栏之间的空隙
                // Box(
                //         modifier =
                //                 Modifier.width(16.dp)
                //                         .height(64.dp)
                //                         .offset(x = drawerOffset + drawerWidth - 16.dp)
                //                         .background(MaterialTheme.colorScheme.primary)
                //                         .zIndex(1f)
                // )

                // 抽屉内容，从左侧滑动进入 - 使用缓存内容
                Surface(
                        modifier =
                                Modifier.width(drawerWidth)
                                        .padding(top = drawerTopInset)
                                        .fillMaxHeight()
                                        .graphicsLayer {
                                                translationX = drawerOffset.toPx()
                                                scaleX = drawerScale
                                                scaleY = drawerScale
                                                alpha = drawerContentAlpha
                                                transformOrigin = TransformOrigin(0f, 0.5f)
                                        }
                                        .waterGlass(
                                                enabled = drawerAppearance.waterGlassEnabled,
                                                shape = drawerShape,
                                                containerColor = drawerAppearance.containerColor,
                                                shadowElevation = sidebarElevation,
                                                borderWidth = 0.7.dp,
                                                overlayAlphaBoost =
                                                        if (enableNavigationAnimation) 0.07f
                                                        else 0.04f
                                        )
                                        .zIndex(2f),
                        shape = drawerShape,
                        color =
                                if (drawerAppearance.waterGlassEnabled) Color.Transparent
                                else drawerAppearance.containerColor,
                        shadowElevation = if (drawerAppearance.waterGlassEnabled) 0.dp else sidebarElevation
                ) {
                        DrawerContent(
                                navItems = navItems,
                                pluginEntries = pluginSidebarEntries,
                                selectedItem = selectedItem,
                                selectedRouteId = selectedRouteId,
                                isNetworkAvailable = isNetworkAvailable,
                                networkType = networkType,
                                appearance = drawerAppearance,
                                topContentPadding = 0.dp,
                                scope = scope,
                                drawerState = drawerState,
                                onScreenSelected = onDrawerItemSelected,
                                onNavigationEntrySelected = onNavigationEntrySelected
                        )
                }

                // 右侧「工具与设置」抽屉：从右边缘滑出（镜像左侧抽屉）
                Surface(
                        modifier =
                                Modifier.width(drawerWidth)
                                        .padding(top = drawerTopInset)
                                        .fillMaxHeight()
                                        .align(Alignment.CenterEnd)
                                        .graphicsLayer {
                                                translationX = rightDrawerOffset.toPx()
                                                scaleX = rightDrawerScale
                                                scaleY = rightDrawerScale
                                                alpha = rightDrawerContentAlpha
                                                transformOrigin = TransformOrigin(1f, 0.5f)
                                        }
                                        .waterGlass(
                                                enabled = drawerAppearance.waterGlassEnabled,
                                                shape = rightDrawerShape,
                                                containerColor = drawerAppearance.containerColor,
                                                shadowElevation = rightSidebarElevation,
                                                borderWidth = 0.7.dp,
                                                overlayAlphaBoost =
                                                        if (enableNavigationAnimation) 0.07f
                                                        else 0.04f
                                        )
                                        .zIndex(2f),
                        shape = rightDrawerShape,
                        color =
                                if (drawerAppearance.waterGlassEnabled) Color.Transparent
                                else drawerAppearance.containerColor,
                        shadowElevation = if (drawerAppearance.waterGlassEnabled) 0.dp else rightSidebarElevation
                ) {
                        RightSidebarContent(
                                onEntrySelected = { key ->
                                        scope.launch {
                                                rightDrawerState.close()
                                        }
                                        onRightSidebarEntrySelected(key)
                                }
                        )
                }

                // 在主内容上方放置遮罩层，阻止右侧内容继续响应点击
                if (isDrawerOpen) {
                        Box(
                                modifier = Modifier.fillMaxSize().zIndex(1.5f)
                        ) {
                                Box(
                                        modifier =
                                                Modifier.fillMaxSize()
                                                        .padding(start = drawerWidth)
                                                        .background(scrimColor)
                                                        .clickable(
                                                                interactionSource =
                                                                        remember {
                                                                                MutableInteractionSource()
                                                                        },
                                                                indication = null,
                                                                onClick = {
                                                                        scope.launch {
                                                                                drawerState.close()
                                                                        }
                                                                }
                                                        )
                                )
                        }
                }

                // 右侧抽屉遮罩：点击内容区域收起抽屉
                if (isRightDrawerOpen) {
                        Box(
                                modifier = Modifier.fillMaxSize().zIndex(1.5f)
                        ) {
                                Box(
                                        modifier =
                                                Modifier.fillMaxSize()
                                                        .padding(end = drawerWidth)
                                                        .background(scrimColor)
                                                        .clickable(
                                                                interactionSource =
                                                                        remember {
                                                                                MutableInteractionSource()
                                                                        },
                                                                indication = null,
                                                                onClick = {
                                                                        scope.launch {
                                                                                rightDrawerState.close()
                                                                        }
                                                                }
                                                        )
                                )
                        }
                }
        }
}

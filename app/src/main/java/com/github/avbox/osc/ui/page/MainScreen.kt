@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.avbox.osc.ui.page

import com.github.avbox.osc.api.AppBootstrap
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.avbox.osc.R
import com.github.avbox.osc.server.ControlManager
import com.github.avbox.osc.ui.HtmlWebActivity
import com.github.avbox.osc.ui.LivePlayActivity
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.components.LocalTopBarGlassPauseRecording
import com.github.avbox.osc.ui.components.LocalSheetDismissThen
import com.github.avbox.osc.ui.components.LocalSheetHost
import com.github.avbox.osc.ui.components.SheetHost
import com.github.avbox.osc.ui.components.SheetHostState
import com.github.avbox.osc.ui.currentWindowWidthClass
import com.github.avbox.osc.ui.navbar.FloatingNavBar
import com.github.avbox.osc.ui.navbar.GlassTabItem
import com.github.avbox.osc.ui.navbar.NavActionItem
import com.github.avbox.osc.ui.navbar.NavAxis
import com.github.avbox.osc.ui.navbar.NavMetrics
import com.github.avbox.osc.ui.settings.UiSettingsState
import com.github.avbox.osc.ui.theme.LiquidGlassState
import com.github.avbox.osc.util.ActivityStack
import com.github.avbox.osc.boot.BootGuard
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

private enum class AppTab(@StringRes val labelRes: Int, @DrawableRes val icon: Int) {
    HOME(R.string.tab_home, R.drawable.ic_tab_home),
    RECORDS(R.string.tab_records, R.drawable.ic_tab_records),
    FOLLOWING(R.string.tab_following, R.drawable.ic_tab_following),
    SETTINGS(R.string.tab_mine, R.drawable.ic_tab_mine),
}

// Bottom-nav slot layout, shared by the surface bars and the glass bar:
// 0 首页 · 1 网页 (action) · 2 直播 (action, unless hidden) · 3 追剧 · 4 我的.
// The web action sits in the slot the records tab used to occupy; records
// itself left the bar for the home top bar and still opens the same pager
// page (AppTab.RECORDS), so its destination and navigation are unchanged.
private const val NAV_WEB_ACTION_SLOT = 1
private const val NAV_LIVE_ACTION_SLOT = 2

@Composable
fun MainScreen() {
    LaunchedEffect(Unit) { AppBootstrap.start() }
    val boot by AppBootstrap.state.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        MainContent()
        if (boot is AppBootstrap.Boot.Error) {
            BootErrorDialog((boot as AppBootstrap.Boot.Error).msg)
        }
    }
}

@Composable
private fun BootErrorDialog(msg: String) {
    AVBoxAlertDialog(
        onDismissRequest = {},
        dismissible = false,
        title = { Text(stringResource(R.string.config_load_failed)) },
        text = { Text(msg) },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(onClick = { dismissThen { AppBootstrap.retry() } }) {
                Text(stringResource(R.string.common_retry))
            }
        },
        dismissButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(onClick = { dismissThen { AppBootstrap.continueOffline() } }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun MainContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { AppTab.entries.size })
    val homeViewModel: HomeViewModel = viewModel()

    LaunchedEffect(Unit) {
        if (homeViewModel.defaultLiveLaunched) return@LaunchedEffect
        AppBootstrap.state.collect { boot ->
            if (boot is AppBootstrap.Boot.Ready && !homeViewModel.defaultLiveLaunched) {
                homeViewModel.markDefaultLiveLaunched()
                val disabled = BootGuard.takeSafeDisabledNotice()
                if (disabled.isNotEmpty()) {
                    Toast.makeText(context, context.getString(R.string.toast_source_auto_disabled), Toast.LENGTH_LONG).show()
                }
                if (UiSettingsState.config.defaultLoadLive) {
                    context.startActivity(Intent(context, LivePlayActivity::class.java))
                }
            }
        }
    }

    LaunchedEffect(pagerState) {
        withFrameNanos { }
        withFrameNanos { }
        pagerState.scrollBy(1f)
        pagerState.scrollBy(-1f)
    }

    BackHandler {
        if (homeViewModel.onBackPressedOnce()) {
            ActivityStack.getInstance().finishAllActivity()
            ControlManager.get().stopServer()
            (context as? Activity)?.finishAffinity()
        } else {
            Toast.makeText(context, context.getString(R.string.toast_press_again_to_exit), Toast.LENGTH_SHORT).show()
        }
    }

    val sheetHost = remember { SheetHostState() }
    // A homePage site on the home tab opens full-screen: hide every nav bar
    // and drop the content reserve so the WebView fills the whole area.
    val homeWebFullScreen by homeViewModel.homeWebFullScreen.collectAsState()
    val webFullScreenActive = homeWebFullScreen && pagerState.targetPage == AppTab.HOME.ordinal
    val navConfig = UiSettingsState.config
    val navAnimationEnabled = !navConfig.navAnimationDisabled
    val navLiveHidden = navConfig.navLiveHidden

    val selectTab: (Int) -> Unit = { index ->
        scope.launch {
            if (navAnimationEnabled) {
                pagerState.animateScrollToPage(index)
            } else {
                pagerState.scrollToPage(index)
            }
        }
    }
    // Tabs actually rendered in the nav bars. RECORDS is not among them:
    // its bar slot now hosts the web action item, and the records page is
    // reached from the home top bar instead (still selectTab(RECORDS)).
    val navTabs = remember { listOf(AppTab.HOME, AppTab.FOLLOWING, AppTab.SETTINGS) }
    val navActionSlots: Set<Int> =
        if (navLiveHidden) setOf(NAV_WEB_ACTION_SLOT)
        else setOf(NAV_WEB_ACTION_SLOT, NAV_LIVE_ACTION_SLOT)
    val navSlotCount = navTabs.size + navActionSlots.size
    val openWeb: () -> Unit = remember(context) {
        { HtmlWebActivity.start(context) }
    }
    val liquidGlassConfig = LiquidGlassState.config
    val liquidGlassEnabled = liquidGlassConfig.navbarEnabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val liquidBackdropBgColor = MaterialTheme.colorScheme.surfaceContainer
    val liquidBackdropOnDraw: ContentDrawScope.() -> Unit =
        remember(liquidBackdropBgColor) {
            { drawRect(liquidBackdropBgColor); drawContent() }
        }
    val liquidBackdrop = rememberLayerBackdrop(onDraw = liquidBackdropOnDraw)
    val pauseGlassRecording: () -> Boolean = remember(pagerState, sheetHost) {
        { pagerState.isScrollInProgress || sheetHost.request != null }
    }
    val pauseTopBarRecording: () -> Boolean = remember(pagerState) {
        { pagerState.isScrollInProgress }
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val navAxis = NavMetrics.axisFor(currentWindowWidthClass())
    val railMode = navAxis == NavAxis.Vertical
    val surfaceNavVisible = !liquidGlassEnabled
    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBandExtent = NavMetrics.BAND_EXTENT_DP.dp
    val liquidBackdropBounds: (Size) -> Rect? = remember(density, navBandExtent, navAxis) {
        { size ->
            val extentPx = with(density) { navBandExtent.toPx() }
            if (navAxis == NavAxis.Horizontal) {
                Rect(0f, size.height - extentPx, size.width, size.height)
            } else {
                Rect(0f, 0f, extentPx, size.height)
            }
        }
    }
    val navReserve = NavMetrics.reserveDp(liquidGlassEnabled, navAxis).dp
    val pageContentPadding: PaddingValues = when {
        webFullScreenActive -> PaddingValues(0.dp)
        railMode -> PaddingValues(
            start = navReserve + navBarsPadding.calculateStartPadding(layoutDirection),
            bottom = navBarsPadding.calculateBottomPadding(),
        )

        liquidGlassEnabled -> PaddingValues(
            bottom = navBarsPadding.calculateBottomPadding() + navReserve,
        )

        else -> PaddingValues(0.dp)
    }
    val navTabLabels = navTabs.map { stringResource(it.labelRes) }
    val glassTabs = remember(navTabLabels) {
        navTabs.mapIndexed { index, tab -> GlassTabItem(tab.icon, navTabLabels[index]) }
    }
    val liveActionLabel = stringResource(R.string.common_live)
    val liveActionItem = remember(liveActionLabel) { GlassTabItem(R.drawable.ic_live_fab, liveActionLabel) }
    val openLive: () -> Unit = remember(context) {
        { context.startActivity(Intent(context, LivePlayActivity::class.java)) }
    }
    val webActionLabel = stringResource(R.string.html_web_entry)
    val webActionItem = remember(webActionLabel) { GlassTabItem(R.drawable.ic_tab_web, webActionLabel) }
    val navActionItems = remember(webActionItem, liveActionItem, navLiveHidden, openWeb, openLive) {
        buildList {
            add(NavActionItem(NAV_WEB_ACTION_SLOT, webActionItem, openWeb))
            if (!navLiveHidden) add(NavActionItem(NAV_LIVE_ACTION_SLOT, liveActionItem, openLive))
        }
    }
    CompositionLocalProvider(
        LocalSheetHost provides sheetHost,
        LocalTopBarGlassPauseRecording provides pauseTopBarRecording,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (surfaceNavVisible && !railMode && !webFullScreenActive) {
                        ShortNavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            for (slot in 0 until navSlotCount) {
                                when {
                                    slot == NAV_WEB_ACTION_SLOT -> ShortNavigationBarItem(
                                        selected = false,
                                        onClick = openWeb,
                                        icon = {
                                            Icon(
                                                painterResource(webActionItem.iconRes),
                                                contentDescription = webActionItem.label,
                                            )
                                        },
                                        label = null,
                                    )

                                    slot == NAV_LIVE_ACTION_SLOT && !navLiveHidden ->
                                        ShortNavigationBarItem(
                                            selected = false,
                                            onClick = openLive,
                                            icon = {
                                                Icon(
                                                    painterResource(liveActionItem.iconRes),
                                                    contentDescription = null,
                                                )
                                            },
                                            label = null,
                                        )

                                    else -> {
                                        val tab = navTabs[
                                            NavMetrics.tabIndexOfSlot(slot, navActionSlots)
                                        ]
                                        val selected = pagerState.targetPage == tab.ordinal
                                        ShortNavigationBarItem(
                                            selected = selected,
                                            onClick = { selectTab(tab.ordinal) },
                                            icon = {
                                                Icon(
                                                    painterResource(tab.icon),
                                                    contentDescription = stringResource(tab.labelRes),
                                                )
                                            },
                                            label = if (selected) {
                                                { Text(stringResource(tab.labelRes)) }
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (liquidGlassEnabled) {
                                Modifier.layerBackdrop(liquidBackdrop, liquidBackdropBounds, pauseGlassRecording)
                            } else {
                                Modifier
                            }
                        ),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = navAnimationEnabled,
                        beyondViewportPageCount = 3,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (liquidGlassEnabled) Modifier else Modifier.padding(innerPadding)),
                    ) { page ->
                        val pageLifecycleOwner = rememberLifecycleOwner(
                            maxLifecycle = if (page == pagerState.targetPage) {
                                Lifecycle.State.RESUMED
                            } else {
                                Lifecycle.State.STARTED
                            }
                        )
                        CompositionLocalProvider(LocalLifecycleOwner provides pageLifecycleOwner) {
                            when (AppTab.entries[page]) {
                                AppTab.HOME -> HomePage(
                                    homeViewModel,
                                    pageContentPadding,
                                    onOpenRecords = { selectTab(AppTab.RECORDS.ordinal) },
                                )
                                AppTab.RECORDS -> RecordsPage(contentPadding = pageContentPadding)
                                AppTab.FOLLOWING -> FollowingPage(contentPadding = pageContentPadding)
                                AppTab.SETTINGS -> SettingsPage(contentPadding = pageContentPadding)
                            }
                        }
                    }
                }
            }
            if (liquidGlassEnabled && !webFullScreenActive) {
                if (navAxis == NavAxis.Horizontal) {
                    val scrimColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(navBandExtent)
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to scrimColor)),
                    )
                }
                Box(
                    modifier = if (navAxis == NavAxis.Horizontal) {
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 16.dp)
                            .padding(bottom = NavMetrics.MARGIN_DP.dp)
                    } else {
                        Modifier
                            .align(Alignment.CenterStart)
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(vertical = 16.dp)
                            .padding(start = NavMetrics.MARGIN_DP.dp)
                    },
                ) {
                    FloatingNavBar(
                        backdrop = liquidBackdrop,
                        axis = navAxis,
                        // Bar-tab index of the current pager page; -1 while
                        // the records page (no longer a bar tab) is showing,
                        // so no bar item renders selected.
                        selectedTabIndex = {
                            navTabs.indexOfFirst { it.ordinal == pagerState.targetPage }
                        },
                        onTabSelected = { barIndex ->
                            navTabs.getOrNull(barIndex)?.let { selectTab(it.ordinal) }
                        },
                        tabs = glassTabs,
                        config = liquidGlassConfig,
                        interactive = { true },
                        actionItems = navActionItems,
                    )
                }
            }
            if (surfaceNavVisible && railMode && !webFullScreenActive) {
                NavigationRail(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.systemBars),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    for (slot in 0 until navSlotCount) {
                        when {
                            slot == NAV_WEB_ACTION_SLOT -> NavigationRailItem(
                                selected = false,
                                onClick = openWeb,
                                icon = {
                                    Icon(
                                        painterResource(webActionItem.iconRes),
                                        contentDescription = webActionItem.label,
                                    )
                                },
                                label = { Text(webActionItem.label) },
                            )

                            slot == NAV_LIVE_ACTION_SLOT && !navLiveHidden -> NavigationRailItem(
                                selected = false,
                                onClick = openLive,
                                icon = {
                                    Icon(
                                        painterResource(liveActionItem.iconRes),
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(liveActionItem.label) },
                            )

                            else -> {
                                val tab = navTabs[
                                    NavMetrics.tabIndexOfSlot(slot, navActionSlots)
                                ]
                                NavigationRailItem(
                                    selected = pagerState.currentPage == tab.ordinal,
                                    onClick = { selectTab(tab.ordinal) },
                                    icon = {
                                        Icon(
                                            painterResource(tab.icon),
                                            contentDescription = stringResource(tab.labelRes),
                                        )
                                    },
                                    label = { Text(stringResource(tab.labelRes)) },
                                )
                            }
                        }
                    }
                }
            }
            SheetHost(sheetHost)
        }
    }
}

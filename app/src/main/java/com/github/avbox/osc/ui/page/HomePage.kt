@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.github.avbox.osc.ui.page

import com.github.avbox.osc.api.SearchSourcesState
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.net.SiteSearch
import com.github.avbox.osc.ui.ConfigManageActivity
import com.github.avbox.osc.ui.HtmlWebActivity
import com.github.avbox.osc.ui.SearchActivity
import com.github.avbox.osc.ui.components.AVBoxBottomSheet
import com.github.avbox.osc.ui.components.AVBoxOptionMenuAction
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.LoadState
import com.github.avbox.osc.ui.components.ProvideTmdbPosterLookup
import com.github.avbox.osc.ui.components.LoadStateBox
import com.github.avbox.osc.ui.components.LocalSheetDismiss
import com.github.avbox.osc.ui.components.SearchField
import com.github.avbox.osc.ui.components.SearchSettingsSheet
import com.github.avbox.osc.ui.components.SettingsCard
import com.github.avbox.osc.ui.components.SettingsCardPosition
import com.github.avbox.osc.ui.components.SettingsGroup
import com.github.avbox.osc.ui.components.SettingsOptionRow
import com.github.avbox.osc.ui.components.SettingsRow
import com.github.avbox.osc.ui.components.VodCardMenu
import com.github.avbox.osc.ui.components.clearFocusOnDrag
import com.github.avbox.osc.ui.components.glassTopBarSurface
import com.github.avbox.osc.ui.components.rememberVodCardMenuState
import com.github.avbox.osc.ui.page.jumpToSearch
import com.github.avbox.osc.ui.theme.cardContainer
import com.kyant.capsule.ContinuousCapsule
import kotlin.math.roundToInt

private val HomeSourceCapsuleMaxWidth = 240.dp

private const val CapsuleLogoZoom = 1.5f

private val HomeTopBarControlSpacing = 8.dp

@Composable
fun HomePage(
    vm: HomeViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val context = LocalContext.current
    val currentSource by vm.currentSource.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val homePageUrl by vm.homePageUrl.collectAsStateWithLifecycle()
    val homePageTarget = homePageUrl
    val webFullScreen by vm.homeWebFullScreen.collectAsStateWithLifecycle()
    // The single HtmlWebView below registers itself here so the back
    // handler always reaches the live WebView.
    val webHolder = remember { HtmlWebHolder() }
    val vodMenu = rememberVodCardMenuState(
        lookupCollected = vm::isVideoCollected,
        onCollectToggle = vm::toggleVideoCollect,
    )

    val pullState = rememberPullToRefreshState()

    val pageLoading by vm.pageLoading.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.pageErrorEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(vm) {
        vm.toastEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(vm) {
        vm.actionMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.refreshPartitions()
        }
    }

    // Full-screen homePage site: back first walks the WebView history; at
    // the history root it exits full-screen (restoring the app bars) instead
    // of falling through to MainScreen's press-again-to-exit handler. This
    // handler is composed after MainScreen's BackHandler, so it wins while
    // enabled — the same pattern the other pages use for their modal states.
    BackHandler(enabled = homePageTarget != null && webFullScreen) {
        val webView = webHolder.webView
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            vm.exitHomeWebFullScreen()
        }
    }

    var showSourceSheet by remember { mutableStateOf(false) }
    var showSearchSettings by remember { mutableStateOf(false) }

    // One scaffold for every home state, full-screen or not: the homePage
    // WebView further down must stay at a single composition position,
    // because moving it (the pre-v1.6.3 if/else between a bare full-screen
    // WebView and the scaffold branch) disposed the AndroidView and
    // reloaded the root page on every full-screen toggle. Full-screen now
    // only hides this scaffold's top bar (its top padding drops to 0) and
    // MainScreen's bottom bars (the contentPadding bottom drops to 0) —
    // pure layout changes that leave the WebView instance, its current
    // page and its history untouched.
    AppTopBarScaffold(
        collapseEnabled = false,
        topBarVisible = homePageTarget == null || !webFullScreen,
        topBarStartInset = navStart,
        topScrimEnabled = homePageTarget == null || !webFullScreen,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContent = {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .widthIn(
                            max = minOf(
                                HomeSourceCapsuleMaxWidth,
                                maxWidth - HomeTopBarControlSpacing,
                            ),
                        )
                        .glassTopBarSurface(ContinuousCapsule, MaterialTheme.colorScheme.cardContainer)
                        .heightIn(min = 40.dp)
                        .combinedClickable(
                            onClick = {
                                vm.refreshSubscribes()
                                showSourceSheet = true
                            },
                            // Same refresh path as long-pressing a site in
                            // the source sheet: reload config, toast on land.
                            onLongClick = { vm.refreshConfig() },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val capsuleLogo = remember { ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground) }
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .drawBehind {
                                capsuleLogo?.let { drawable ->
                                    val w = size.width
                                    val h = size.height
                                    drawable.setBounds(
                                        (w / 2 - w * CapsuleLogoZoom / 2).roundToInt(),
                                        (h / 2 - h * CapsuleLogoZoom / 2).roundToInt(),
                                        (w / 2 + w * CapsuleLogoZoom / 2).roundToInt(),
                                        (h / 2 + h * CapsuleLogoZoom / 2).roundToInt(),
                                    )
                                    drawable.draw(drawContext.canvas.nativeCanvas)
                                }
                            },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = currentSource?.name?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.home_subscription_source),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        actions = {
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .glassTopBarSurface(ContinuousCapsule, MaterialTheme.colorScheme.surfaceBright),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { showSearchSettings = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.search_settings),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { HtmlWebActivity.start(context) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = stringResource(R.string.html_web_entry),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable {
                            context.startActivity(Intent(context, SearchActivity::class.java))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.common_search),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
    ) { topPad, _ ->
        when {
            pageLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(Modifier.size(64.dp))
                }
            }
            sources.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPad, bottom = navBottom),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_empty_record),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = stringResource(R.string.home_no_source),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { ConfigManageActivity.start(context) }) {
                            Text(stringResource(R.string.config_add_subscribe))
                        }
                    }
                }
            }
            homePageTarget != null -> {
                // homePage site (FongMi-style): the site's home is a web page.
                // Render it full-area; no category tabs/grid. This is the
                // WebView's only composition position: full-screen (the
                // default on activation) merely zeroes the paddings around
                // it (topPad from the hidden top bar, navBottom from
                // MainScreen), so entering/leaving full-screen never
                // recreates the WebView or reloads the page. The key only
                // changes when the site (and thus the URL) changes.
                key(homePageTarget) {
                    HtmlWebView(
                        url = homePageTarget,
                        holder = webHolder,
                        showLoadError = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topPad, bottom = navBottom),
                    )
                }
            }
            else -> {
                // Single home layout: top category tab row + one full-page grid for the
                // selected category (HomeGridLayout). The former stacked-sections home
                // (hero + one horizontal row per category) has been removed.
                ProvideTmdbPosterLookup(vm) {
                    HomeGridLayout(
                        vm = vm,
                        topPadding = topPad,
                        contentPadding = contentPadding,
                        pullState = pullState,
                        onCardClick = { video -> handleCardClick(vm, video, context) },
                        onCardLongClick = { video -> vodMenu.show(video) },
                    )
                }
            }
        }
    }

    if (showSourceSheet) {
        AVBoxBottomSheet(
            onDismissRequest = { showSourceSheet = false },
            modifier = Modifier.heightIn(max = (screenHeightDp * 0.9f).dp),
            title = stringResource(R.string.home_subscription_source),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            isScrollable = false,
        ) {
            val dismissAnimated = LocalSheetDismiss.current
            val keyboard = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            val subscribes by vm.subscribeItems.collectAsStateWithLifecycle()
            val activeSubscribeIndex by vm.activeSubscribeIndex.collectAsStateWithLifecycle()
            var query by remember { mutableStateOf("") }
            val filtered = remember(sources, query) { SiteSearch.filter(sources, query) }
            val subscribeOptions = remember(subscribes) { subscribes.map { it.name.ifEmpty { it.url } } }
            val listState = rememberLazyListState()
            val selectedIndex = filtered.indexOfFirst { it.key == currentSource?.key }
            LaunchedEffect(query.isEmpty()) {
                if (query.isEmpty() && selectedIndex > 0) listState.scrollToItem(selectedIndex)
            }
            fun switchSubscribe(item: SubscribeSource) {
                if (vm.isSubscribeDisabled(item)) {
                    Toast.makeText(context, R.string.toast_source_auto_disabled, Toast.LENGTH_LONG).show()
                } else {
                    dismissAnimated()
                    vm.switchSubscribe(item)
                }
            }
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { keyboard?.hide() },
                hint = stringResource(R.string.home_site_search_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                trailing = if (subscribeOptions.isEmpty()) {
                    null
                } else {
                    {
                        AVBoxOptionMenuAction(
                            options = subscribeOptions,
                            selectedIndex = activeSubscribeIndex,
                            onSelect = { index -> subscribes.getOrNull(index)?.let(::switchSubscribe) },
                            contentDescription = stringResource(R.string.home_subscription_source),
                        )
                    }
                },
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clearFocusOnDrag(focusManager)
                    .pointerInput(focusManager) {
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    },
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                if (filtered.isEmpty()) {
                    item {
                        LoadStateBox(
                            state = LoadState.Empty,
                            emptyText = stringResource(
                                if (query.isEmpty()) R.string.config_empty_subscribe else R.string.home_site_search_empty,
                            ),
                            errorText = "",
                            retryText = "",
                            emptyIconRes = R.drawable.ic_empty_record,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                        )
                    }
                }
                itemsIndexed(filtered) { index, bean ->
                    val selected = bean.key == currentSource?.key
                    SettingsCard(
                        position = when {
                            filtered.size == 1 -> SettingsCardPosition.SINGLE
                            index == 0 -> SettingsCardPosition.FIRST
                            index == filtered.size - 1 -> SettingsCardPosition.LAST
                            else -> SettingsCardPosition.MIDDLE
                        },
                        modifier = Modifier.padding(bottom = if (index == filtered.lastIndex) 0.dp else 2.dp),
                        color = MaterialTheme.colorScheme.surfaceBright,
                    ) {
                        SettingsOptionRow(
                            title = bean.name ?: bean.key.orEmpty(),
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    vm.switchSource(bean)
                                }
                                dismissAnimated()
                            },
                            onLongClick = {
                                // Refresh resources: reload the whole config
                                // (sites all come from it), keep the current
                                // site; the VM toasts once the reload lands.
                                dismissAnimated()
                                vm.refreshConfig()
                            },
                        )
                    }
                }
                item {
                    SettingsGroup(title = null, modifier = Modifier.padding(top = 20.dp)) {
                        SettingsCard(
                            position = SettingsCardPosition.SINGLE,
                            color = MaterialTheme.colorScheme.surfaceBright,
                        ) {
                            SettingsRow(
                                title = stringResource(R.string.settings_config_manage),
                                onClick = {
                                    dismissAnimated()
                                    ConfigManageActivity.start(context)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSearchSettings) {
        val searchSources by SearchSourcesState.state.collectAsStateWithLifecycle()
        SearchSettingsSheet(
            sources = searchSources.sources,
            selected = searchSources.pickedKeys,
            onApplySelection = { SearchSourcesState.applySelection(it) },
            onDismiss = { showSearchSettings = false },
        )
    }

    val menuContext = LocalContext.current
    VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
}

@Composable
fun HomePullRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    topPadding: Dp,
    modifier: Modifier = Modifier,
) {
    PullToRefreshDefaults.IndicatorBox(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier
            .offset(y = topPadding)
            .size(48.dp),
        shape = RectangleShape,
        containerColor = Color.Transparent,
        elevation = 0.dp,
    ) {
        Crossfade(targetState = isRefreshing) { refreshing ->
            if (refreshing) {
                ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
            } else {
                ContainedLoadingIndicator(
                    progress = { state.distanceFraction },
                    modifier = Modifier
                        .size(48.dp)
                        .drawWithContent {
                            val progress = state.distanceFraction
                            if (progress > 1f) {
                                rotate(degrees = -(progress - 1f) * 180f) {
                                    this@drawWithContent.drawContent()
                                }
                            } else {
                                drawContent()
                            }
                        },
                )
            }
        }
    }
}

private fun handleCardClick(vm: HomeViewModel, video: Movie.Video, context: android.content.Context) {
    context.dispatchVodCardClick(video, onAction = { vm.handleAction(it) })
}

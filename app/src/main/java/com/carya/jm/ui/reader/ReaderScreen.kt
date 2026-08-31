package com.carya.jm.ui.reader

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.carya.jm.data.model.Episode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    albumId: String,
    photoId: String,
    onBack: () -> Unit,
    episodes: List<Episode>? = null,
) {
    val context = LocalContext.current
    val viewModel: ReaderViewModel = viewModel(
        key = "reader_${albumId}_$photoId",
        factory = ReaderViewModel.factory(
            application = context.applicationContext as android.app.Application,
            albumId = albumId,
            photoId = photoId,
            episodes = episodes,
        ),
    )
    val state by viewModel.state.collectAsState()

    // ---- Immersive mode: hide system bars while reading --------------------
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        }
    }

    // ---- UI state ----------------------------------------------------------
    var uiVisible by rememberSaveable { mutableStateOf(true) }
    var showChapterSheet by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Scroll to top whenever a chapter finishes loading so the reader
    // always starts at the first page, not stuck in the middle.
    LaunchedEffect(state.currentEpisodeId, state.isLoading) {
        if (!state.isLoading) {
            listState.scrollToItem(0)
        }
    }

    // Track scroll position to prioritize image downloads around the
    // user's current reading position. When the user scrolls to a new
    // page, the ViewModel re-prioritizes the download queue so the
    // next few images are fetched first.
    LaunchedEffect(listState, state.currentEpisodeId) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                viewModel.updateVisibleIndex(index)
            }
    }

    // Reading progress: current page / total pages (0..1)
    val readingProgress by remember {
        derivedStateOf {
            val total = state.images.size
            if (total == 0) 0f else (listState.firstVisibleItemIndex + 1).toFloat() / total
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ---- Content states ------------------------------------------------
        when {
            state.isLoading -> {
                // 章节信息加载中：不整页遮罩动画，用顶部细进度条 + 轻提示，
                // 信息到达后图片立即流入（配合详情页预加载，通常瞬间完成）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(2.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.15f),
                    )
                    Text(
                        text = "正在加载章节信息…",
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.error!!,
                            color = Color.White,
                        )
                        IconButton(onClick = { viewModel.loadChapter(state.currentEpisodeId) }) {
                            Text("重试", color = Color.White)
                        }
                    }
                }
            }

            else -> {
                // ---- Comic pages (continuous vertical scroll) ----------------
                // Each item uses aspectRatio to reserve the correct height
                // BEFORE the image loads. When the image arrives, only the
                // content is replaced — the container size stays the same,
                // so the scroll position never jumps.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.images, key = { imgState ->
                        when (imgState) {
                            is ReaderImageState.Loading -> imgState.index
                            is ReaderImageState.Ready -> imgState.index
                            is ReaderImageState.Error -> imgState.index
                        }
                    }) { imgState ->
                        when (imgState) {
                            is ReaderImageState.Loading -> {
                                LoadingPlaceholder(
                                    aspectRatio = imgState.aspectRatio,
                                    onTap = { uiVisible = !uiVisible },
                                )
                            }
                            is ReaderImageState.Ready -> {
                                AsyncImage(
                                    model = imgState.path,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(imgState.aspectRatio)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) { uiVisible = !uiVisible },
                                )
                            }
                            is ReaderImageState.Error -> {
                                ErrorPlaceholder(
                                    message = imgState.message,
                                    onTap = { uiVisible = !uiVisible },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Top app bar (animated) ---------------------------------------
        AnimatedVisibility(
            visible = uiVisible && !state.isLoading && state.error == null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = viewModel.currentEpisodeTitle(),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.episodes.size > 1) {
                        IconButton(onClick = { showChapterSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = "选择章节",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---- Bottom reading progress bar (always visible, ultra-thin) ------
        if (!state.isLoading && state.error == null && state.images.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { readingProgress },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp),
                color = Color(0xFF000000),
                trackColor = Color.White.copy(alpha = 0.12f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
    if (showChapterSheet) {
        ChapterSelectorSheet(
            episodes = state.episodes,
            currentEpisodeId = state.currentEpisodeId,
            onDismiss = { showChapterSheet = false },
            onSelect = { ep ->
                showChapterSheet = false
                if (ep.id != state.currentEpisodeId) {
                    viewModel.loadChapter(ep.id)
                }
            },
        )
    }
}

/** Loading placeholder shown while an image is being downloaded.
 *  Uses [aspectRatio] to match the final image height exactly. */
@Composable
private fun LoadingPlaceholder(aspectRatio: Float, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .background(Color(0xFF1A1A1A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = Color.White.copy(alpha = 0.4f),
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** Error placeholder shown when an image fails to load. */
@Composable
private fun ErrorPlaceholder(message: String, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(Color(0xFF1A1A1A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSelectorSheet(
    episodes: List<Episode>,
    currentEpisodeId: String,
    onDismiss: () -> Unit,
    onSelect: (Episode) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Black.copy(alpha = 0.65f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()),
        ) {
            Text(
                text = "选择章节",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(
                        if (episodes.size > 10) 420.dp
                        else (episodes.size * 56).dp
                    ),
            ) {
                items(episodes, key = { it.id }) { ep ->
                    val isCurrent = ep.id == currentEpisodeId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                            .clickable { onSelect(ep) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCurrent)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface,
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ep.index,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isCurrent)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(width = 32.dp, height = 20.dp),
                            )
                            Text(
                                text = ep.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

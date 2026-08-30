package com.carya.jm.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carya.jm.data.model.ComicItem
import com.carya.jm.ui.components.BackToTopButton
import com.carya.jm.ui.components.ComicCard
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(
    onComicClick: (ComicItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = viewModel(
        factory = FavoritesViewModel.factory(
            LocalContext.current.applicationContext as android.app.Application,
        ),
    ),
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // --- Infinite scroll: trigger loadMore when near the bottom ---
    val shouldLoadMore by remember {
        derivedStateOf {
            if (state.items.isEmpty() || state.isLoading || state.isLoadingMore || !state.hasMore) {
                false
            } else {
                val layoutInfo = gridState.layoutInfo
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalItems = layoutInfo.totalItemsCount
                lastVisibleIndex >= totalItems - 4
            }
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadMore()
        }
    }

    // --- Back-to-top visibility ---
    val showBackToTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = 20.dp,
                vertical = 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: back button + title + refresh
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Text(
                            text = "我的收藏",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    if (state.total > 0) {
                        Text(
                            text = "共 ${state.total} 部",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !state.isLoading,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }

            // --- Initial loading state ---
            if (state.isLoading && state.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            // --- Error state ---
            if (state.error != null && state.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = state.error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { viewModel.loadFavorites() }) {
                            Text("重试")
                        }
                    }
                }
            }

            // --- Empty state ---
            if (!state.isLoading && state.items.isEmpty() && state.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无收藏",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // --- Comic cards ---
            items(
                items = state.items,
                key = { it.id },
            ) { item ->
                ComicCard(
                    title = item.title,
                    author = item.author,
                    coverUrl = item.coverUrl,
                    onClick = { onComicClick(item) },
                )
            }

            // --- Footer: loading more indicator ---
            if (state.isLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            // --- Footer: no more content ---
            if (!state.hasMore && state.items.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "没有更多了",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // --- Back-to-top floating button ---
        BackToTopButton(
            visible = showBackToTop,
            onClick = {
                scope.launch { gridState.animateScrollToItem(0) }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

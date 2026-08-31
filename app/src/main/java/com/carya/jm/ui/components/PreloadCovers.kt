package com.carya.jm.ui.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/**
 * Calculates the target image size for comic cover thumbnails.
 * Returns (width, height) in pixels at 2x the display card size,
 * shared by [PreloadCovers] and [ComicCard] so cache entries match.
 *
 * Layout assumptions: 20dp horizontal padding × 2 + 12dp gap.
 */
@Composable
fun rememberCardImageSize(columns: Int = 2): Pair<Int, Int> {
    val context = LocalContext.current
    return remember(columns) {
        val dm = context.resources.displayMetrics
        val density = dm.density
        val screenWidth = dm.widthPixels
        val totalPaddingPx = ((20 * 2 + 12) * density).toInt()
        val cardWidthPx = ((screenWidth - totalPaddingPx) / columns).coerceAtLeast(1)
        val cardHeightPx = (cardWidthPx / 0.75f).toInt()
        val imgW = (cardWidthPx * 2).coerceAtLeast(200)
        val imgH = (cardHeightPx * 2).coerceAtLeast(267)
        imgW to imgH
    }
}

/**
 * Preloads cover images for items outside the visible viewport.
 *
 * Key optimizations:
 * - **Velocity-aware**: preload count scales with scroll speed (6→12→24→36)
 * - **Bidirectional**: preloads ahead when scrolling down, behind when up
 * - **Smart cancellation**: only cancels requests that left the preload window,
 *   in-progress requests for items still in range continue running
 * - **URL dedup**: never enqueues the same URL twice
 * - **Size-limited**: images loaded at 2× display card size, not full resolution
 */
@Composable
fun PreloadCovers(
    gridState: LazyGridState,
    coverUrls: List<String>,
    headerCount: Int,
    preloadCount: Int = 6,
    columns: Int = 2,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val activeRequests = remember { mutableMapOf<String, Disposable>() }
    val (imgW, imgH) = rememberCardImageSize(columns)

    // Cancel all preload requests when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            activeRequests.values.forEach { it.dispose() }
            activeRequests.clear()
        }
    }

    LaunchedEffect(gridState, coverUrls.size) {
        var prevLastIndex = -1
        var prevTimeNs = 0L

        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            val first = info.visibleItemsInfo.minOfOrNull { it.index } ?: -1
            Pair(first, last)
        }.distinctUntilChanged().conflate().collect { (firstVisible, lastVisible) ->
            if (lastVisible < 0 || coverUrls.isEmpty()) return@collect

            // ── Scroll velocity (items / second) ──
            val nowNs = System.nanoTime()
            val timeDeltaMs = if (prevTimeNs > 0) (nowNs - prevTimeNs) / 1_000_000 else 0L
            val indexDelta = if (prevLastIndex >= 0) abs(lastVisible - prevLastIndex) else 0
            val itemsPerSec = if (timeDeltaMs > 0)
                (indexDelta.toFloat() / timeDeltaMs * 1000).toInt() else 0

            // ── Dynamic preload count ──
            //  slow (≤3/s)→6  normal (≤10/s)→12  fast (≤20/s)→24  very fast→36
            val dynamicCount = when {
                itemsPerSec <= 3 -> preloadCount
                itemsPerSec <= 10 -> preloadCount * 2
                itemsPerSec <= 20 -> preloadCount * 4
                else -> preloadCount * 6
            }

            // ── Scroll direction ──
            val scrollingDown = prevLastIndex < 0 || lastVisible >= prevLastIndex

            prevLastIndex = lastVisible
            prevTimeNs = nowNs

            // ── Clean up completed requests (Coil's isDisposed
            //    returns true once the underlying job finishes) ──
            activeRequests.entries.removeAll { it.value.isDisposed }

            // ── Calculate preload range based on direction ──
            val lastComicIndex = lastVisible - headerCount
            val firstComicIndex = firstVisible - headerCount
            val rangeStart: Int
            val rangeEnd: Int

            if (scrollingDown) {
                rangeStart = (lastComicIndex + 1).coerceAtLeast(0)
                rangeEnd = minOf(lastComicIndex + dynamicCount, coverUrls.lastIndex)
            } else {
                rangeStart = maxOf(0, firstComicIndex - dynamicCount)
                rangeEnd = (firstComicIndex - 1).coerceAtLeast(0)
            }

            if (rangeStart > rangeEnd) return@collect

            // ── Determine URLs in the new range ──
            val newUrls = (rangeStart..rangeEnd).mapNotNull { i ->
                coverUrls.getOrNull(i)?.takeIf { it.isNotBlank() }
            }.toSet()

            // ── Cancel requests for URLs that left the preload window ──
            activeRequests.keys.filter { it !in newUrls }.forEach { url ->
                activeRequests.remove(url)?.dispose()
            }

            // ── Enqueue new requests (deduplicated) ──
            for (i in rangeStart..rangeEnd) {
                val url = coverUrls.getOrNull(i) ?: continue
                if (url.isBlank() || url in activeRequests) continue

                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(imgW, imgH)
                    .build()
                val disposable = imageLoader.enqueue(request)
                activeRequests[url] = disposable
            }
        }
    }
}

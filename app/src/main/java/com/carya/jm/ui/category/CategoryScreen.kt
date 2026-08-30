package com.carya.jm.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Grouped category page mirroring the site's sidebar sections. */
@Composable
fun CategoryScreen(
    onCategoryClick: (label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "分类",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(vertical = 16.dp),
            )
        }

        // Group sections: title + entry cards
        for (group in CATEGORY_GROUPS) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "group_${group.title}") {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(
                items = group.entries,
                key = { "${group.title}_${it.label}" },
            ) { entry ->
                CategoryCard(
                    label = entry.label,
                    onClick = { onCategoryClick(entry.label) },
                )
            }
        }

        // Bottom spacing so the last row clears the nav bar comfortably
        item(span = { GridItemSpan(maxLineSpan) }, key = "footer_spacer") {
            Box(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CategoryCard(
    label: String,
    onClick: () -> Unit,
) {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
    )
    val color = palette[Math.abs(label.hashCode()) % palette.size]

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

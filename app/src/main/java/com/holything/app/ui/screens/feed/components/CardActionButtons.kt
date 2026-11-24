package com.holything.app.ui.screens.feed.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CardActionButtons(
    isAmened: Boolean,
    isSaved: Boolean,
    onAmenToggle: () -> Unit,
    onSaveToggle: () -> Unit,
    onShare: () -> Unit,
    onMoreLikeThis: () -> Unit = {},
    onLessLikeThis: () -> Unit = {},
    onReflect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val heartScale by animateFloatAsState(
        targetValue = if (isAmened) 1.2f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "heartScale"
    )

    val heartColor by animateColorAsState(
        targetValue = if (isAmened) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface,
        label = "heartColor"
    )

    val bookmarkColor by animateColorAsState(
        targetValue = if (isSaved) Color(0xFFF57F17) else MaterialTheme.colorScheme.onSurface,
        label = "bookmarkColor"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Amen (Heart with small centered "Amen" label below)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(
                onClick = onAmenToggle,
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isAmened) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isAmened) "Remove Amen" else "Amen (Heart)",
                    tint = heartColor,
                    modifier = Modifier
                        .size(24.dp)
                        .scale(heartScale)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Amen",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (isAmened) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }

        // 2. Save (Bookmark)
        IconButton(
            onClick = onSaveToggle,
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f), CircleShape)
        ) {
            Icon(
                imageVector = if (isSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = if (isSaved) "Remove from Saved" else "Save Scripture",
                tint = bookmarkColor,
                modifier = Modifier.size(24.dp)
            )
        }

        // 3. Share
        IconButton(
            onClick = onShare,
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = "Share Scripture",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }

        // 4. Reflect (Meditation / Prayer)
        IconButton(
            onClick = onReflect,
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = "Pause and Reflect",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

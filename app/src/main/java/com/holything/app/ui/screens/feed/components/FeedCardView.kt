package com.holything.app.ui.screens.feed.components

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.holything.app.domain.model.FeedCardItem
import kotlinx.coroutines.delay

@Composable
fun FeedCardView(
    item: FeedCardItem,
    isCurrentPage: Boolean,
    onAmenToggle: () -> Unit,
    onSaveToggle: () -> Unit,
    onShare: () -> Unit,
    onMoreLikeThis: () -> Unit,
    onLessLikeThis: () -> Unit,
    onReflect: () -> Unit,
    onWhyThisCardClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val card = item.card
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    // Detect system "Reduce Motion" setting
    val isReduceMotion = remember {
        try {
            val animatorScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            val transitionScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f
            )
            animatorScale == 0f || transitionScale == 0f
        } catch (e: Exception) {
            false
        }
    }

    // Track revealed state per card so scrolling backwards doesn't re-delay or jump
    var hasBeenRevealed by rememberSaveable(card.id) { mutableStateOf(isReduceMotion) }
    var showVerse by rememberSaveable(card.id) { mutableStateOf(isReduceMotion || hasBeenRevealed) }
    var showInsight by rememberSaveable(card.id) { mutableStateOf(isReduceMotion || hasBeenRevealed) }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage && !hasBeenRevealed) {
            if (isReduceMotion) {
                showVerse = true
                showInsight = true
                hasBeenRevealed = true
            } else {
                delay(700)
                showVerse = true
                delay(500)
                showInsight = true
                hasBeenRevealed = true
            }
        }
    }

    // Alpha animations preserve exact layout measurement so content never jumps
    val verseAlpha by animateFloatAsState(
        targetValue = if (showVerse || hasBeenRevealed) 1f else 0f,
        animationSpec = tween(durationMillis = if (isReduceMotion) 0 else 500),
        label = "verseAlpha"
    )

    val insightAlpha by animateFloatAsState(
        targetValue = if (showInsight || hasBeenRevealed) 1f else 0f,
        animationSpec = tween(durationMillis = if (isReduceMotion) 0 else 500),
        label = "insightAlpha"
    )

    val backgroundBrush = remember(card.tone, isDark) {
        CardBackgroundGradients.getGradient(card.tone, isDark)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        // Main Card Content Column - stable layout with no height collapses
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 56.dp, bottom = 48.dp), // Room for right action buttons and bottom label
            verticalArrangement = Arrangement.Center
        ) {
            // 1. Subtle Format & Series Header Badge
            CardFormatBadge(
                format = card.format,
                seriesOrder = card.seriesOrder,
                seriesTotal = item.seriesTotalCount
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Hook Line (Large typography, appears immediately)
            Text(
                text = card.hook,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { traversalIndex = 1f }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Verse Reference & Scripture Text (Fades in without layout jump)
            Column(
                modifier = Modifier
                    .graphicsLayer { alpha = verseAlpha }
                    .semantics { traversalIndex = 2f }
            ) {
                Text(
                    text = "${card.verseRef}  •  ${card.translation}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "“${card.verseText}”",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Plain-Language Insight (Fades in without layout jump)
            if (card.insight.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = insightAlpha }
                        .semantics { traversalIndex = 3f }
                ) {
                    Text(
                        text = card.insight,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        // Right-Side Action Buttons
        CardActionButtons(
            isAmened = item.isAmened,
            isSaved = item.isSaved,
            onAmenToggle = onAmenToggle,
            onSaveToggle = onSaveToggle,
            onShare = onShare,
            onMoreLikeThis = onMoreLikeThis,
            onLessLikeThis = onLessLikeThis,
            onReflect = onReflect,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(bottom = 32.dp)
                .semantics { traversalIndex = 4f }
        )

        // Bottom "Why this card" Honest Label
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .clickable { onWhyThisCardClick() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = item.recommendationReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun CardFormatBadge(
    format: String,
    seriesOrder: Int?,
    seriesTotal: Int?
) {
    val (label, icon) = when (format.lowercase()) {
        "story" -> {
            val partText = if (seriesOrder != null && seriesTotal != null) {
                "STORY • PART $seriesOrder OF $seriesTotal"
            } else if (seriesOrder != null) {
                "STORY • PART $seriesOrder"
            } else {
                "STORY"
            }
            partText to Icons.AutoMirrored.Filled.MenuBook
        }
        "didyouknow" -> "DID YOU KNOW" to Icons.Filled.AutoAwesome
        "question" -> "REFLECTION QUESTION" to Icons.AutoMirrored.Filled.HelpOutline
        "testimony" -> "TESTIMONY" to Icons.Filled.RecordVoiceOver
        else -> "SCRIPTURE" to Icons.Filled.Psychology
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

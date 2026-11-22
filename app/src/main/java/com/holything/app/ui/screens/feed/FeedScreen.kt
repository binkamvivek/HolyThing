package com.holything.app.ui.screens.feed

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.holything.app.domain.model.Card
import com.holything.app.ui.screens.feed.components.FeedCardView
import com.holything.app.ui.screens.feed.components.ReflectBottomSheet
import com.holything.app.ui.screens.feed.components.WhyThisCardDialog

@Composable
fun FeedScreen(
    viewModel: FeedViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }
    var activeReflectCard by remember { mutableStateOf<Card?>(null) }
    var activeWhyReason by remember { mutableStateOf<String?>(null) }

    // Listen to user feedback events (e.g. "Saved to library", "More like this")
    LaunchedEffect(Unit) {
        viewModel.userFeedbackEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                // 1. Loading State
                uiState.isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Opening Scripture...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 2. Empty State
                uiState.items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No Scripture Cards Yet",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "We couldn't reach the content library. Check your internet connection or try syncing.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { viewModel.syncContent() }) {
                            Text("Retry Sync")
                        }
                    }
                }

                // 3. Main Full-Screen Vertical Pager
                else -> {
                    val pagerState = rememberPagerState(
                        initialPage = 0,
                        pageCount = { uiState.items.size }
                    )

                    // Track page changes for dwell time
                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.currentPage }.collect { pageIndex ->
                            viewModel.onPageChanged(pageIndex)
                        }
                    }

                    VerticalPager(
                        state = pagerState,
                        beyondViewportPageCount = 2, // Smooth preloading for mid-range devices
                        modifier = Modifier.fillMaxSize()
                    ) { pageIndex ->
                        val item = uiState.items.getOrNull(pageIndex)
                        if (item != null) {
                            FeedCardView(
                                item = item,
                                isCurrentPage = pagerState.currentPage == pageIndex,
                                onAmenToggle = { viewModel.onAmenToggle(item.card.id) },
                                onSaveToggle = { viewModel.onSaveToggle(item.card.id) },
                                onShare = {
                                    viewModel.onShare(item.card)
                                    val shareText = "${item.card.hook}\n\n“${item.card.verseText}”\n— ${item.card.verseRef} (${item.card.translation})\n\nRead Scripture on HolyThing"
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                        type = "text/plain"
                                    }
                                    val chooser = Intent.createChooser(sendIntent, "Share Scripture")
                                    context.startActivity(chooser)
                                },
                                onMoreLikeThis = { viewModel.onMoreLikeThis(item.card.id) },
                                onLessLikeThis = { viewModel.onLessLikeThis(item.card.id) },
                                onReflect = {
                                    viewModel.onReflect(item.card.id)
                                    activeReflectCard = item.card
                                },
                                onWhyThisCardClick = { activeWhyReason = item.recommendationReason }
                            )
                        }
                    }
                }
            }

            // Subtle Offline Indicator if serving cached or fallback content offline
            if (uiState.isOffline && uiState.items.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = "Offline mode",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = "Offline Mode",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Modal Reflection Sheet
    activeReflectCard?.let { card ->
        ReflectBottomSheet(
            card = card,
            onDismiss = { activeReflectCard = null }
        )
    }

    // Why This Card Dialog
    activeWhyReason?.let { reason ->
        WhyThisCardDialog(
            reason = reason,
            onDismiss = { activeWhyReason = null }
        )
    }
}

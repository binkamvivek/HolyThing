package com.holything.app.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.holything.app.core.signal.SignalCaptureManager
import com.holything.app.data.local.room.entity.SignalEventEntity
import com.holything.app.domain.state.UserStateConfig
import com.holything.app.domain.state.UserStateEstimate
import java.util.Locale

@Composable
fun FeedDebugDialog(
    userState: UserStateEstimate,
    recentEvents: List<SignalEventEntity>,
    recentServedCards: List<SignalCaptureManager.ServedCardDebugInfo>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Feed & State Debug",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Session Mode: ${userState.sessionMode.name}  |  Confidence: ${String.format(Locale.US, "%.0f%%", userState.confidence * 100f)}  |  Late Night: ${userState.isLateNight}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Mood Belief",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                UserStateConfig.CARD_MOODS.forEach { mood ->
                    val prob = (userState.moodBelief[mood] ?: 0f).coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = mood.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(68.dp)
                        )
                        LinearProgressIndicator(
                            progress = { prob },
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = String.format(Locale.US, "%.0f%%", prob * 100f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last ${recentEvents.size} Signal Events",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (recentEvents.isEmpty()) {
                    Text(
                        text = "No signal events recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    recentEvents.take(20).forEach { evt ->
                        val actions = if (evt.actionsTaken.isEmpty()) "-" else evt.actionsTaken.joinToString(",")
                        Text(
                            text = "${evt.cardId}: score=${String.format(Locale.US, "%+.2f", evt.engagementScore)}, dwell=${String.format(Locale.US, "%.2f", evt.dwellRatio)}, speed=${evt.scrollSpeedBucket}, actions=$actions",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last ${recentServedCards.size} Served Card Reasons",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (recentServedCards.isEmpty()) {
                    Text(
                        text = "No served cards recorded in this session yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    recentServedCards.take(20).forEach { served ->
                        Text(
                            text = "${served.cardId} (${served.verseRef}): ${served.reason}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

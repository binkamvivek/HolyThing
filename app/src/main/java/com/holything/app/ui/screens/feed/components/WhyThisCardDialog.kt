package com.holything.app.ui.screens.feed.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun WhyThisCardDialog(
    reason: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Why you're seeing this",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Text(
                text = "$reason.\n\nHolyThing curates your feed to help you read and reflect on Scripture without addictive tricks. No ads, no data tracking, and no selling your reading habits—everything is personalized privately on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

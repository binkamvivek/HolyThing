package com.holything.app.domain.model

sealed interface SyncResult {
    data class Synced(val updatedCount: Int, val version: String) : SyncResult
    data class UpToDate(val version: String) : SyncResult
    data class FallbackLoaded(val count: Int, val version: String) : SyncResult
    data class Offline(val localCount: Int, val version: String?) : SyncResult
}


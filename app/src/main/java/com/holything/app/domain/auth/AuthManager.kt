package com.holything.app.domain.auth

import kotlinx.coroutines.flow.Flow

/**
 * Authentication and identity abstraction.
 * Currently provides an anonymous on-device identity (UUID),
 * structured so Google Sign-In and Cloud Backup can be plugged in later without architectural rewrites.
 */
interface AuthManager {
    val userId: Flow<String>
    val isAnonymous: Flow<Boolean>

    suspend fun getOrCreateUserId(): String
    suspend fun linkGoogleAccount(idToken: String): Result<Unit> // Future Phase stub
    suspend fun signOut(): Result<Unit> // Future Phase stub
}

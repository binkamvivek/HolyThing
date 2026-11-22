package com.holything.app.data.auth

import com.holything.app.data.local.datastore.UserPreferencesDataStore
import com.holything.app.domain.auth.AuthManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalAuthManager @Inject constructor(
    private val dataStore: UserPreferencesDataStore
) : AuthManager {

    override val userId: Flow<String> = dataStore.anonymousUserId.map { storedId ->
        storedId ?: getOrCreateUserId()
    }

    override val isAnonymous: Flow<Boolean> = flowOf(true)

    override suspend fun getOrCreateUserId(): String {
        return dataStore.getOrCreateAnonymousId {
            "anon_${UUID.randomUUID()}"
        }
    }

    override suspend fun linkGoogleAccount(idToken: String): Result<Unit> {
        // Architecture placeholder for future Google Sign-In & backup sync phase
        return Result.success(Unit)
    }

    override suspend fun signOut(): Result<Unit> {
        // Architecture placeholder for future multi-account phase
        return Result.success(Unit)
    }
}

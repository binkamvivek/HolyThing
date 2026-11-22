package com.holything.app.data.remote

import com.holything.app.data.remote.dto.SyncResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

interface HolyThingApiService {
    @GET("exec")
    suspend fun getCards(
        @Query("since") sinceVersion: String? = null
    ): SyncResponseDto
}


package com.holything.app.di

import com.holything.app.data.auth.LocalAuthManager
import com.holything.app.domain.auth.AuthManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindAuthManager(localAuthManager: LocalAuthManager): AuthManager
}

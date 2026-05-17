package com.fubon.daytrade.di

import com.fubon.daytrade.data.repository.FubonRepository
import com.fubon.daytrade.data.repository.FubonRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindFubonRepository(
        impl: FubonRepositoryImpl
    ): FubonRepository
}
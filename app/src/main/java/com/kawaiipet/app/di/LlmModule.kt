package com.kawaiipet.app.di

import com.kawaiipet.app.llm.LlmService
import com.kawaiipet.app.llm.SmolLmLlmService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {

    @Binds
    @Singleton
    abstract fun bindLlmService(impl: SmolLmLlmService): LlmService
}

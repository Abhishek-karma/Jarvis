package com.jarvis.core.ml

import android.content.Context
import com.jarvis.core.common.DispatcherProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalMlModule {
    @Provides
    @Singleton
    fun provideCatalog(
        @ApplicationContext context: Context,
    ): LocalModelCatalog =
        LocalModelCatalog(
            source = { runCatching { context.assets.open(CATALOG_ASSET) }.getOrNull() },
        )

    @Provides
    @Singleton
    fun provideModelStore(
        catalog: LocalModelCatalog,
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
    ): LocalModelStore =
        LocalModelStore(
            catalog = catalog,
            modelsDir = File(context.filesDir, MODELS_DIR),
            openAsset = { name -> runCatching { context.assets.open("$DEV_ASSETS_DIR/$name") }.getOrNull() },
            okHttpClient = okHttpClient,
            dispatchers = dispatchers,
        )

    @Provides
    @Singleton
    fun provideLocalConnectivity(
        @ApplicationContext context: Context,
    ): LocalConnectivity = AndroidLocalConnectivity(context)

    @Provides
    @Singleton
    fun provideLocalLlmRuntime(
        @ApplicationContext context: Context,
        store: LocalModelStore,
        dispatchers: DispatcherProvider,
    ): LocalLlmRuntime =
        LocalLlmRuntime(
            appContext = context,
            store = store,
            dispatchers = dispatchers,
        )

    private const val CATALOG_ASSET = "local-models.json"
    private const val MODELS_DIR = "local-llm"
    private const val DEV_ASSETS_DIR = "models-dev"
}

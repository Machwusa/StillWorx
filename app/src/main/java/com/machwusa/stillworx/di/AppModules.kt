package com.machwusa.stillworx.di

import android.content.Context
import androidx.room.Room
import com.machwusa.stillworx.BuildConfig
import com.machwusa.stillworx.data.local.AppDatabase
import com.machwusa.stillworx.data.local.TaskDao
import com.machwusa.stillworx.data.repository.LocalChangeNotifier
import com.machwusa.stillworx.data.repository.TaskRepositoryImpl
import com.machwusa.stillworx.domain.repository.SyncStatusRepository
import com.machwusa.stillworx.domain.repository.TaskRepository
import com.machwusa.stillworx.sync.AutomergeCrdtStore
import com.machwusa.stillworx.sync.CrdtStore
import com.machwusa.stillworx.sync.SyncCoordinator
import com.machwusa.stillworx.sync.SyncTransport
import com.machwusa.stillworx.sync.WebSocketSyncTransport
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
internal object InfrastructureModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "stillworx.db").build()

    @Provides
    fun provideTaskDao(database: AppDatabase): TaskDao = database.taskDao()

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    @Provides
    @Singleton
    fun provideCrdtStore(@ApplicationContext context: Context): CrdtStore =
        AutomergeCrdtStore(File(context.filesDir, "crdt/${BuildConfig.SYNC_BOARD_ID}.automerge"))

    @Provides
    @Singleton
    fun provideSyncTransport(
        client: OkHttpClient,
        scope: CoroutineScope,
    ): SyncTransport = WebSocketSyncTransport(
        client = client,
        serverUrl = BuildConfig.SYNC_SERVER_URL,
        boardId = BuildConfig.SYNC_BOARD_ID,
        scope = scope,
    )
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class BindingModule {
    @Binds
    abstract fun bindTaskRepository(implementation: TaskRepositoryImpl): TaskRepository

    @Binds
    abstract fun bindLocalChangeNotifier(coordinator: SyncCoordinator): LocalChangeNotifier

    @Binds
    abstract fun bindSyncStatusRepository(coordinator: SyncCoordinator): SyncStatusRepository
}

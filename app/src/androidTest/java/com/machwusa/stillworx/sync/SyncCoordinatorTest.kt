package com.machwusa.stillworx.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.machwusa.stillworx.data.local.AppDatabase
import com.machwusa.stillworx.domain.model.ConnectionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncCoordinatorTest {
    @Test
    fun reconnectStagesRemoteDocumentUntilUserApprovesRoomUpdate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "coordinator-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val remote = AutomergeCrdtStore(File(directory, "remote.automerge"))
        remote.load()
        remote.applyLocal(listOf(SyncedTask("remote", "From device B", "DONE", 10, false)))
        val remoteBytes = remote.documentBytes()
        remote.close()

        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val transport = FakeTransport()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = SyncCoordinator(
            database.taskDao(),
            AutomergeCrdtStore(File(directory, "local.automerge")),
            transport,
            scope,
        )

        withTimeout(5_000) { transport.connectionState.first { it == ConnectionState.CONNECTED } }
        withTimeout(5_000) { transport.sendCount.first { it > 0 } }
        transport.disconnectAndReconnect()
        withTimeout(5_000) { coordinator.syncApprovalRequired.first { it } }
        transport.receive(remoteBytes)

        assertEquals(0, database.taskDao().getAll().size)
        coordinator.approveSync()
        val roomTasks = withTimeout(5_000) {
            database.taskDao().observeActive().first { it.any { task -> task.id == "remote" } }
        }

        assertEquals("From device B", roomTasks.single().title)
        assertEquals("DONE", roomTasks.single().column)
        coordinator.stop()
        scope.cancel()
        database.close()
    }
}

private class FakeTransport : SyncTransport {
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val manualReconnect = MutableStateFlow(false)
    private val sends = MutableStateFlow(0)
    private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    override val connectionState = state.asStateFlow()
    override val manualReconnectRequired = manualReconnect.asStateFlow()
    val sendCount = sends.asStateFlow()
    override val incomingDocuments = incoming.asSharedFlow()

    override fun connect() {
        state.value = ConnectionState.CONNECTED
    }

    override fun retryConnection() = connect()

    override fun send(document: ByteArray): Boolean {
        sends.value += 1
        return true
    }

    override fun close() {
        state.value = ConnectionState.DISCONNECTED
    }

    suspend fun disconnectAndReconnect() {
        state.value = ConnectionState.DISCONNECTED
        yield()
        state.value = ConnectionState.CONNECTING
        yield()
        state.value = ConnectionState.CONNECTED
    }

    suspend fun receive(document: ByteArray) {
        incoming.emit(document)
    }
}

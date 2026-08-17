package com.machwusa.stillworx.sync

import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.automerge.Read

internal class AutomergeCrdtStore(
    private val documentFile: File,
) : CrdtStore {
    private val mutex = Mutex()
    private var document: Document? = null

    override suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(document == null) { "CRDT store is already loaded" }
            document = if (documentFile.exists() && documentFile.length() > 0L) {
                Document.load(documentFile.readBytes())
            } else {
                Document()
            }
        }
    }

    override suspend fun applyLocal(tasks: List<SyncedTask>) = mutex.withLock {
        if (tasks.isEmpty()) return@withLock
        val doc = requireDocument()
        doc.startTransaction().use { transaction ->
            val tasksObject = transaction.mapObject(ObjectId.ROOT, Schema.TASKS)
                ?: transaction.set(ObjectId.ROOT, Schema.TASKS, ObjectType.MAP)

            tasks.forEach { task ->
                val taskObject = transaction.mapObject(tasksObject, task.id)
                    ?: transaction.set(tasksObject, task.id, ObjectType.MAP)
                transaction.setIfDifferent(taskObject, Schema.ID, task.id)
                transaction.setIfDifferent(taskObject, Schema.TITLE, task.title)
                transaction.setIfDifferent(taskObject, Schema.COLUMN, task.column)
                transaction.setIfDifferent(taskObject, Schema.UPDATED_AT, Date(task.updatedAt))
                transaction.setIfDifferent(taskObject, Schema.DELETED, task.deleted)
            }
            transaction.commit()
        }
        Unit
    }

    override suspend fun merge(remoteDocument: ByteArray) = mutex.withLock {
        val remote = Document.load(remoteDocument)
        try {
            requireDocument().merge(remote)
        } finally {
            remote.free()
        }
    }

    override suspend fun tasks(): List<SyncedTask> = mutex.withLock {
        val doc = requireDocument()
        val tasksObject = doc.mapObject(ObjectId.ROOT, Schema.TASKS) ?: return@withLock emptyList()
        val keys = doc.keys(tasksObject).orElse(null) ?: return@withLock emptyList()
        buildList {
            for (taskId in keys) {
                val taskObject = doc.mapObject(tasksObject, taskId) ?: continue
                logConflicts(doc, taskObject, taskId)
                val id = doc.string(taskObject, Schema.ID) ?: taskId
                val title = doc.string(taskObject, Schema.TITLE) ?: continue
                val column = doc.string(taskObject, Schema.COLUMN) ?: continue
                val updatedAt = doc.timestamp(taskObject, Schema.UPDATED_AT) ?: 0L
                val deleted = doc.boolean(taskObject, Schema.DELETED) ?: false
                add(SyncedTask(id, title, column, updatedAt, deleted))
            }
        }
    }

    override suspend fun documentBytes(): ByteArray = mutex.withLock { requireDocument().save() }

    override suspend fun persist() = withContext(Dispatchers.IO) {
        mutex.withLock {
            documentFile.parentFile?.mkdirs()
            val temporary = File(documentFile.parentFile, "${documentFile.name}.tmp")
            temporary.writeBytes(requireDocument().save())
            Files.move(
                temporary.toPath(),
                documentFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Unit
        }
    }

    override suspend fun close() = mutex.withLock {
        document?.free()
        document = null
    }

    private fun requireDocument() = checkNotNull(document) { "CRDT store has not been loaded" }

    private fun logConflicts(doc: Document, taskObject: ObjectId, taskId: String) {
        listOf(Schema.TITLE, Schema.COLUMN, Schema.UPDATED_AT, Schema.DELETED).forEach { field ->
            val conflicts = doc.getAll(taskObject, field).orElse(null) ?: return@forEach
            if (conflicts.values().size > 1) {
                Log.w(TAG, "Concurrent values for task=$taskId field=$field: ${conflicts.values()}")
            }
        }
    }

    private fun Read.mapObject(parent: ObjectId, key: String): ObjectId? =
        (get(parent, key).orElse(null) as? AmValue.Map)?.id

    private fun Read.string(parent: ObjectId, key: String): String? =
        (get(parent, key).orElse(null) as? AmValue.Str)?.value

    private fun Read.timestamp(parent: ObjectId, key: String): Long? =
        (get(parent, key).orElse(null) as? AmValue.Timestamp)?.value?.time

    private fun Read.boolean(parent: ObjectId, key: String): Boolean? =
        (get(parent, key).orElse(null) as? AmValue.Bool)?.value

    private fun org.automerge.Transaction.setIfDifferent(parent: ObjectId, key: String, value: String) {
        if (string(parent, key) != value) set(parent, key, value)
    }

    private fun org.automerge.Transaction.setIfDifferent(parent: ObjectId, key: String, value: Date) {
        if (timestamp(parent, key) != value.time) set(parent, key, value)
    }

    private fun org.automerge.Transaction.setIfDifferent(parent: ObjectId, key: String, value: Boolean) {
        if (boolean(parent, key) != value) set(parent, key, value)
    }

    private object Schema {
        const val TASKS = "tasks"
        const val ID = "id"
        const val TITLE = "title"
        const val COLUMN = "column"
        const val UPDATED_AT = "updatedAt"
        const val DELETED = "deleted"
    }

    private companion object {
        const val TAG = "AutomergeCrdtStore"
    }
}

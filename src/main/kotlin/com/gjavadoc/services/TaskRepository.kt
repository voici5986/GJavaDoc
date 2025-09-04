package com.gjavadoc.services

import com.gjavadoc.bus.GJavaDocBusTopics
import com.gjavadoc.model.*
import com.gjavadoc.settings.SettingsState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@State(name = "GJavaDocTaskHistory", storages = [Storage("gjavadoc_tasks.xml")])
@Service(Service.Level.PROJECT)
class TaskRepository(private val project: Project) : PersistentStateComponent<TaskRepository.State> {
    data class Snapshot(
        var taskId: String = "",
        var classFqn: String = "",
        var method: String = "",
        var file: String = "",
        var line: Int = 1,
        var annotation: String = "",
        var createdAt: Long = 0,
        var status: String = TaskStatus.QUEUED.name,
        var progress: Double = 0.0,
        var message: String? = null,
        var scope: String = TaskScope.METHOD.name,
        var cgSummary: String? = null,
        var jsonPath: String? = null,
        var mdPath: String? = null,
        var ctxPath: String? = null,
        var errorType: String? = null,
        var errorMessage: String? = null,
        var attempt: Int = 0,
    )

    data class State(var snapshots: MutableList<Snapshot> = mutableListOf())

    private val tasks = LinkedHashMap<String, TaskModel>()
    private var state: State = State()

    fun all(): List<TaskModel> = synchronized(tasks) { tasks.values.toList() }

    fun upsert(task: TaskModel) {
        synchronized(tasks) { tasks[task.taskId] = task }
        saveSnapshot(task)
        project.messageBus.syncPublisher(GJavaDocBusTopics.TASK_EVENTS).onUpdated(task)
    }

    fun enqueued(task: TaskModel) {
        synchronized(tasks) { tasks[task.taskId] = task }
        saveSnapshot(task)
        project.messageBus.syncPublisher(GJavaDocBusTopics.TASK_EVENTS).onEnqueued(task)
    }

    fun started(task: TaskModel) {
        synchronized(tasks) { tasks[task.taskId] = task }
        saveSnapshot(task)
        project.messageBus.syncPublisher(GJavaDocBusTopics.TASK_EVENTS).onStarted(task)
    }

    fun finished(task: TaskModel) {
        synchronized(tasks) { tasks[task.taskId] = task }
        saveSnapshot(task)
        project.messageBus.syncPublisher(GJavaDocBusTopics.TASK_EVENTS).onFinished(task)
    }

    private fun saveSnapshot(task: TaskModel) {
        val s = snapshotOf(task)
        val limit = SettingsState.getInstance(project).state.persist.historyLimit
        synchronized(state) {
            state.snapshots.removeAll { it.taskId == s.taskId }
            state.snapshots.add(s)
            while (state.snapshots.size > limit) state.snapshots.removeAt(0)
        }
    }

    fun clearAll() {
        synchronized(tasks) { tasks.clear() }
        synchronized(state) { state.snapshots.clear() }
        // Trigger UI refresh by publishing an update event with a dummy task
        val dummy = TaskModel(
            taskId = "__reset__",
            entry = EntryPoint(classFqn = "", method = "", file = "", line = 1, annotation = ""),
            status = TaskStatus.CANCELLED,
        )
        project.messageBus.syncPublisher(GJavaDocBusTopics.TASK_EVENTS).onUpdated(dummy)
    }

    private fun snapshotOf(task: TaskModel): Snapshot = Snapshot(
        taskId = task.taskId,
        classFqn = task.entry.classFqn,
        method = task.entry.method,
        file = task.entry.file,
        line = task.entry.line,
        annotation = task.entry.annotation,
        createdAt = task.createdAt,
        status = task.status.name,
        progress = task.progress.fraction,
        message = task.progress.message,
        scope = task.scope.name,
        cgSummary = task.cgSummary,
        jsonPath = task.result?.jsonPath,
        mdPath = task.result?.mdPath,
        ctxPath = task.result?.ctxPath,
        errorType = task.error?.type,
        errorMessage = task.error?.message,
        attempt = task.attempt,
    )

    private fun restore(snapshot: Snapshot): TaskModel = TaskModel(
        taskId = snapshot.taskId,
        entry = EntryPoint(snapshot.classFqn, snapshot.method, snapshot.file, snapshot.line, snapshot.annotation),
        createdAt = snapshot.createdAt,
        status = TaskStatus.valueOf(snapshot.status),
        scope = TaskScope.valueOf(snapshot.scope),
        progress = TaskProgress(snapshot.progress, snapshot.message),
        cgSummary = snapshot.cgSummary,
        sliceAnchors = null,
        result = TaskResult(snapshot.jsonPath, snapshot.mdPath, snapshot.ctxPath),
        error = if (snapshot.errorType != null) TaskError(snapshot.errorType!!, snapshot.errorMessage ?: "") else null,
        attempt = snapshot.attempt,
    )

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        synchronized(tasks) {
            tasks.clear()
            for (s in state.snapshots) {
                val t = restore(s)
                tasks[t.taskId] = t
            }
        }
    }
}

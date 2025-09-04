package com.gjavadoc.bus

import com.gjavadoc.model.TaskModel
import com.intellij.util.messages.Topic

interface TaskEventsListener {
    fun onEnqueued(task: TaskModel) {}
    fun onStarted(task: TaskModel) {}
    fun onUpdated(task: TaskModel) {}
    fun onFinished(task: TaskModel) {}
}

// Queue-level events to reflect running/stopped state and live metrics
data class QueueStatus(
    val running: Boolean,
    val runningCount: Int,
    val maxConcurrent: Int,
    val backlogSize: Int,
    val queueSize: Int,
    val queueRemainingCapacity: Int,
    val requestsPerSecond: Double,
)

interface QueueEventsListener {
    fun onQueueStarted() {}
    fun onQueueStopped() {}
    fun onQueueHeartbeat(status: QueueStatus) {}
}

object GJavaDocBusTopics {
    val TASK_EVENTS: Topic<TaskEventsListener> = Topic.create("GJavaDocTaskEvents", TaskEventsListener::class.java)
    val QUEUE_EVENTS: Topic<QueueEventsListener> = Topic.create("GJavaDocQueueEvents", QueueEventsListener::class.java)
}

package com.gjavadoc.queue

import com.gjavadoc.model.TaskModel
import com.gjavadoc.model.TaskStatus
import com.gjavadoc.settings.SettingsState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal queue skeleton with coarse concurrency limiter.
 * Rate limiting and retries to be added per design.
 */
class TaskQueue(private val project: Project) {
    private val log = Logger.getInstance(TaskQueue::class.java)
    private val running = AtomicBoolean(false)
    private val queue = ConcurrentLinkedQueue<TaskModel>()
    private val permits = Semaphore(SettingsState.getInstance(project).state.maxConcurrentRequests)

    fun addAll(tasks: Collection<TaskModel>) {
        queue.addAll(tasks)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        log.info("GJavaDoc queue started with ${permits.availablePermits()} concurrency")
        drain()
    }

    fun stop() {
        running.set(false)
    }

    private fun drain() {
        while (running.get()) {
            val next = queue.poll() ?: break
            permits.acquire()
            next.status = TaskStatus.RUNNING
            // For now, immediately mark as PARTIAL to visualize
            try {
                next.status = TaskStatus.PARTIAL
            } finally {
                permits.release()
            }
        }
    }
}


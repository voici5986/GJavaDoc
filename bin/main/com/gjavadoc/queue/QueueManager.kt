package com.gjavadoc.queue

import com.gjavadoc.analysis.CGSliceFactory
import com.gjavadoc.analysis.CGSliceResult
import com.gjavadoc.bus.GJavaDocBusTopics
import com.gjavadoc.bus.QueueStatus
import com.gjavadoc.io.OutputWriter
import com.gjavadoc.context.ContextPackager
import com.gjavadoc.llm.LLMClient
import com.gjavadoc.llm.LLMClientFactory
import com.gjavadoc.model.*
import com.gjavadoc.services.TaskRepository
import com.gjavadoc.settings.SettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

@Service(Service.Level.PROJECT)
class QueueManager(private val project: Project) {
    private val log = Logger.getInstance(QueueManager::class.java)
    private val running = AtomicBoolean(false)
    private var scheduler: ScheduledExecutorService? = null
    // Use AtomicInteger for thread-safe concurrency accounting
    private val runningCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val repo = project.getService(TaskRepository::class.java)
    private val settings get() = SettingsState.getInstance(project).state
    @Volatile private var queue: ArrayBlockingQueue<TaskModel>? = null
    private val indicators = ConcurrentHashMap<String, ProgressIndicator>()
    // Hard concurrency limiter in addition to runningCount-based gating
    // Ensures we never oversubscribe even if there are races.
    @Volatile private var permits = java.util.concurrent.Semaphore(SettingsState.getInstance(project).state.maxConcurrentRequests, true)
    private val backlog = ConcurrentLinkedQueue<TaskModel>()
    @Volatile private var lastHeartbeatNs: Long = 0L

    private fun ensureQueue(): ArrayBlockingQueue<TaskModel> {
        var q = queue
        if (q == null) {
            q = ArrayBlockingQueue(settings.queueSize)
            queue = q
        }
        return q
    }

    fun enqueueAll(entries: List<EntryPoint>) {
        ensureQueue()
        for (e in entries) {
            val id = e.classFqn + "#" + e.method + "#" + System.currentTimeMillis()
            val t = TaskModel(taskId = id, entry = e)
            backlog.add(t)
            repo.enqueued(t)
        }
    }

    fun enqueueTasks(tasks: List<TaskModel>) {
        ensureQueue()
        for (t in tasks) {
            val withId = if (t.taskId.isBlank()) t.copy(taskId = t.entry.classFqn + "#" + t.entry.method + "#" + System.currentTimeMillis()) else t
            backlog.add(withId)
            repo.enqueued(withId)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        // Reset concurrency limiter to current settings on every start
        permits = java.util.concurrent.Semaphore(settings.maxConcurrentRequests, true)
        val rps = settings.requestsPerSecond
        val periodMs = if (rps <= 0.0) 1000L else (1000.0 / rps).toLong().coerceAtLeast(1L)
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler!!.scheduleAtFixedRate({ tick() }, 0, periodMs, TimeUnit.MILLISECONDS)
        log.info("GJavaDoc QueueManager started. RPS=$rps period=${periodMs}ms")
        publishStarted()
        publishHeartbeat()
    }

    fun stop() {
        running.set(false)
        scheduler?.shutdownNow()
        scheduler = null
        log.info("GJavaDoc QueueManager stopped.")
        publishStopped()
    }

    fun clearQueue() {
        val q = ensureQueue()
        q.clear()
        backlog.clear()
        runningCount.set(0)
        log.info("GJavaDoc queue cleared")
    }

    /**
     * 自动恢复未完成的任务：包含 QUEUED / RUNNING(残留) / PARTIAL。
     * 不恢复 SUCCEEDED / CANCELLED / FAILED。
     */
    fun autoResumePending() {
        ensureQueue()
        try {
            val all = repo.all()
            val toResume = all.filter { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING || it.status == TaskStatus.PARTIAL }
            if (toResume.isEmpty()) return
            for (t in toResume) {
                // 将状态复位为 QUEUED，保留错误信息便于追踪
                t.status = TaskStatus.QUEUED
                t.progress = TaskProgress(0.0, "Auto-resume")
                repo.upsert(t)
            }
            enqueueTasks(toResume)
            if (!running.get()) start() else publishHeartbeat()
            log.info("GJavaDoc auto resumed ${toResume.size} pending tasks")
        } catch (e: Throwable) {
            log.warn("autoResumePending failed", e)
        }
    }

    private fun tick() {
        if (!running.get()) return
        refreshLimiterIfChanged()
        val q = ensureQueue()
        // Fill bounded queue from backlog up to remaining capacity
        var rem = q.remainingCapacity()
        while (rem > 0) {
            val next = backlog.poll() ?: break
            if (q.offer(next)) rem-- else break
        }
        // Derive capacity from actual running indicators to avoid drift across threads
        val capacity = settings.maxConcurrentRequests - indicators.size
        if (capacity <= 0) return
        var dispatched = 0
        while (dispatched < capacity) {
            val task = q.poll() ?: break
            // Double guard: also acquire a permit; if we cannot, push it back and stop dispatching now.
            if (!permits.tryAcquire()) {
                // No permit available — return task to the head (best-effort) and break
                q.offer(task)
                break
            }
            executeTask(task)
            dispatched++
        }
        // Emit a lightweight heartbeat so UI can reflect queue state (throttled)
        publishHeartbeat()
    }

    private fun executeTask(task: TaskModel) {
        val client: LLMClient = LLMClientFactory.create(project)
        val cg = CGSliceFactory.create(project)
        val out = OutputWriter(project)
        val packager = ContextPackager(project)

        runningCount.incrementAndGet()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "GJavaDoc: ${task.entry.classFqn}#${task.entry.method}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicators[task.taskId] = indicator
                updateStatus(task, TaskStatus.RUNNING, 0.05, "Analyzing")
                try {
                    if (indicator.isCanceled) throw InterruptedException("Canceled")
                    val analysis: CGSliceResult = cg.analyze(task.entry)
                    task.cgSummary = analysis.summary
                    task.sliceAnchors = analysis.anchors.map { it.file to (it.startLine..it.endLine) }
                    updateStatus(task, TaskStatus.RUNNING, 0.35, "Generating")

                    if (indicator.isCanceled) throw InterruptedException("Canceled")
                    val ctxRel = "context-bundles/${safe(task.taskId)}.txt"
                    val ctx = if (task.scope == TaskScope.CLASS) packager.buildForClass(task.entry, analysis, ctxRel) else packager.build(task.entry, analysis, ctxRel)
                    val doc = client.generate(task.entry, analysis, ctx.text)

                    updateStatus(task, TaskStatus.RUNNING, 0.65, "Writing output")
                    if (indicator.isCanceled) throw InterruptedException("Canceled")

                    val jsonPath = if (doc.json.isNotBlank()) out.writeRelative("method-docs/${safe(task.taskId)}.json", doc.json) else null
                    val mdClean = unwrapMarkdownFence(doc.markdown)
                    val modFolder = moduleFolderFor(task.entry)
                    val mdRel = if (modFolder != null) "docs/${modFolder}/${safe(task.taskId)}.md" else "docs/${safe(task.taskId)}.md"
                    val mdPath = out.writeRelative(mdRel, mdClean)

                    task.result = TaskResult(jsonPath = jsonPath, mdPath = mdPath, ctxPath = ctx.path)
                    updateStatus(task, TaskStatus.SUCCEEDED, 1.0, "Done")
                    repo.finished(task)
                } catch (e: InterruptedException) {
                    task.status = TaskStatus.CANCELLED
                    task.error = TaskError("CANCELLED", e.message ?: "Cancelled")
                    repo.finished(task)
                } catch (t: Throwable) {
                    handleFailure(task, t)
                } finally {
                    runningCount.decrementAndGet()
                    // Release hard limiter permit
                    permits.release()
                    indicators.remove(task.taskId)
                    // Emit heartbeat after a task finishes to refresh counts promptly
                    publishHeartbeat()
                }
            }
        })
    }

    private fun handleFailure(task: TaskModel, t: Throwable) {
        val st = settings
        if (st.retry.enabled && task.attempt + 1 < st.retry.maxAttempts) {
            task.attempt += 1
            task.status = TaskStatus.PARTIAL
            task.error = TaskError(t::class.java.simpleName, t.message ?: "error")
            repo.upsert(task)
            scheduler?.schedule({
                task.status = TaskStatus.QUEUED
                repo.upsert(task)
                backlog.add(task)
            }, st.retry.backoffMs, TimeUnit.MILLISECONDS)
        } else {
            task.status = TaskStatus.FAILED
            task.error = TaskError(t::class.java.simpleName, t.message ?: "error")
            repo.finished(task)
        }
    }

    private fun updateStatus(task: TaskModel, status: TaskStatus, fraction: Double, message: String) {
        task.status = status
        task.progress = TaskProgress(fraction, message)
        repo.upsert(task)
        // Push a heartbeat immediately so UI Running count stays in sync with table updates
        publishHeartbeat()
    }

    private fun safe(id: String): String = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private fun moduleFolderFor(entry: EntryPoint): String? {
        val st = settings
        if (!st.groupDocsByModule) return null
        return try {
            val vf = LocalFileSystem.getInstance().findFileByPath(entry.file) ?: return null
            val mod = ModuleUtilCore.findModuleForFile(vf, project) ?: return null
            safe(mod.name)
        } catch (_: Throwable) { null }
    }

    // If markdown is wrapped by a ```markdown ... ``` fence, strip the outer fence.
    private fun unwrapMarkdownFence(md: String): String {
        val text = md.trim('\uFEFF', ' ', '\n', '\r', '\t')
        val lines = text.lines()
        if (lines.size >= 2) {
            val first = lines.first().trim()
            val last = lines.last().trim()
            if (first == "```markdown" && last == "```") {
                return lines.subList(1, lines.size - 1).joinToString("\n").trimEnd()
            }
        }
        return md
    }

    fun cancelTask(taskId: String) {
        indicators[taskId]?.cancel()
    }

    fun cancelAllRunning() {
        for ((_, ind) in indicators) {
            ind.cancel()
        }
    }

    private fun publishStarted() {
        project.messageBus.syncPublisher(GJavaDocBusTopics.QUEUE_EVENTS).onQueueStarted()
    }

    private fun publishStopped() {
        project.messageBus.syncPublisher(GJavaDocBusTopics.QUEUE_EVENTS).onQueueStopped()
    }

    private fun publishHeartbeat() {
        val q = ensureQueue()
        val now = System.nanoTime()
        // throttle to at most 4 updates/second
        if (now - lastHeartbeatNs >= 250_000_000L) {
            lastHeartbeatNs = now
            // Derive a more user-facing running count from repository to avoid UI mismatch
            val repoRunning = try {
                repo.all().count { it.status == TaskStatus.RUNNING }
            } catch (_: Throwable) { 0 }
            val actualRunning = indicators.size
            val displayRunning = kotlin.math.max(actualRunning, repoRunning)
            val status = QueueStatus(
                running = running.get(),
                runningCount = displayRunning,
                maxConcurrent = settings.maxConcurrentRequests,
                backlogSize = backlog.size,
                queueSize = q.size,
                queueRemainingCapacity = q.remainingCapacity(),
                requestsPerSecond = settings.requestsPerSecond,
            )
            project.messageBus.syncPublisher(GJavaDocBusTopics.QUEUE_EVENTS).onQueueHeartbeat(status)
        }
    }

    private fun refreshLimiterIfChanged() {
        // Adjust semaphore permits to reflect current maxConcurrentRequests from settings.
        // We cannot reduce below the number of currently running tasks; extra reduction will happen as tasks complete.
        val desired = settings.maxConcurrentRequests
        val currentRunning = runningCount.get()
        val currentTotal = permits.availablePermits() + currentRunning
        if (desired > currentTotal) {
            // Need to add permits
            permits.release(desired - currentTotal)
        } else if (desired < currentTotal) {
            // Need to reduce available permits to (desired - running), but not below zero.
            val targetAvailable = (desired - currentRunning).coerceAtLeast(0)
            val toAcquire = permits.availablePermits() - targetAvailable
            if (toAcquire > 0) {
                // Acquire from ourselves to reduce availability
                repeat(toAcquire) { permits.tryAcquire() }
            }
        }
    }
}

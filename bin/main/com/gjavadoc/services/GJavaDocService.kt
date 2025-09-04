package com.gjavadoc.services

import com.gjavadoc.queue.QueueManager
import com.gjavadoc.scan.EntryScanner
import com.intellij.notification.NotificationType
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.application.ReadAction
import com.gjavadoc.util.MethodCategory
import com.gjavadoc.util.classifyMethodName
import com.gjavadoc.settings.SettingsState

@Service(Service.Level.PROJECT)
class GJavaDocService(private val project: Project) {
    private val log = Logger.getInstance(GJavaDocService::class.java)
    private val queue = project.getService(QueueManager::class.java)
    private val repo = project.getService(TaskRepository::class.java)

    fun startFullScan(crudOverride: SettingsState.CrudFilter? = null, moduleName: String? = null) {
        Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "开始扫描入口（注解：" + com.gjavadoc.settings.SettingsState.getInstance(project).state.annotation + ")", NotificationType.INFORMATION), project)
        ProgressManager.getInstance().run(object: Task.Backgroundable(project, "GJavaDoc: 扫描入口", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "等待索引完成..."
                val entries = DumbService.getInstance(project).runReadActionInSmartMode<List<com.gjavadoc.model.EntryPoint>> {
                    indicator.checkCanceled()
                    indicator.text = "扫描注解入口..."
                    ReadAction.compute<List<com.gjavadoc.model.EntryPoint>, RuntimeException> {
                        val targetModule = (moduleName ?: com.gjavadoc.settings.SettingsState.getInstance(project).state.ui.lastModule)
                        val scope = try {
                            if (!targetModule.isNullOrBlank() && targetModule != "ALL") {
                                val m = com.intellij.openapi.module.ModuleManager.getInstance(project).findModuleByName(targetModule)
                                if (m != null) com.intellij.psi.search.GlobalSearchScope.moduleScope(m)
                                else com.intellij.psi.search.GlobalSearchScope.projectScope(project)
                            } else com.intellij.psi.search.GlobalSearchScope.projectScope(project)
                        } catch (_: Throwable) { com.intellij.psi.search.GlobalSearchScope.projectScope(project) }
                        EntryScanner(project).scan(scope)
                    }
                }
                val s = com.gjavadoc.settings.SettingsState.getInstance(project).state
                val crud = crudOverride ?: s.crud
                val filtered = entries.filter { ep ->
                    val base = ep.method.substringBefore('(')
                    when (classifyMethodName(base, s.crudPatterns)) {
                        MethodCategory.CREATE -> crud.includeCreate
                        MethodCategory.READ -> crud.includeRead
                        MethodCategory.UPDATE -> crud.includeUpdate
                        MethodCategory.DELETE -> crud.includeDelete
                        MethodCategory.OTHER -> crud.includeOther
                    }
                }
                val dropped = entries.size - filtered.size
                log.info("GJavaDoc scanned ${entries.size} entries, filtered=${dropped}")
                if (filtered.isEmpty()) {
                    Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "未找到任何入口，请检查注解配置或项目索引状态。", NotificationType.WARNING), project)
                    return
                }
                if (dropped > 0) {
                    Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "已过滤 ${dropped} 个方法（基于 CRUD 选项）", NotificationType.INFORMATION), project)
                }
                // Incremental skip: only enqueue items with no existing docs in docs/
                val outputs = com.gjavadoc.io.ExistingOutputs.index(project.basePath)
                if (s.perClassDocument) {
                    val grouped = filtered.groupBy { it.classFqn }
                    val tasks = grouped
                        .filter { (cls, _) -> !outputs.classSet.contains(cls) }
                        .map { (cls, eps) ->
                            val first = eps.first()
                            com.gjavadoc.model.TaskModel(
                                taskId = cls + "#CLASS#" + System.currentTimeMillis(),
                                entry = first.copy(method = "CLASS"),
                                scope = com.gjavadoc.model.TaskScope.CLASS,
                            )
                        }
                    if (tasks.isEmpty()) {
                        Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "所有类级文档均已存在（基于 docs/）。", NotificationType.INFORMATION), project)
                        return
                    }
                    val skipped = grouped.size - tasks.size
                    queue.enqueueTasks(tasks)
                    queue.start()
                    Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "已入队 ${tasks.size} 个类级任务（跳过 ${skipped} 个已生成）", NotificationType.INFORMATION), project)
                } else {
                    fun methodSafeKey(sig: String): String {
                        // Reproduce QueueManager.safe() behavior for the method signature part
                        val replaced = sig.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        // Drop trailing underscores for stable comparison
                        return replaced.trimEnd('_')
                    }
                    val toRun = filtered.filter { ep ->
                        val key = methodSafeKey(ep.method)
                        val hit = outputs.methodsExact.contains(ep.classFqn to key)
                        !hit
                    }
                    if (toRun.isEmpty()) {
                        Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "所有方法文档均已存在（基于 docs/）。", NotificationType.INFORMATION), project)
                        return
                    }
                    val skipped = filtered.size - toRun.size
                    queue.enqueueAll(toRun)
                    queue.start()
                    Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "已入队 ${toRun.size} 个任务（跳过 ${skipped} 个已生成）", NotificationType.INFORMATION), project)
                }
            }
        })
    }

    fun stop() {
        queue.stop()
    }

    fun retry(taskId: String) {
        val t = repo.all().firstOrNull { it.taskId == taskId } ?: return
        val entry = t.entry
        queue.enqueueAll(listOf(entry))
        queue.start()
    }

    fun requeue(taskId: String) {
        val t = repo.all().firstOrNull { it.taskId == taskId } ?: return
        queue.enqueueAll(listOf(t.entry))
        queue.start()
    }

    fun retryAllFailed() {
        val failed = repo.all().filter { it.status == com.gjavadoc.model.TaskStatus.FAILED }
        if (failed.isEmpty()) {
            Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "没有 FAILED 状态的任务可重试。", NotificationType.INFORMATION), project)
            return
        }
        queue.enqueueAll(failed.map { it.entry })
        queue.start()
        Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "已重新入队 ${failed.size} 个失败任务", NotificationType.INFORMATION), project)
    }

    fun retryAllTimeouts() {
        val failed = repo.all().filter { t ->
            t.status == com.gjavadoc.model.TaskStatus.FAILED &&
            ((t.error?.type?.contains("Timeout", ignoreCase = true) == true) ||
             (t.error?.message?.contains("timed out", ignoreCase = true) == true))
        }
        if (failed.isEmpty()) {
            Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "没有超时失败的任务可重试。", NotificationType.INFORMATION), project)
            return
        }
        queue.enqueueAll(failed.map { it.entry })
        queue.start()
        Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "已重新入队 ${failed.size} 个超时任务", NotificationType.INFORMATION), project)
    }

    fun resetAll() {
        queue.stop()
        queue.clearQueue()
        repo.clearAll()
        Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "队列与任务状态已清空", NotificationType.INFORMATION), project)
    }

    fun cancel(taskId: String) {
        queue.cancelTask(taskId)
        Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "已请求取消任务：$taskId", NotificationType.INFORMATION), project)
    }

    fun cancelAll() {
        queue.cancelAllRunning()
        Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "已请求取消所有运行中任务", NotificationType.INFORMATION), project)
    }

    /**
     * 根据给定的列表内容恢复/继续任务。
     * 支持的行格式（每行一个）：
     *  - com.pkg.Class#method
     *  - com.pkg.Class#method(argTypes...)
     *  - com.pkg.Class#CLASS （类级任务）
     *  - 也尽力解析输出文件名安全化的前缀（如 xxx___123456.md/.txt）
     */
    fun resumeFromList(raw: String, skipSucceeded: Boolean = true) {
        val lines = raw.lines().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.isEmpty()) {
            Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "列表为空，未入队任何任务。", NotificationType.WARNING), project)
            return
        }

        ProgressManager.getInstance().run(object: Task.Backgroundable(project, "GJavaDoc: 解析列表并入队", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "等待索引完成..."
                    val allEntries = DumbService.getInstance(project).runReadActionInSmartMode<List<com.gjavadoc.model.EntryPoint>> {
                        indicator.checkCanceled()
                        ReadAction.compute<List<com.gjavadoc.model.EntryPoint>, RuntimeException> {
                            EntryScanner(project).scan()
                        }
                    }
                    val byClass = allEntries.groupBy { it.classFqn }
                    val history = repo.all()

                    fun succeeded(ep: com.gjavadoc.model.EntryPoint, scope: com.gjavadoc.model.TaskScope): Boolean {
                        if (!skipSucceeded) return false
                        return history.any { t ->
                            t.status == com.gjavadoc.model.TaskStatus.SUCCEEDED &&
                            t.entry.classFqn == ep.classFqn &&
                            t.entry.method == ep.method &&
                            t.scope == scope
                        }
                    }

                    // 解析单行，尽力返回 (classFqn, methodKey)；methodKey == "CLASS" 表示类级任务
                    fun parseLine(line: String): Pair<String, String>? {
                        var s = line.trim().removeSuffix(".md").removeSuffix(".txt")
                        // 去掉时间戳后缀：_{digits}（一个或多个下划线）
                        run {
                            var i = s.length - 1
                            while (i >= 0 && s[i].isDigit()) i--
                            var j = i
                            while (j >= 0 && s[j] == '_') j--
                            if (j < i) s = s.substring(0, j + 1)
                        }
                        // 明确的 # 分隔
                        if (s.contains('#')) {
                            val p = s.split('#', limit = 3)
                            val cls = p.getOrNull(0)?.trim().orEmpty()
                            val m = p.getOrNull(1)?.trim().orEmpty()
                            if (cls.isNotBlank() && m.isNotBlank()) return cls to m
                        }
                        // 尝试从安全化文件名推断：com.pkg.Clz_method_args 形式
                        val lastDot = s.lastIndexOf('.')
                        if (lastDot > 0) {
                            val pkg = s.substring(0, lastDot)
                            val tail = s.substring(lastDot + 1)
                            val us = tail.indexOf('_')
                            if (us > 0) {
                                val simple = tail.substring(0, us)
                                val methodPart = tail.substring(us + 1)
                                val cls = "$pkg.$simple"
                                val base = methodPart.substringBefore('_')
                                if (cls.isNotBlank() && base.isNotBlank()) return cls to base
                            } else {
                                // 仅类名（无下划线），当作类级任务
                                val cls = "$pkg.$tail"
                                return cls to "CLASS"
                            }
                        }
                        return null
                    }

                    val methodEntries = LinkedHashMap<String, com.gjavadoc.model.EntryPoint>()
                    val classTasks = mutableListOf<com.gjavadoc.model.TaskModel>()

                    for (ln in lines) {
                        indicator.checkCanceled()
                        val p = parseLine(ln) ?: continue
                        val cls = p.first
                        val methodKey = p.second
                        if (methodKey.equals("CLASS", ignoreCase = true)) {
                            val eps = byClass[cls]
                            if (!eps.isNullOrEmpty()) {
                                val first = eps.first()
                                // 类级任务：method 固定为 "CLASS"
                                val ep = first.copy(method = "CLASS")
                                if (!succeeded(ep, com.gjavadoc.model.TaskScope.CLASS)) {
                                    classTasks.add(
                                        com.gjavadoc.model.TaskModel(
                                            taskId = cls + "#CLASS#" + System.currentTimeMillis(),
                                            entry = ep,
                                            scope = com.gjavadoc.model.TaskScope.CLASS,
                                        )
                                    )
                                }
                            }
                            continue
                        }
                        val eps = byClass[cls] ?: emptyList()
                        if (methodKey.contains('(')) {
                            // 精确签名
                            eps.filter { it.method == methodKey }.forEach { ep ->
                                val key = ep.classFqn + "#" + ep.method
                                if (!methodEntries.containsKey(key) && !succeeded(ep, com.gjavadoc.model.TaskScope.METHOD)) methodEntries[key] = ep
                            }
                        } else {
                            // 仅方法名：匹配重载的所有方法
                            eps.filter { it.method.substringBefore('(') == methodKey }.forEach { ep ->
                                val key = ep.classFqn + "#" + ep.method
                                if (!methodEntries.containsKey(key) && !succeeded(ep, com.gjavadoc.model.TaskScope.METHOD)) methodEntries[key] = ep
                            }
                        }
                    }

                    val methodsToEnqueue = methodEntries.values.toList()
                    val classesToEnqueue = classTasks
                    if (methodsToEnqueue.isEmpty() && classesToEnqueue.isEmpty()) {
                        Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "未匹配到任何入口（可能已全部成功或列表格式不正确）", NotificationType.WARNING), project)
                        return
                    }

                    if (methodsToEnqueue.isNotEmpty()) queue.enqueueAll(methodsToEnqueue)
                    if (classesToEnqueue.isNotEmpty()) queue.enqueueTasks(classesToEnqueue)
                    queue.start()

                    val total = methodsToEnqueue.size + classesToEnqueue.size
                    Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "已根据列表入队 ${total} 个任务（方法 ${methodsToEnqueue.size}，类 ${classesToEnqueue.size}）", NotificationType.INFORMATION), project)
                } catch (t: Throwable) {
                    log.warn("resumeFromList failed", t)
                    Notifications.Bus.notify(Notification("GJavaDoc", "GJavaDoc", "解析列表失败：${t.message}", NotificationType.ERROR), project)
                }
            }
        })
    }
}

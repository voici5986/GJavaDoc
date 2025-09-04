package com.gjavadoc.lifecycle

import com.gjavadoc.queue.QueueManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 在项目启动后自动扫描任务历史并继续未完成的任务，避免因模型/IDE 中断而人工干预。
 */
class AutoResumeStartup : StartupActivity {
    override fun runActivity(project: Project) {
        // 尽量静默执行，若有待恢复任务则自动入队并启动
        val queue = project.getService(QueueManager::class.java)
        queue.autoResumePending()
    }
}


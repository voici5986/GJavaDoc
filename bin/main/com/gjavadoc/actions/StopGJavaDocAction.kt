package com.gjavadoc.actions

import com.gjavadoc.services.GJavaDocService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class StopGJavaDocAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.getService(GJavaDocService::class.java).stop()
    }
}


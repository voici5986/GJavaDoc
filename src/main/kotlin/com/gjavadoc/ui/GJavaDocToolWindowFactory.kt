package com.gjavadoc.ui

import com.gjavadoc.services.GJavaDocService
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBPanel
import com.intellij.ui.table.JBTable
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JComponent

class GJavaDocToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tablePanel = TaskTablePanel(project)
        val panel = tablePanel.component

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(panel as JComponent, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

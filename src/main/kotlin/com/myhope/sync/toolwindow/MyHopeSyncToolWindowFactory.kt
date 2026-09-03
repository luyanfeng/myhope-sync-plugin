package com.myhope.sync.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class MyHopeSyncToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MyHopeSyncPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.content, "", false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * 工具窗口对所有项目可见。
     * 使用 isApplicableAsync 替代已废弃的 isApplicable。
     */
    override suspend fun isApplicableAsync(project: Project): Boolean = true
}

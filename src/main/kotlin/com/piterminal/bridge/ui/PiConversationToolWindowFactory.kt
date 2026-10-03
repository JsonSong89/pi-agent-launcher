package com.piterminal.bridge.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import com.piterminal.bridge.conversations.PiConversationService

class PiConversationToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PiConversationPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        project.messageBus.connect(panel).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(shown: ToolWindow) {
                    if (shown.id != PiConversationService.TOOL_WINDOW_ID) return
                    PiConversationService.getInstance(project).ensureActiveConversation()
                }
            }
        )
        if (toolWindow.isVisible) {
            PiConversationService.getInstance(project).ensureActiveConversation()
        }
    }
}

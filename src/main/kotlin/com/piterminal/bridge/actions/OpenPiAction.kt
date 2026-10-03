package com.piterminal.bridge.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.piterminal.bridge.conversations.PiConversationService

/**
 * Open or focus the Pi conversation tool window.
 */
class OpenPiAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val conversations = PiConversationService.getInstance(project)
        conversations.showToolWindow(focus = true)
        conversations.ensureActiveConversation()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

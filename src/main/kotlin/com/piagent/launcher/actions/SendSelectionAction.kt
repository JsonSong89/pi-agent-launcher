package com.piagent.launcher.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.piagent.launcher.conversations.PiConversationService
import com.piagent.launcher.services.PiDiffWatcher

/**
 * Send selected code to the active conversation input as a file reference.
 * Format: @relative/path/to/file.go#L10-25
 */
class SendSelectionAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectionModel = editor.selectionModel
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: e.getData(CommonDataKeys.PSI_FILE)?.virtualFile
            ?: return

        val projectPath = project.basePath ?: ""
        val filePath = virtualFile.path
        val relativePath = if (filePath.startsWith(projectPath)) {
            filePath.removePrefix(projectPath).removePrefix("/")
        } else {
            filePath
        }

        val reference = if (!selectionModel.hasSelection()) {
            "@$relativePath"
        } else {
            val startLine = editor.document.getLineNumber(selectionModel.selectionStart) + 1
            val endLine = editor.document.getLineNumber(selectionModel.selectionEnd) + 1
            if (startLine == endLine) {
                "@$relativePath#L$startLine"
            } else {
                "@$relativePath#L$startLine-$endLine"
            }
        }

        val diffWatcher = PiDiffWatcher.getInstance(project)
        diffWatcher.snapshotFile(filePath)
        diffWatcher.startWatching()

        PiConversationService.getInstance(project).appendToDraft(reference)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isVisible = true
        e.presentation.isEnabled = editor != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

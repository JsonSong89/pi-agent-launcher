package com.piagent.launcher.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileSystemItem
import com.piagent.launcher.conversations.PiConversationService

/**
 * Send file(s) from Project View to the active conversation input.
 * Format: @relative/path/to/file.go
 */
class SendFileToPiAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFiles = getFiles(e)
        if (virtualFiles.isEmpty()) return

        val projectPath = project.basePath ?: ""
        val references = virtualFiles
            .filter { !it.isDirectory }
            .joinToString(" ") { file ->
                val relativePath = if (file.path.startsWith(projectPath)) {
                    file.path.removePrefix(projectPath).removePrefix("/")
                } else {
                    file.path
                }
                "@$relativePath"
            }

        if (references.isBlank()) return
        PiConversationService.getInstance(project).appendToDraft("$references ")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        val files = getFiles(e)
        e.presentation.isEnabled = files.any { !it.isDirectory }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun getFiles(e: AnActionEvent): List<VirtualFile> {
        val array = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (array != null && array.isNotEmpty()) return array.toList()

        val single = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (single != null) return listOf(single)

        val psiElements = e.getData(LangDataKeys.PSI_ELEMENT_ARRAY)
        if (psiElements != null) {
            val files = psiElements.mapNotNull {
                (it as? PsiFileSystemItem)?.virtualFile
            }
            if (files.isNotEmpty()) return files
        }

        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        if (psiFile?.virtualFile != null) return listOf(psiFile.virtualFile)

        return emptyList()
    }
}

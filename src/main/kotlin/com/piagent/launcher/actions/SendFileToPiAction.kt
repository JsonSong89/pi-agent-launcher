package com.piagent.launcher.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.piagent.launcher.conversations.PiConversationService

/**
 * Send file(s) from Project View to the active conversation input.
 * Format: @relative/path/to/file.go
 *
 * Update runs on BGT so Project View file keys are actually available.
 */
class SendFileToPiAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFiles = getFiles(e)
        if (virtualFiles.isEmpty()) return

        val projectPath = project.basePath ?: ""
        val references = virtualFiles
            .distinctBy { it.path }
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
        e.presentation.isEnabled = e.project != null && getFiles(e).isNotEmpty()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun getFiles(e: AnActionEvent): List<VirtualFile> {
        val found = LinkedHashSet<VirtualFile>()

        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.forEach { found.add(it) }
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { found.add(it) }
        e.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY)?.forEach { found.add(it) }

        e.getData(LangDataKeys.IDE_VIEW)?.selectedFiles?.forEach { found.add(it) }

        e.getData(LangDataKeys.PSI_ELEMENT_ARRAY)?.forEach { addPsiFile(found, it) }
        e.getData(CommonDataKeys.PSI_ELEMENT)?.let { addPsiFile(found, it) }
        e.getData(CommonDataKeys.PSI_FILE)?.virtualFile?.let { found.add(it) }

        e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)?.forEach { navigatable ->
            when (navigatable) {
                is VirtualFile -> found.add(navigatable)
                is PsiFileSystemItem -> navigatable.virtualFile?.let { found.add(it) }
            }
        }

        return found.toList()
    }

    private fun addPsiFile(found: MutableSet<VirtualFile>, element: PsiElement) {
        when (element) {
            is PsiFile -> element.virtualFile?.let { found.add(it) }
            is PsiDirectory -> element.virtualFile?.let { found.add(it) }
            is PsiFileSystemItem -> element.virtualFile?.let { found.add(it) }
            else -> element.containingFile?.virtualFile?.let { found.add(it) }
        }
    }
}

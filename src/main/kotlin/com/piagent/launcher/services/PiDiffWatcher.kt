package com.piagent.launcher.services

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.messages.MessageBusConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Watches for file changes made by Pi and shows diff previews in the IDE.
 *
 * VFS after() only enqueues paths. Diff windows are opened later on EDT
 * so we never upgrade write-intent to write on the VFS dispatch thread.
 */
@Service(Service.Level.PROJECT)
class PiDiffWatcher(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(PiDiffWatcher::class.java)
    private val snapshots = ConcurrentHashMap<String, String>()
    private val pendingDiffPaths = ConcurrentLinkedQueue<String>()
    private val queuedDiffPaths = ConcurrentHashMap.newKeySet<String>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    @Volatile
    private var isWatching = false
    private var connection: MessageBusConnection? = null

    fun startWatching() {
        if (isWatching) return
        isWatching = true
        pendingDiffPaths.clear()
        queuedDiffPaths.clear()
        snapshotOpenFiles()
        ensureConnection()
        logger.info("Pi diff watcher started, tracking ${snapshots.size} files")
    }

    fun stopWatching() {
        isWatching = false
        alarm.cancelAllRequests()
        pendingDiffPaths.clear()
        queuedDiffPaths.clear()
        snapshots.clear()
    }

    fun snapshotFile(filePath: String) {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return
        snapshots[filePath] = document.text
    }

    private fun ensureConnection() {
        if (connection != null) return
        connection = project.messageBus.connect(this).also { conn ->
            conn.subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        if (!isWatching) return
                        val projectPath = project.basePath ?: return
                        for (event in events) {
                            if (event !is VFileContentChangeEvent) continue
                            val path = event.file.path
                            if (!path.startsWith(projectPath)) continue
                            if (!snapshots.containsKey(path)) continue
                            enqueueDiff(path)
                        }
                    }
                }
            )
        }
    }

    private fun enqueueDiff(path: String) {
        if (!queuedDiffPaths.add(path)) return
        pendingDiffPaths.add(path)
        alarm.cancelAllRequests()
        alarm.addRequest({ flushNextDiff() }, DEBOUNCE_MS)
    }

    private fun flushNextDiff() {
        runOutsideVfs { showOnePendingDiff() }
    }

    private fun showOnePendingDiff() {
        if (project.isDisposed || !isWatching) {
            pendingDiffPaths.clear()
            queuedDiffPaths.clear()
            return
        }
        val path = pendingDiffPaths.poll() ?: return
        queuedDiffPaths.remove(path)
        showDiff(path)

        if (pendingDiffPaths.isNotEmpty()) {
            alarm.addRequest({ flushNextDiff() }, DIFF_GAP_MS)
        }
    }

    private fun showDiff(filePath: String) {
        val originalContent = snapshots[filePath] ?: return
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        if (!vFile.isValid) return
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return
        val newContent = document.text
        if (originalContent == newContent) return

        val diffContentFactory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Pi Agent: Changes to ${vFile.name}",
            diffContentFactory.create(originalContent),
            diffContentFactory.create(project, document),
            "Before Pi",
            "After Pi"
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun snapshotOpenFiles() {
        val projectPath = project.basePath ?: return
        val fileDocManager = FileDocumentManager.getInstance()
        fileDocManager.unsavedDocuments.forEach { doc ->
            val vFile = fileDocManager.getFile(doc)
            if (vFile != null && vFile.path.startsWith(projectPath)) {
                snapshots[vFile.path] = doc.text
            }
        }
    }

    private fun runOutsideVfs(work: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) {
                work()
            }
        }, ModalityState.nonModal(), project.disposed)
    }

    override fun dispose() {
        isWatching = false
        alarm.cancelAllRequests()
        pendingDiffPaths.clear()
        queuedDiffPaths.clear()
        connection?.disconnect()
        connection = null
        snapshots.clear()
    }

    companion object {
        private const val DEBOUNCE_MS = 800
        private const val DIFF_GAP_MS = 400

        fun getInstance(project: Project): PiDiffWatcher = project.service()
    }
}

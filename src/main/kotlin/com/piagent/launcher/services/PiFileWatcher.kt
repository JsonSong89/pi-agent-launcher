package com.piagent.launcher.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.messages.MessageBusConnection
import com.piagent.launcher.settings.PiSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Watches for file changes made by Pi.
 * - Auto-opens modified files in the editor
 * - Shows notification when Pi finishes modifying files
 *
 * VFS after() runs under write-intent. Opening an editor there tries to
 * upgrade the same thread to a write lock and crashes the platform.
 * The listener only enqueues paths; UI work is delayed onto EDT.
 */
@Service(Service.Level.PROJECT)
class PiFileWatcher(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(PiFileWatcher::class.java)
    private val modifiedFiles = ConcurrentHashMap.newKeySet<String>()
    private val pendingOpenPaths = ConcurrentLinkedQueue<String>()
    private val queuedOpenPaths = ConcurrentHashMap.newKeySet<String>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    @Volatile
    private var isWatching = false
    private var connection: MessageBusConnection? = null

    fun startWatching() {
        if (isWatching) return
        isWatching = true
        modifiedFiles.clear()
        pendingOpenPaths.clear()
        queuedOpenPaths.clear()
        ensureConnection()
        logger.info("Pi file watcher started")
    }

    fun stopWatching() {
        isWatching = false
        alarm.cancelAllRequests()
        pendingOpenPaths.clear()
        queuedOpenPaths.clear()

        val count = modifiedFiles.size
        modifiedFiles.clear()

        val settings = PiSettings.getInstance().state
        if (settings.showNotifications && count > 0) {
            runOutsideVfs {
                showCompletionNotification(count)
            }
        }
    }

    private fun ensureConnection() {
        if (connection != null) return
        connection = project.messageBus.connect(this).also { conn ->
            conn.subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        if (!isWatching) return
                        val settings = PiSettings.getInstance().state
                        if (!settings.autoOpenFiles && !settings.showNotifications) return
                        val projectPath = project.basePath ?: return
                        for (event in events) {
                            if (event !is VFileContentChangeEvent) continue
                            val path = event.file.path
                            if (!path.startsWith(projectPath)) continue
                            if (settings.showNotifications) {
                                modifiedFiles.add(path)
                            }
                            if (settings.autoOpenFiles) {
                                enqueueOpen(path)
                            }
                        }
                    }
                }
            )
        }
    }

    private fun enqueueOpen(path: String) {
        if (!queuedOpenPaths.add(path)) return
        pendingOpenPaths.add(path)
        alarm.cancelAllRequests()
        alarm.addRequest({ flushNextOpen() }, DEBOUNCE_MS)
    }

    private fun flushNextOpen() {
        runOutsideVfs { openOnePendingFile() }
    }

    private fun openOnePendingFile() {
        if (project.isDisposed || !isWatching) {
            pendingOpenPaths.clear()
            queuedOpenPaths.clear()
            return
        }
        val path = pendingOpenPaths.poll() ?: return
        queuedOpenPaths.remove(path)
        if (!PiSettings.getInstance().state.autoOpenFiles) {
            pendingOpenPaths.clear()
            queuedOpenPaths.clear()
            return
        }

        val file = LocalFileSystem.getInstance().findFileByPath(path)
        if (file != null && file.isValid && !file.isDirectory) {
            val manager = FileEditorManager.getInstance(project)
            if (!manager.isFileOpen(file)) {
                manager.openFile(file, false)
            }
        }

        if (pendingOpenPaths.isNotEmpty()) {
            alarm.addRequest({ flushNextOpen() }, OPEN_GAP_MS)
        }
    }

    private fun runOutsideVfs(work: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) {
                work()
            }
        }, ModalityState.nonModal(), project.disposed)
    }

    private fun showCompletionNotification(count: Int) {
        val message = if (count == 1) {
            "Pi modified 1 file"
        } else {
            "Pi modified $count files"
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pi Agent")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    override fun dispose() {
        isWatching = false
        alarm.cancelAllRequests()
        pendingOpenPaths.clear()
        queuedOpenPaths.clear()
        connection?.disconnect()
        connection = null
    }

    companion object {
        private const val DEBOUNCE_MS = 800
        private const val OPEN_GAP_MS = 200

        fun getInstance(project: Project): PiFileWatcher = project.service()
    }
}

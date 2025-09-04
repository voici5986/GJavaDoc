package com.gjavadoc.io

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class OutputWriter(private val project: Project) {
    fun writeRelative(path: String, content: String): String {
        val base = project.basePath ?: return path
        val ioFile = File(base, path)
        ioFile.parentFile?.mkdirs()
        // Write via NIO to avoid heavy EDT write actions for large files
        Files.write(ioFile.toPath(), content.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        // Refresh VFS on the written file (no write action needed for refresh)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(ioFile.absolutePath)?.refresh(false, false)
        return ioFile.absolutePath
    }
}

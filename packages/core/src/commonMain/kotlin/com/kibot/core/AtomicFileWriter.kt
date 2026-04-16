package com.kibot.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AtomicFileWriter {
    fun write(path: String, content: String) {
        write(File(path), content)
    }

    fun write(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile ?: File("."), "${target.name}.tmp.${System.nanoTime()}")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            FileOutputStream(tmp, true).channel.use { channel ->
                channel.force(true)
            }
            moveAtomically(tmp, target)
        } catch (error: Exception) {
            tmp.delete()
            throw IOException("AtomicFileWriter failed for ${target.path}: ${error.message}", error)
        }
    }

    private fun moveAtomically(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}

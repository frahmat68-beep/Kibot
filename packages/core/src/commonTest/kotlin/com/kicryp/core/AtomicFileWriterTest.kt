package com.kicryp.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtomicFileWriterTest {
    @Test
    fun `write replaces file contents atomically`() {
        val dir = Files.createTempDirectory("atomic-file-writer-test")
        val target = dir.resolve("state.json").toFile()

        AtomicFileWriter.write(target, """{"status":"first"}""")
        AtomicFileWriter.write(target, """{"status":"second"}""")

        assertEquals("""{"status":"second"}""", target.readText())
        assertTrue(dir.resolve("state.json").toFile().exists())
    }
}

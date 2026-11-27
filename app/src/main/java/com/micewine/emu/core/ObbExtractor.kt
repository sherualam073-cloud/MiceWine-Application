package com.micewine.emu.core

import android.annotation.SuppressLint
import android.system.Os
import com.micewine.emu.fragments.SetupFragment.Companion.progressBarIsIndeterminate
import com.micewine.emu.fragments.SetupFragment.Companion.progressBarValue
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.progress.ProgressMonitor
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

object ObbExtractor {
    @Volatile var lastExtractionReport: String = "No extraction report"

    @SuppressLint("SetTextI18n")
    @Throws(IOException::class)
    fun extractZip(archivePath: String?, destinationPath: String) {
        val source = archivePath?.let(::File)
            ?: throw IOException("RootFS file path is missing")
        if (!source.isFile || !source.canRead()) {
            throw IOException("RootFS file cannot be read")
        }

        val destination = File(destinationPath).canonicalFile
        if (!destination.exists() && !destination.mkdirs()) {
            throw IOException("Cannot create RootFS destination")
        }

        val signature = ByteArray(6)
        FileInputStream(source).use { input ->
            if (input.read(signature) < 4) throw IOException("RootFS archive is incomplete")
        }

        // ZIP archives start with PK. Current MiceWine .rat packages are tar.xz.
        if (signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte()) {
            extractZipArchive(source, destination)
        } else {
            extractTarXzArchive(source, destination)
        }
    }

    private fun extractZipArchive(source: File, destination: File) {
        val headers = ZipFile(source).fileHeaders
        val box64 = headers.firstOrNull { it.fileName.endsWith("usr/bin/box64") || it.fileName.endsWith("usr\\bin\\box64") }
        lastExtractionReport = "ZIP entries=${headers.size}, box64=${box64?.fileName ?: "not listed"}, first=${headers.take(8).joinToString { it.fileName }}"
        progressBarIsIndeterminate = false
        val zipFile = ZipFile(source)
        zipFile.isRunInThread = true
        val monitor = zipFile.progressMonitor
        zipFile.extractAll(destination.absolutePath)

        while (monitor.state != ProgressMonitor.State.READY) {
            progressBarValue = monitor.percentDone
            Thread.sleep(100)
        }

        if (monitor.result == ProgressMonitor.Result.ERROR) {
            throw IOException("ZIP extraction failed", monitor.exception)
        }

        zipFile.close()
        progressBarValue = 0
    }

    private fun extractTarXzArchive(source: File, destination: File) {
        progressBarIsIndeterminate = true
        val root = destination.canonicalFile
        val directories = mutableListOf<Pair<File, Int>>()
        var entryCount = 0
        var fileCount = 0
        var box64Entry = "not listed"
        val sampleEntries = mutableListOf<String>()

        BufferedInputStream(FileInputStream(source)).use { fileInput ->
            XZCompressorInputStream(fileInput).use { xzInput ->
                TarArchiveInputStream(xzInput).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        entryCount++
                        if (sampleEntries.size < 12) sampleEntries.add(entry.name)
                        if (entry.name.replace('\\', '/').endsWith("usr/bin/box64")) {
                            box64Entry = "name=${entry.name}, file=${entry.isFile}, size=${entry.size}, type=${entry.linkFlag}"
                        }
                        if (entry.isFile) fileCount++
                        val output = File(root, entry.name).canonicalFile
                        if (output != root && !output.path.startsWith(root.path + File.separator)) {
                            throw IOException("Unsafe path in RootFS archive")
                        }

                        when {
                            entry.isDirectory -> {
                                if (!output.exists() && !output.mkdirs()) {
                                    throw IOException("Cannot create RootFS directory: ${entry.name}")
                                }
                                directories.add(output to entry.mode)
                            }

                            entry.isSymbolicLink -> {
                                output.parentFile?.mkdirs()
                                Files.deleteIfExists(output.toPath())
                                Files.createSymbolicLink(output.toPath(), Paths.get(entry.linkName))
                            }

                            entry.isLink -> {
                                output.parentFile?.mkdirs()
                                val target = File(root, entry.linkName).canonicalFile
                                if (target != root && !target.path.startsWith(root.path + File.separator)) {
                                    throw IOException("Unsafe hard link in RootFS archive")
                                }
                                Files.deleteIfExists(output.toPath())
                                Files.createLink(output.toPath(), target.toPath())
                            }

                            entry.isFile -> {
                                output.parentFile?.mkdirs()
                                // Read exactly the current TAR entry. copyTo() consumes
                                // the rest of the archive and skips all following RootFS files.
                                Files.newOutputStream(output.toPath()).use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    var remaining = entry.size
                                    while (remaining > 0) {
                                        val bytesToRead = minOf(buffer.size.toLong(), remaining).toInt()
                                        val count = tar.read(buffer, 0, bytesToRead)
                                        if (count < 0) {
                                            throw IOException("Unexpected end of RootFS entry: ${entry.name}")
                                        }
                                        out.write(buffer, 0, count)
                                        remaining -= count
                                    }
                                }
                                Os.chmod(output.absolutePath, entry.mode)
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }

        directories.asReversed().forEach { (directory, mode) ->
            Os.chmod(directory.absolutePath, mode)
        }
        lastExtractionReport = "TAR.XZ entries=$entryCount, regularFiles=$fileCount, box64=$box64Entry, first=${sampleEntries.joinToString(" | ")}"
        progressBarValue = 0
        progressBarIsIndeterminate = false
    }
}

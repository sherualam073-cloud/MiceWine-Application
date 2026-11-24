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

        BufferedInputStream(FileInputStream(source)).use { fileInput ->
            XZCompressorInputStream(fileInput).use { xzInput ->
                TarArchiveInputStream(xzInput).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
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
                                Files.newOutputStream(output.toPath()).use { out -> tar.copyTo(out) }
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
        progressBarValue = 0
        progressBarIsIndeterminate = false
    }
}

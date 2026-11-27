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

        // Some RootFS bundles contain Box64 as a nested MiceWine .rat package
        // instead of placing usr/bin/box64 directly in the outer ZIP.
        installBundledRatPackages(destination)
    }

    // RootFS bundles keep architecture-specific components in nested .rat files.
    // Merge their files/ payloads and collect each package's generated symlink commands.
    private fun installBundledRatPackages(destination: File) {
        val ratPackages = destination.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.extension.equals("rat", ignoreCase = true) }
            .toList()
        if (ratPackages.isEmpty()) return

        val temporary = File(destination, ".rat-package-extract")
        val symlinkCommands = StringBuilder()
        var installedBox64 = false

        try {
            ratPackages.forEach { ratPackage ->
                temporary.deleteRecursively()
                if (!temporary.mkdirs()) {
                    throw IOException("Cannot create temporary .rat package directory")
                }

                val ratZip = ZipFile(ratPackage)
                if (!ratZip.isValidZipFile) {
                    throw IOException("Bundled .rat package is not a valid ZIP archive: ${ratPackage.name}")
                }
                ratZip.extractAll(temporary.absolutePath)

                val packageFiles = File(temporary, "files")
                if (packageFiles.isDirectory) {
                    packageFiles.copyRecursively(destination, overwrite = true)
                }

                listOf("makeSymlinks.sh", "generateSymlinks.sh").forEach { scriptName ->
                    val script = File(temporary, scriptName)
                    if (script.isFile) {
                        symlinkCommands.append("\n").append(script.readText()).append("\n")
                    }
                }
                if (ratPackage.parentFile?.name == "box64" || ratPackage.name.contains("box64", true)) {
                    installedBox64 = File(destination, "usr/bin/box64").isFile
                }
                lastExtractionReport += "; processed ${ratPackage.name}"
            }

            val expectedBox64 = File(destination, "usr/bin/box64")
            if (!expectedBox64.isFile) {
                throw IOException("Bundled .rat packages did not provide files/usr/bin/box64")
            }
            Os.chmod(expectedBox64.absolutePath, 448)

            val usrDirectory = File(destination, "usr")
            if (!usrDirectory.exists() && !usrDirectory.mkdirs()) {
                throw IOException("Cannot create RootFS usr directory")
            }
            val symlinkScript = File(usrDirectory, "generateSymlinks.sh")
            if (symlinkCommands.isNotEmpty()) {
                symlinkScript.writeText("#!/system/bin/sh\n" + symlinkCommands.toString())
            } else if (!symlinkScript.isFile) {
                throw IOException("Bundled .rat packages do not contain makeSymlinks.sh")
            }
            Os.chmod(symlinkScript.absolutePath, 448)
            lastExtractionReport += "; symlink script=${symlinkScript.isFile}, box64=$installedBox64"
        } finally {
            temporary.deleteRecursively()
        }
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

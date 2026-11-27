package com.micewine.emu.core

import android.os.Build
import com.micewine.emu.core.EnvVars.getEnv
import com.micewine.emu.core.ShellLoader.runCommand
import com.micewine.emu.activities.MainActivity.Companion.usrDir
import java.io.File

object WineWrapper {
    // Use the extracted executable directly instead of depending on PATH lookup.
    private val IS_BOX64: String
        get() = if (Build.SUPPORTED_ABIS[0] == "x86_64") "" else "'${File(usrDir, "bin/box64").absolutePath}'"

    fun wineServer(args: String) {
        runCommand(
            getEnv() + "$IS_BOX64 wineserver $args"
        )
    }

    fun wine(args: String, winePrefix: File) {
        runCommand(
            getEnv() + "WINEPREFIX=$winePrefix $IS_BOX64 wine $args"
        )
    }

    /**
     * Launches a user-selected Windows executable without interpolating its path
     * directly into shell syntax. Paths are canonicalized and shell-quoted.
     */
    fun wineExecutable(exeFile: File, winePrefix: File) {
        val executable = exeFile.canonicalFile
        require(executable.isFile) { "Executable does not exist" }
        require(executable.extension.lowercase() in setOf("exe", "bat", "msi")) {
            "Unsupported executable type"
        }

        val workingDirectory = executable.parentFile
            ?: throw IllegalArgumentException("Executable has no parent directory")

        runCommand(
            "cd ${shellQuote(workingDirectory.path)};" +
                    getEnv() + "WINEPREFIX=${shellQuote(winePrefix.canonicalPath)} " +
                    "$IS_BOX64 wine ${shellQuote(executable.path)}"
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    fun extractIcon(exeFile: File, output: String) {
        if (exeFile.name.endsWith(".exe")) {
            runCommand(
                getEnv() + "wrestool -x -t 14 '${exeFile.path}' > '$output'"
            )
        }
    }
}
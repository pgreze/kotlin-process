package com.github.pgreze.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

data class ProcessResult(
    val resultCode: Int,
    val output: List<String>,
)

/**
 * Helper allowing to ensure a [process] call always conclude correctly.
 * @return [ProcessResult.output] because it ensures the result is always 0.
 */
fun ProcessResult.validate(): List<String> {
    check(resultCode == 0) { "Invalid result: $resultCode" }
    return output
}

sealed class RedirectMode {
    object SILENT : RedirectMode()
    object PRINT : RedirectMode()
    object CAPTURE : RedirectMode()
    class File(val file: java.io.File, val append: Boolean = false) : RedirectMode()
    // TODO: Stream mode, allowing to consume without storing the content
}

@ExperimentalCoroutinesApi
@Suppress("BlockingMethodInNonBlockingContext")
suspend fun process(
    vararg command: String,
    stdout: RedirectMode = RedirectMode.PRINT,
    stderr: RedirectMode = RedirectMode.PRINT,
    env: Map<String, String>? = null,
    /** Invoked for [stdout] or [stderr] output when their mode is [RedirectMode.CAPTURE] */
    consumer: (String) -> Unit = {},
): ProcessResult = withContext(Dispatchers.IO) {
    // Based on the fact that it's hardcore to achieve manually:
    // https://stackoverflow.com/a/4959696
    val captureAll = stdout == stderr && stderr == RedirectMode.CAPTURE
    // https://www.baeldung.com/java-lang-processbuilder-api
    val process = ProcessBuilder(*command).apply {
        if (captureAll) {
            redirectErrorStream(true)
        } else {
            redirectOutput(stdout.toNative())
            redirectError(stderr.toNative())
        }
        env?.let { environment().putAll(it) }
    }.start()

    // Consume the output before waitFor, ensuring no content is skipped.
    val output = when {
        captureAll || stdout == RedirectMode.CAPTURE ->
            process.inputStream
        stderr == RedirectMode.CAPTURE ->
            process.errorStream
        else -> null
    }?.bufferedReader()?.useLines { lines ->
        lines.map { it.also(consumer) }.toList()
    } ?: emptyList()

    return@withContext ProcessResult(
        resultCode = process.waitFor(),
        output = output,
    )
}

private fun RedirectMode.toNative() = when (this) {
    RedirectMode.SILENT -> ProcessBuilder.Redirect.DISCARD
    RedirectMode.PRINT -> ProcessBuilder.Redirect.INHERIT
    RedirectMode.CAPTURE -> ProcessBuilder.Redirect.PIPE
    is RedirectMode.File -> when (append) {
        true -> ProcessBuilder.Redirect.appendTo(file)
        false -> ProcessBuilder.Redirect.to(file)
    }
}

package com.github.pgreze.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.InputStream

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
    /** Ignores the related stream. */
    object SILENT : RedirectMode()

    /**
     * Redirect the stream to this process equivalent one.
     * In other words, it will print to the terminal if this process is also doing so.
     *
     * This is correctly using [System.out] or [System.err] depending on the stream +
     * preserving the correct order.
     * @see ProcessBuilder.Redirect.INHERIT
     * @see Streaming when you want to have full control on the outcome.
     */
    object PRINT : RedirectMode()

    /**
     * This will ensure that the stream content is returned as [process]'s return.
     * If both stdout and stderr are using this mode, their output will be correctly merged.
     *
     * It's also possible to consume this content without delay by using [process]'s consumer argument.
     * @see [ProcessBuilder.redirectErrorStream]
     */
    object CAPTURE : RedirectMode()

    /** Redirect to a file, overriding or appending on demand. */
    class File(val file: java.io.File, val append: Boolean = false) : RedirectMode()

    /**
     * Alternative to [CAPTURE] allowing to consume their content
     * without storing them in memory, and so not returned at the end of [process] invocation.
     * @see [process]'s consumer argument to consume [CAPTURE] content without delay.
     */
    class Streaming(val consumer: (String) -> Unit) : RedirectMode()
}

@ExperimentalCoroutinesApi
@Suppress("BlockingMethodInNonBlockingContext")
suspend fun process(
    vararg command: String,
    // TODO: stdin from string or file
    stdout: RedirectMode = RedirectMode.PRINT,
    stderr: RedirectMode = RedirectMode.PRINT,
    /** Allowing to append new environment variables during this process's invocation. */
    env: Map<String, String>? = null,
    /** Consume without delay all streams configured with [RedirectMode.CAPTURE] */
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

    // Handles async streaming consumption before the blocking output handling.
    if (stdout is RedirectMode.Streaming) {
        process.inputStream.toLinesFlow().collect { stdout.consumer(it) }
    }
    if (stderr is RedirectMode.Streaming) {
        process.errorStream.toLinesFlow().collect { stderr.consumer(it) }
    }

    // Consume the output before waitFor, ensuring no content is skipped.
    val output = when {
        captureAll || stdout == RedirectMode.CAPTURE ->
            process.inputStream
        stderr == RedirectMode.CAPTURE ->
            process.errorStream
        else -> null
    }?.toLinesFlow()?.map { it.also(consumer) }?.toList() ?: emptyList()

    return@withContext ProcessResult(
        resultCode = process.waitFor(),
        output = output,
    )
}

private fun InputStream.toLinesFlow(): Flow<String> =
    bufferedReader().lineSequence().asFlow()

private fun RedirectMode.toNative() = when (this) {
    RedirectMode.SILENT -> ProcessBuilder.Redirect.DISCARD
    RedirectMode.PRINT -> ProcessBuilder.Redirect.INHERIT
    RedirectMode.CAPTURE -> ProcessBuilder.Redirect.PIPE
    is RedirectMode.File -> when (append) {
        true -> ProcessBuilder.Redirect.appendTo(file)
        false -> ProcessBuilder.Redirect.to(file)
    }
    is RedirectMode.Streaming -> ProcessBuilder.Redirect.PIPE
}

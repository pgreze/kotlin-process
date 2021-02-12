package com.github.pgreze.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.File
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

@ExperimentalCoroutinesApi
@Suppress("BlockingMethodInNonBlockingContext")
suspend fun process(
    vararg command: String,
    stdin: InputSource? = null,
    stdout: Redirect = Redirect.PRINT,
    stderr: Redirect = Redirect.PRINT,
    /** Allowing to append new environment variables during this process's invocation. */
    env: Map<String, String>? = null,
    /** Override the process working directory. */
    directory: File? = null,
    /** Consume without delay all streams configured with [Redirect.CAPTURE] */
    consumer: suspend (String) -> Unit = {},
): ProcessResult = withContext(Dispatchers.IO) {
    // Based on the fact that it's hardcore to achieve manually:
    // https://stackoverflow.com/a/4959696
    val captureAll = stdout == stderr && stderr == Redirect.CAPTURE

    // https://www.baeldung.com/java-lang-processbuilder-api
    val process = ProcessBuilder(*command).apply {
        when (stdin) {
            null -> null
            is InputSource.FromFile -> ProcessBuilder.Redirect.from(stdin.file)
            is InputSource.FromStream -> ProcessBuilder.Redirect.PIPE
        }?.let { redirectInput(it) }

        if (captureAll) {
            redirectErrorStream(true)
        } else {
            redirectOutput(stdout.toNative())
            redirectError(stderr.toNative())
        }

        directory?.let { directory(it) }
        env?.let { environment().putAll(it) }
    }.start()

    // Handles async consumptions before the blocking output handling.
    if (stdout is Redirect.Consume) {
        process.inputStream.lineFlow(stdout.consumer)
    }
    if (stderr is Redirect.Consume) {
        process.inputStream.lineFlow(stderr.consumer)
    }

    val output = async {
        when {
            captureAll || stdout == Redirect.CAPTURE ->
                process.inputStream
            stderr == Redirect.CAPTURE ->
                process.errorStream
            else -> null
        }?.lineFlow { f -> f.map { it.also { consumer(it) } }.toList() }
            ?: emptyList()
    }

    val input = async {
        (stdin as? InputSource.FromStream)?.handler?.let { handler ->
            process.outputStream.use { handler(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    return@withContext ProcessResult(
        // Consume the output before waitFor,
        // ensuring no content is skipped.
        output = awaitAll(input, output).last() as List<String>,
        resultCode = process.waitFor(),
    )
}

private suspend fun <T> InputStream.lineFlow(block: suspend (Flow<String>) -> T): T =
    bufferedReader().use { it.lineSequence().asFlow().let { f -> block(f) } }

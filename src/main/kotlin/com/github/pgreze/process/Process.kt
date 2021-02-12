package com.github.pgreze.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
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
    // TODO: stdin from string or file
    stdout: Redirect = Redirect.PRINT,
    stderr: Redirect = Redirect.PRINT,
    /** Allowing to append new environment variables during this process's invocation. */
    env: Map<String, String>? = null,
    /** Override the process working directory. */
    directory: File? = null,
    /** Consume without delay all streams configured with [Redirect.CAPTURE] */
    // If we want this function to be suspend, we'll need to manage a sharedFlow.
    consumer: (String) -> Unit = {},
): ProcessResult = withContext(Dispatchers.IO) {
    // Based on the fact that it's hardcore to achieve manually:
    // https://stackoverflow.com/a/4959696
    val captureAll = stdout == stderr && stderr == Redirect.CAPTURE
    // https://www.baeldung.com/java-lang-processbuilder-api
    val process = ProcessBuilder(*command).apply {
        redirectInput(ProcessBuilder.Redirect.from(TODO()))
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
        process.inputStream.toLinesFlow().let { stdout.consumer(it) }
    }
    if (stderr is Redirect.Consume) {
        process.errorStream.toLinesFlow().let { stderr.consumer(it) }
    }

    // Consume the output before waitFor, ensuring no content is skipped.
    val output = when {
        captureAll || stdout == Redirect.CAPTURE ->
            process.inputStream
        stderr == Redirect.CAPTURE ->
            process.errorStream
        else -> null
    }?.bufferedReader()?.lineSequence()?.map { it.also(consumer) }?.toList()
        ?: emptyList()

    return@withContext ProcessResult(
        resultCode = process.waitFor(),
        output = output,
    )
}

private fun InputStream.toLinesFlow(): Flow<String> =
    bufferedReader().lineSequence().asFlow().flowOn(Dispatchers.IO)

package com.github.pgreze.process

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

private suspend fun <R> coroutineScopeIO(block: suspend CoroutineScope.() -> R) =
    withContext(Dispatchers.IO) {
        // Encapsulates all async calls in the current scope.
        // https://elizarov.medium.com/structured-concurrency-722d765aa952
        coroutineScope(block)
    }

@Suppress("BlockingMethodInNonBlockingContext", "LongParameterList", "ComplexMethod")
@JvmOverloads
suspend fun process(
    vararg command: String,
    stdin: InputSource? = null,
    stdout: Redirect = Redirect.PRINT,
    stderr: Redirect = Redirect.PRINT,
    charset: Charset = Charsets.UTF_8,
    /** Extend with new environment variables during this process's invocation. */
    env: Map<String, String>? = null,
    /** Override the process working directory. */
    directory: File? = null,
    /** Determine if process should be destroyed forcibly on job cancellation. */
    destroyForcibly: Boolean = false,
    /** Consume without delay all streams configured with [Redirect.CAPTURE]. */
    consumer: suspend (String) -> Unit = {},
): ProcessResult = coroutineScopeIO {
    // Special case if both stdout and stderr are captured,
    // leading to an impossibility to distinguish them:
    // https://stackoverflow.com/a/4959696
    val captureAll = stdout == Redirect.CAPTURE && stderr == Redirect.CAPTURE

    // https://www.baeldung.com/java-lang-processbuilder-api
    val process = ProcessBuilder(*command)
        .apply {
            stdin?.toNative()?.let { redirectInput(it) }

            if (captureAll) {
                redirectErrorStream(true)
            } else {
                redirectOutput(stdout.toNative())
                redirectError(stderr.toNative())
            }

            directory?.let { directory(it) }
            env?.let { environment().putAll(it) }
        }.start()

    val syncs = arrayOf<Deferred<Any?>>(
        async {
            (stdout as? Redirect.Consume)?.let {
                process.inputStream.lineFlow(charset, it.consumer)
            }
        },
        async {
            (stderr as? Redirect.Consume)?.let {
                process.errorStream.lineFlow(charset, it.consumer)
            }
        },
        async {
            (stdin as? InputSource.FromStream)?.handler?.let { handler ->
                process.outputStream.use { handler(it) }
            }
        },
        async {
            when {
                captureAll || stdout == Redirect.CAPTURE ->
                    process.inputStream

                stderr == Redirect.CAPTURE ->
                    process.errorStream

                else -> null
            }?.lineFlow(charset) { f ->
                @Suppress("ktlint:standard:chain-method-continuation")
                f.map {
                    yield()
                    it.also { consumer(it) }
                }.toList()
            } ?: emptyList()
        },
    )

    try {
        @Suppress("UNCHECKED_CAST")
        ProcessResult(
            // Consume the output before waitFor,
            // ensuring no content is skipped.
            output = awaitAll(*syncs).last() as List<String>,
            resultCode = runInterruptible { process.waitFor() },
        )
    } catch (e: CancellationException) {
        when (destroyForcibly) {
            true -> process.destroyForcibly()
            false -> process.destroy()
        }
        throw e
    }
}

private suspend fun <T> InputStream.lineFlow(
    charset: Charset,
    block: suspend (Flow<String>) -> T,
): T = bufferedReader(charset).use { it.lineSequence().asFlow().let { f -> block(f) } }

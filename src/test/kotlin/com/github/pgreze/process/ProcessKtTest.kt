package com.github.pgreze.process

import com.github.pgreze.process.Redirect.CAPTURE
import com.github.pgreze.process.Redirect.Consume
import com.github.pgreze.process.Redirect.PRINT
import com.github.pgreze.process.Redirect.SILENT
import com.github.pgreze.process.Redirect.ToFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText

class ProcessKtTest {
    private companion object {
        val OUT = arrayOf("hello world", "no worry")
        val ERR = arrayOf("e=omg", "e=windows")
        val ALL = arrayOf(OUT[0], ERR[0], OUT[1], ERR[1])

        fun Path.createScript(): Path =
            resolve("script.sh").also { f ->
                val text =
                    """
                    #!/usr/bin/env bash
                    for arg in "¥@"
                    do
                        if [[ "¥arg" == e=* ]]; then
                          echo 1>&2 "¥arg"
                        else
                          echo "¥arg"
                        fi
                    done
                    """.trimIndent().replace("¥", "$") // https://stackoverflow.com/a/30699291/5489877
                f.writeText(text)
                f.toFile().setExecutable(true)
            }
    }

    @Nested
    @DisplayName("print to console or not")
    inner class Print {
        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun test(print: Boolean) =
            runSuspendTest {
                val mode = if (print) PRINT else SILENT
                val res = process("echo", "hello world", stdout = mode, stderr = mode)

                res.resultCode shouldBeEqualTo 0
                res.output shouldBeEqualTo emptyList()
                // Could not find a way to test the inherit behavior...
            }
    }

    @Test
    fun `process support multiple arguments`() =
        runSuspendTest {
            val output = process("echo", *OUT, stdout = CAPTURE).unwrap()
            output shouldBeEqualTo listOf(OUT.joinToString(" "))
        }

    @Test
    fun `process support charset`() =
        runSuspendTest {
            val charset = Charset.forName("unicode")
            val text = "hello world"
            val inputStream = ByteArrayInputStream(text.toByteArray(charset))
            val output = process(
                "cat",
                stdin = InputSource.fromInputStream(inputStream),
                stdout = CAPTURE,
                charset = charset,
            ).unwrap()
            output shouldBeEqualTo listOf(text)
        }

    @Test
    fun `env is allowing to inject environment variables`() =
        runSuspendTest {
            val name = "PROCESS_VAR"
            val value = "42"
            val output = process("env", env = mapOf(name to value), stdout = CAPTURE).unwrap()
            output shouldContain "$name=$value"
        }

    @Test
    fun `directory is allowing to change folder`() =
        runSuspendTest {
            // Notice: use a temporary path in OSX is failing due to /tmp -> /private/var symlink...
            val dir = File(".").absoluteFile.parentFile
            val output = process(
                "pwd",
                "-L",
                directory = dir,
                stdout = CAPTURE,
            ).unwrap()
            output shouldBeEqualTo listOf(dir.path)
        }

    @Test
    fun `process redirect to files`(
        @TempDir dir: Path,
    ) = runSuspendTest {
        val script = dir.createScript()
        val errHeader = "bonjour"
        val out = dir.resolve("out.txt").toFile()
        val err = dir.resolve("err.txt").toFile().also { it.writeText("$errHeader\n") }
        process(
            script.absolutePathString(),
            *ALL,
            stdout = ToFile(out, append = false),
            stderr = ToFile(err, append = true),
        ).unwrap()

        out.readText() shouldBeEqualTo OUT.toList().joinLines()
        err.readText() shouldBeEqualTo arrayOf(errHeader, *ERR).toList().joinLines()
    }

    @Nested
    @DisplayName("process with all outputs as capture is merging them")
    inner class CaptureAllOutputs {
        @RepeatedTest(3) // Repeat to ensure no random order.
        fun test(
            @TempDir dir: Path,
        ) = runSuspendTest {
            val script = dir.createScript()
            val consumer = ByteArrayOutputStream()
            val res = process(
                script.absolutePathString(),
                *ALL,
                stdout = CAPTURE,
                stderr = CAPTURE,
                consumer = PrintStream(consumer)::println,
            )

            res.resultCode shouldBeEqualTo 0
            res.output shouldBeEqualTo ALL.toList()
            consumer.toString() shouldBeEqualTo res.output.joinLines()
        }
    }

    @Test
    fun `use Consume when CAPTURE is unnecessary`(
        @TempDir dir: Path,
    ) = runSuspendTest {
        val script = dir.createScript()
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        val output = process(
            script.absolutePathString(),
            *ALL,
            stdout = Consume { it.toList(stdout) },
            stderr = Consume { it.toList(stderr) },
        ).unwrap()

        output shouldBeEqualTo emptyList()
        stdout shouldBeEqualTo OUT.toList()
        stderr shouldBeEqualTo ERR.toList()
    }

    @Test
    fun `use consume on stdout and stderr in parallel`() = runSuspendTest {
        val stdoutAndStdErr = mutableListOf<String>()

        val output = process(
            command = arrayOf(
                "bash",
                "-c",
                "(echo 1 >&2; sleep 0.1; echo 2; sleep 0.1; echo 3 >&2; sleep 0.1; echo 4; sleep 0.1;)",
            ),
            stdout = Consume {
                it.collect(stdoutAndStdErr::add)
            },
            stderr = Consume {
                it.collect(stdoutAndStdErr::add)
            },
        ).unwrap()

        output shouldBeEqualTo emptyList()
        stdoutAndStdErr shouldBeEqualTo listOf(1, 2, 3, 4).map { it.toString() }
    }

    @Test
    fun `ensure Consume and CAPTURE are playing well together`(
        @TempDir dir: Path,
    ) = runSuspendTest {
        val script = dir.createScript()
        val stdout = mutableListOf<String>()

        val output = process(
            script.absolutePathString(),
            *ALL,
            stdout = Consume { it.toList(stdout) },
            stderr = CAPTURE,
        ).unwrap()

        output shouldBeEqualTo ERR.toList()
        stdout shouldBeEqualTo OUT.toList()
    }

    @Nested
    @DisplayName("print to console or not")
    inner class Cancellation {
        @ParameterizedTest
        @ValueSource(strings = ["CAPTURE", "SILENT", "Consume"])
        @Timeout(value = 3, unit = TimeUnit.SECONDS)
        fun `job cancellation should destroy the process`(captureMode: String) =
            runSuspendTest {
                var visitedCancelledBlock = false
                val job = launch(Dispatchers.IO) {
                    @Suppress("SwallowedException")
                    try {
                        val ret = process(
                            "cat", // cat without args is an endless process.
                            stdout = when (captureMode) {
                                "CAPTURE" -> CAPTURE
                                "SILENT" -> SILENT
                                "Consume" -> Consume { it.collect { } }
                                else -> throw IllegalArgumentException("Illegal capture mode: $captureMode")
                            },
                        )
                        throw AssertionError("Process completed despite being cancelled: $ret")
                    } catch (e: CancellationException) {
                        visitedCancelledBlock = true
                    }
                }

                // Introduce delays to be sure the job was started before being cancelled.
                delay(500L)
                job.cancel()
                delay(500L)

                job.isCancelled shouldBeEqualTo true
                job.isCompleted shouldBeEqualTo true
                visitedCancelledBlock shouldBeEqualTo true
            }
    }

    @Nested
    inner class Unwrap {
        @Test
        fun `a valid result throws nothing`() {
            ProcessResult(resultCode = 0, output = emptyList())
                .unwrap() shouldBeEqualTo emptyList()
        }

        @Test
        fun `an invalid result throws an IllegalStateException`() {
            val exception = assertThrows<IllegalStateException> {
                ProcessResult(resultCode = 1, output = emptyList()).unwrap()
            }
            exception.message!! shouldBeEqualTo "Invalid result: 1"
        }
    }
}

package com.github.pgreze.process

import com.github.pgreze.process.RedirectMode.CAPTURE
import com.github.pgreze.process.RedirectMode.File
import com.github.pgreze.process.RedirectMode.PRINT
import com.github.pgreze.process.RedirectMode.SILENT
import com.github.pgreze.process.RedirectMode.Streaming
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

@ExperimentalCoroutinesApi
class ProcessKtTest {

    companion object {
        val OUT = arrayOf("hello world", "no worry")
        val ERR = arrayOf("e=omg", "e=windows")
        val ALL = arrayOf(OUT[0], ERR[0], OUT[1], ERR[1])
        val CMD = arrayOf("./print.sh", *ALL)
    }

    @Nested
    @DisplayName("print to console or not")
    inner class Print {
        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun test(print: Boolean) = runTestBlocking {
            val mode = if (print) PRINT else SILENT
            val res = process("echo", "hello world", stdout = mode, stderr = mode)

            res.resultCode shouldBeEqualTo 0
            res.output shouldBeEqualTo emptyList()
            // Could not find a way to test the inherit behavior...
        }
    }

    @Test
    fun `env is allowing to inject environment variables`() = runTestBlocking {
        val name = "PROCESS_VAR"
        val value = "42"
        val output = process("env", env = mapOf(name to value), stdout = CAPTURE).validate()
        output shouldContain "$name=$value"
    }

    @Test
    fun `process redirect to files`(@TempDir dir: Path) = runTestBlocking {
        val errHeader = "bonjour"
        val out = dir.resolve("out.txt").toFile()
        val err = dir.resolve("err.txt").toFile().also { it.writeText("$errHeader\n") }
        process(
            *CMD,
            stdout = File(out, append = false),
            stderr = File(err, append = true),
        ).validate()

        out.readText() shouldBeEqualTo OUT.toList().joinLines()
        err.readText() shouldBeEqualTo arrayOf(errHeader, *ERR).toList().joinLines()
    }

    @Nested
    @DisplayName("process with all outputs as capture is merging them")
    inner class CaptureAllOutputs {
        @RepeatedTest(3) // Repeat to ensure no random order.
        fun test() = runTestBlocking {
            val consumer = ByteArrayOutputStream()
            val res = process(
                *CMD,
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
    fun `use Streaming when CAPTURE is unnecessary`() = runTestBlocking {
        val consumer = mutableListOf<String>()
        val output = process(
            *CMD,
            stdout = Streaming(consumer::add),
            stderr = CAPTURE,
        ).validate()

        output shouldBeEqualTo ERR.toList()
        consumer shouldBeEqualTo OUT.toList()
    }

    @Nested
    inner class Validate {
        @Test
        fun `a valid result throws nothing`() {
            ProcessResult(resultCode = 0, output = emptyList()).validate() shouldBeEqualTo emptyList()
        }

        @Test
        fun `an invalid result throws an IllegalStateException`() {
            val exception = assertThrows<IllegalStateException> {
                ProcessResult(resultCode = 1, output = emptyList()).validate()
            }
            exception.message!! shouldBeEqualTo "Invalid result: 1"
        }
    }
}

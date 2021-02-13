package com.github.pgreze.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.*

@ExperimentalCoroutinesApi
class InputSourceTest {
    private companion object {
        const val STRING = "hello world"
        val LINES: List<String> = (0..5).map { "Hello $it" }
    }

    @Test
    fun fromString() = runTestBlocking {
        val output = process(
            "cat",
            stdin = InputSource.fromString(STRING),
            stdout = Redirect.CAPTURE
        ).unwrap()
        output shouldBeEqualTo listOf(STRING)
    }

    @Test
    fun fromStream() = runTestBlocking {
        val inputStream = ByteArrayInputStream(STRING.toByteArray())
        val output = process(
            "cat",
            stdin = InputSource.fromInputStream(inputStream),
            stdout = Redirect.CAPTURE
        ).unwrap()
        output shouldBeEqualTo listOf(STRING)
    }

    @Nested
    @DisplayName("ensure that input and output are concurrently processed")
    inner class AsyncStreams {

        @RepeatedTest(5)
        fun test() = runTestBlocking {
            // Used to synchronized producer and consumer
            val channel = Channel<Unit>(1)
            val consumer = Collections.synchronizedList(mutableListOf<String>())
            val proc = async(Dispatchers.IO) {
                process(
                    "cat",
                    stdin = InputSource.FromStream { str ->
                        LINES.forEach {
                            str.write("$it\n".toByteArray())
                            str.flush()
                            channel.receive()
                        }
                    },
                    stdout = Redirect.CAPTURE,
                    consumer = consumer::add
                )
            }

            LINES.toList()
                .let { (1..it.size).map { idx -> it.subList(0, idx) } }
                .forEach { list ->
                    delay(50)
                    consumer shouldBeEqualTo list
                    channel.send(Unit)
                }

            proc.await().unwrap()
        }
    }
}

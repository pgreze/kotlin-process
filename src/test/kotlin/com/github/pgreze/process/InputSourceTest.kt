package com.github.pgreze.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import java.io.ByteArrayInputStream
import java.util.*

@ExperimentalCoroutinesApi
class InputSourceTest {
    companion object {
        const val string = "hello world"
    }

    @Test
    fun fromString() = runTestBlocking {
        val output = process(
            "cat",
            stdin = InputSource.fromString(string),
            stdout = Redirect.CAPTURE
        ).validate()
        output shouldBeEqualTo listOf(string)
    }

    @Test
    fun fromStream() = runTestBlocking {
        val inputStream = ByteArrayInputStream(string.toByteArray())
        val output = process(
            "cat",
            stdin = InputSource.fromInputStream(inputStream),
            stdout = Redirect.CAPTURE
        ).validate()
        output shouldBeEqualTo listOf(string)
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
                        ProcessKtTest.ALL.forEach {
                            str.write("$it\n".toByteArray())
                            str.flush()
                            channel.receive()
                        }
                    },
                    stdout = Redirect.CAPTURE,
                    consumer = consumer::add
                )
            }

            ProcessKtTest.ALL.toList()
                .let { (1..it.size).map { idx -> it.subList(0, idx) } }
                .forEach { list ->
                    delay(50)
                    consumer shouldBeEqualTo list
                    channel.send(Unit)
                }

            proc.await().validate()
        }
    }
}
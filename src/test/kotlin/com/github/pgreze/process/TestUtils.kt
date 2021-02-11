package com.github.pgreze.process

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

fun runTestBlocking(block: suspend CoroutineScope.() -> Unit) {
    runBlocking { block() }
}

fun List<String>.joinLines(): String =
    joinToString(separator = "") { "$it\n" }

package com.github.pgreze.process

import kotlinx.coroutines.flow.Flow

sealed class Redirect {
    /** Ignores the related stream. */
    object SILENT : Redirect()

    /**
     * Redirect the stream to this process equivalent one.
     * In other words, it will print to the terminal if this process is also doing so.
     *
     * This is correctly using [System.out] or [System.err] depending on the stream +
     * preserving the correct order.
     * @see ProcessBuilder.Redirect.INHERIT
     * @see Consume when you want to have full control on the outcome.
     */
    object PRINT : Redirect()

    /**
     * This will ensure that the stream content is returned as [process]'s return.
     * If both stdout and stderr are using this mode, their output will be correctly merged.
     *
     * It's also possible to consume this content without delay by using [process]'s consumer argument.
     * @see [ProcessBuilder.redirectErrorStream]
     */
    object CAPTURE : Redirect()

    /** Redirect to a file, overriding or appending on demand. */
    class File(val file: java.io.File, val append: Boolean = false) : Redirect()

    /**
     * Alternative to [CAPTURE] allowing to consume without delay a stream
     * without storing it in memory, and so not returned at the end of [process] invocation.
     * @see [process]'s consumer argument to consume [CAPTURE] content without delay.
     */
    class Consume(val consumer: suspend (Flow<String>) -> Unit) : Redirect()
}

internal fun Redirect.toNative() = when (this) {
    Redirect.SILENT -> ProcessBuilder.Redirect.DISCARD
    Redirect.PRINT -> ProcessBuilder.Redirect.INHERIT
    Redirect.CAPTURE -> ProcessBuilder.Redirect.PIPE
    is Redirect.File -> when (append) {
        true -> ProcessBuilder.Redirect.appendTo(file)
        false -> ProcessBuilder.Redirect.to(file)
    }
    is Redirect.Consume -> ProcessBuilder.Redirect.PIPE
}

# kotlin-process [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) ![Build](https://github.com/pgreze/kotlin-process/workflows/Build/badge.svg?branch=master) ![codecov](https://codecov.io/gh/pgreze/kotlin-process/branch/master/graph/badge.svg)

Functional Kotlin friendly way to create external system processes
by leveraging the powerful but convoluted
[ProcessBuilder](https://docs.oracle.com/javase/7/docs/api/java/lang/ProcessBuilder.html)
and Kotlin coroutines.

## Installation  [![central](https://maven-badges.herokuapp.com/maven-central/com.github.pgreze/kotlin-process/badge.svg?style={style})](https://search.maven.org/artifact/com.github.pgreze/kotlin-process)

```kotlin
dependencies {
    // Check the 🔝 maven central badge 🔝 for the latest $version
    implementation("com.github.pgreze:kotlin-process:$version")
}

repositories {
    jcenter()
}
```

## Usage [![](https://img.shields.io/badge/dokka-read-blue)](https://kotlin-process.netlify.app/)

### Launch a script and consume its results

Starts a program and prints its stdout/stderr outputs to the terminal:

```kotlin
import com.github.pgreze.process.process
import kotlinx.coroutines.runBlocking

runBlocking {
    val res = process("echo", "hello world")
    check(res.resultCode == 0)
    check(res.output == emptyList<String>())
}
```

The next is probably to capture the output stream,
in order to process some data from our own-made script:

```kotlin
val output = process(
    "./my-script.sh", arg1, arg2,

    // Capture stdout lines to do some operations after
    stdout = Redirect.CAPTURE,

    // Default value, prints to System.err
    stderr = Redirect.PRINT,

).unwrap() // Fails if the resultCode != 0

// TODO: process the output
println("Success:\n${output.joinToString("\n")}")
```

Notice that if you want to capture both stdout and stderr,
there's no way to differentiate them in the returned output:

```kotlin
val res = process(
    "./long-and-dangerous.sh", arg1, arg2,

    // Both streams will be captured,
    // preserving their orders but mixing them in the result.
    stdout = Redirect.CAPTURE,
    stderr = Redirect.CAPTURE,

    // Allows to consume line by line without delay the provided output.
    consumer = { line -> TODO("process a line") },
)

println("Script finished with result=${res.resultCode}")
println("stdout+stderr:\n" + res.output.joinToString("\n"))
```

It's also possible to redirect an output stream to a file,
or manually by consuming a Flow<String> instance.

```kotlin
import com.github.pgreze.process.Redirect
import com.github.pgreze.process.process
import java.io.File
import java.util.Collections
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

val errLines = Collections.synchronizedList(mutableListOf<String>())
val res = process(
    "./my-script.sh", arg1, arg2,

    // You can save the execution result in a file,
    stdout = Redirect.ToFile(File("my-input.txt")),

    // If you want to handle this stream yourself,
    // a Flow<String> instance can be used.
    stderr = Redirect.Consume { flow -> flow.toList(errLines) },
)
```

The last but not least, you can just silence a stream with Redirect.SILENT 😶

### Control the environment

Several other options are available to control the script environment:

```kotlin
import com.github.pgreze.process.InputSource
import java.io.File

val res = process(
    "./my-script.sh",
    
    // Provides the input as a string, similar to:
    // $ echo "hello world" | my-script.sh
    stdin = InputSource.fromString("hello world"),

    // Inject custom environment variables:
    env = mapOf("MY_ENV_VARIABLE" to "42"),

    // Override the working directory:
    directory = File("./a/directory"),
)
```

There are other ways to provide the process input:

```kotlin
// From a file:
process(
    "./my-script.sh",
    stdin = InputSource.FromFile(File("my-input.txt")),
)

// From an InputStream:
process(
    "./my-script.sh",
    stdin = InputSource.fromInputStream(myInputStream)),
)

// Manually by using the raw OutputStream:
process(
    "./my-script.sh",
    stdin = InputSource.FromStream { out: OutputStream ->
        out.write("hello world\n".toByteArray())
        out.flush()
    },
)
```

@file:JvmName("RootThreadKt")
@file:Suppress("unused")

package dev.mmrlx.thread.ktx

import dev.mmrlx.thread.RootArgs
import dev.mmrlx.thread.RootCallable
import dev.mmrlx.thread.RootConsumer
import dev.mmrlx.thread.RootThread
import dev.mmrlx.thread.RootThreadExtensions

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import dev.mmrlx.thread.RootThreadLifecycleObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspends the coroutine until this [Future] completes, using only stdlib coroutines.
 *
 * Runs [Future.get] on [Dispatchers.IO] (a blocking-friendly thread pool) and resumes
 * the coroutine with the result or exception.  Cancellation cancels the future and
 * re-throws [CancellationException] without leaking the thread.
 */
suspend fun <T> Future<T>.awaitRoot(): T =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel(true) }
        try {
            cont.resume(get())
        } catch (e: ExecutionException) {
            cont.resumeWithException(e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            cont.resumeWithException(CancellationException("Interrupted", e))
        } catch (e: CancellationException) {
            cancel(true)
            cont.resumeWithException(e)
        }
    }

/**
 * Suspends the coroutine, executes [block] in the root process, and resumes
 * with the result on the original dispatcher.
 *
 * ```kotlin
 * val version = rootThread { SystemProperties.get("ro.build.version.release") }
 * ```
 *
 * @throws IOException on IPC or remote execution failure.
 */
suspend fun <T> rootThread(block: RootCallable<T>): T =
    withContext(Dispatchers.IO) {
        RootThread.submit(block).awaitRoot()
    }

/**
 * Suspends the coroutine, executes [block] in the root process with [this] as the
 * receiver, and resumes with the result.
 *
 * ```kotlin
 * val packages = packageManager.rootThread { pm ->
 *     pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
 * }
 * ```
 *
 * @throws IOException on IPC or remote execution failure.
 */
suspend fun <T, R> T.rootThread(block: RootConsumer<T, R>): R =
    withContext(Dispatchers.IO) {
        RootThread.submit { block.call(this@rootThread, it) }.awaitRoot()
    }

/**
 * Launches a root callable in the current [CoroutineScope].
 * Exceptions are propagated through the scope's job as normal coroutine failures.
 *
 * ```kotlin
 * viewModelScope.rootLaunch {
 *     Runtime.getRuntime().exec("chmod 777 /data/local/tmp/file")
 * }
 * ```
 */
fun CoroutineScope.rootLaunch(
    block: RootCallable<Unit>,
): Job = launch {
    rootThread(block)
}

/**
 * Launches a root callable that produces a [Deferred] result.
 *
 * ```kotlin
 * val deferred = viewModelScope.rootAsync { readRootFile("/data/adb/config") }
 * val content = deferred.await()
 * ```
 */
fun <T> CoroutineScope.rootAsync(
    block: RootCallable<T>,
): Deferred<T> = async {
    rootThread(block)
}

/**
 * Returns a cold [Flow] that executes [block] in the root
 * process on each collection and emits a single value.
 *
 * ```kotlin
 * rootFlow { File("/proc/version").readText() }
 *     .flowOn(Dispatchers.IO)
 *     .collect { version -> textView.text = version }
 * ```
 */
fun <T> rootFlow(block: RootCallable<T>): Flow<T> =
    flow { emit(rootThread(block)) }

/**
 * Executes [block] in the root process, blocking the calling thread.
 * Must NOT be called on the main thread.
 *
 * ```kotlin
 * val exists = rootBlocking { File("/system/bin/su").exists() }
 * ```
 */
@Throws(IOException::class, InterruptedException::class)
fun <T> rootBlocking(block: RootCallable<T>): T? =
    RootThread.executeBlocking(block)

/**
 * Executes [block] in the root process with [this] as the receiver,
 * blocking the calling thread.  Must NOT be called on the main thread.
 *
 * ```kotlin
 * val packages = packageManager.rootBlocking { pm ->
 *     pm.getInstalledPackages(0)
 * }
 * ```
 */
@Throws(IOException::class, InterruptedException::class)
fun <T : Any, R> T.rootBlocking(block: RootConsumer<T, R>): R? =
    RootThreadExtensions.rootBlocking(this, block)

/**
 * Timed variant of [rootBlocking].
 *
 * ```kotlin
 * val result = rootBlocking(5, TimeUnit.SECONDS) { readHeavyRootFile() }
 * ```
 */
@Throws(
    IOException::class,
    InterruptedException::class,
    java.util.concurrent.TimeoutException::class
)
fun <T> rootBlocking(timeout: Long, unit: TimeUnit, block: RootCallable<T>): T? =
    RootThread.executeBlocking(block, timeout, unit)

/**
 * Allows [RootThread] to be called as a function from a suspend context:
 *
 * ```kotlin
 * val result = RootThread { doPrivilegedWork() }
 * ```
 */
suspend operator fun <T> RootThread.invoke(block: RootCallable<T>): T =
    rootThread(block)

// -------------------------------------------------------------------------------------------------
// Args-aware overloads
// -------------------------------------------------------------------------------------------------

/**
 * Suspends the coroutine, executes [block] in the root process with [args] available via
 * [dev.mmrlx.thread.RootOptions.getArgs], and resumes with the result.
 *
 * ```kotlin
 * val result = rootThread(RootArgs.of("path", "/data/local/tmp/file")) { opts ->
 *     File(opts.args.get<String>("path")).readText()
 * }
 * ```
 */
suspend fun <T> rootThread(args: RootArgs, block: RootCallable<T>): T =
    withContext(Dispatchers.IO) {
        RootThread.submit(block, args).awaitRoot()
    }

/**
 * Executes [block] in the root process with [args] and [this] as the receiver,
 * blocking the calling thread.  Must NOT be called on the main thread.
 */
@Throws(IOException::class, InterruptedException::class)
fun <T> rootBlocking(args: RootArgs, block: RootCallable<T>): T? =
    RootThread.executeBlocking(block, args)

/**
 * Timed variant of [rootBlocking] with custom [args].
 */
@Throws(
    IOException::class,
    InterruptedException::class,
    java.util.concurrent.TimeoutException::class
)
fun <T> rootBlocking(args: RootArgs, timeout: Long, unit: TimeUnit, block: RootCallable<T>): T? =
    RootThread.executeBlocking(block, args, timeout, unit)

/**
 * Attaches [RootThread] bind/unbind to this [LifecycleOwner]'s lifecycle.
 *
 * ```kotlin
 * // In Activity.onCreate or Fragment.onViewCreated:
 * addRootThread(requireContext())
 * ```
 */
fun LifecycleOwner.addRootThread(context: Context) {
    lifecycle.addObserver(RootThreadLifecycleObserver(context))
}

/**
 * DSL that groups multiple root calls under a single bind/execute lifecycle,
 * collecting results into a typed map.
 *
 * ```kotlin
 * val results = rootBlock {
 *     val su   = exec { File("/system/bin/su").exists() }
 *     val ver  = exec { SystemProperties.get("ro.build.version.release") }
 *     mapOf("su" to su, "version" to ver)
 * }
 * ```
 */
suspend fun <T> rootBlock(block: suspend RootBlockScope.() -> T): T =
    withContext(Dispatchers.IO) {
        RootBlockScope().block()
    }

/** Scope for [rootBlock], providing a typed [exec] helper. */
class RootBlockScope {
    /**
     * Executes a single [RootCallable] and suspends until it completes.
     */
    suspend fun <T> exec(block: RootCallable<T>): T = rootThread(block)
}

/**
 * Suspending variant that wraps the result in [Result], never throwing.
 *
 * ```kotlin
 * rootThreadCatching { dangerousRootOp() }
 *     .onSuccess { result -> /* use it */ }
 *     .onFailure { error -> log(error) }
 * ```
 */
suspend fun <T> rootThreadCatching(block: RootCallable<T>): Result<T> =
    runCatching { rootThread(block) }

/**
 * Blocking variant that wraps the result in [Result], never throwing.
 *
 * ```kotlin
 * val result = rootBlockingCatching { File("/proc/version").readText() }
 * ```
 */
fun <T> rootBlockingCatching(block: RootCallable<T>): Result<T> =
    runCatching { RootThread.executeBlocking(block) as T }
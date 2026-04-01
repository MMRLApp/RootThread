package dev.mmrlx.thread.ktx

import dev.mmrlx.thread.RootArgs
import dev.mmrlx.thread.RootCallable
import kotlinx.coroutines.flow.Flow

/**
 * Converts this [RootCallable] into a [Flow].
 *
 * When the resulting flow is collected, the callable is executed and its result
 * is emitted to the flow.
 *
 * @receiver The [RootCallable] to be converted into a flow.
 * @return A [Flow] that executes the callable and emits its result upon collection.
 */
fun <T> RootCallable<T>.asFlow(): Flow<T> = rootFlow(this)

/**
 * Executes this [RootCallable] within a thread and returns the result.
 *
 * This is a suspending extension function that delegates to [rootThread].
 *
 * @return The result of the [RootCallable] execution.
 */
suspend fun <T> RootCallable<T>.asThread(): T = rootThread(this)

/**
 * Invokes the [RootCallable] instance as a function.
 *
 * This operator function allows for a concise syntax to execute the callable
 * logic, delegating the execution to the underlying thread mechanism.
 *
 * @return The result produced by the [RootCallable].
 */
suspend operator fun <T> RootCallable<T>.invoke(args: Map<String, Any>? = null): T {
    if (args == null) return rootThread(this)
    val mArgs = RootArgs.of(args)
    return rootThread(mArgs, this)
}
package dev.mmrlx.thread.ktx

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
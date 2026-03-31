package dev.mmrlx.thread;

import java.io.Serializable;

/**
 * A serializable bifunctional closure that accepts a receiver of type {@code T}, executes
 * in the root process with that receiver, and returns a value of type {@code R}.
 *
 * <p>This interface mirrors the Kotlin {@code rootBlocking} extension pattern, making it easy
 * to associate a receiver object with the root callable without wrapping it manually.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * String result = RootThreadExtensions.rootBlocking(myManager, manager -> {
 *     return manager.doPrivilegedOperation();
 * });
 * }</pre>
 *
 * @param <T> The receiver type passed into the call.
 * @param <R> The return type.
 */
@FunctionalInterface
public interface RootConsumer<T, R> extends Serializable {

    /**
     * Executes the work in the root process with the given receiver.
     *
     * @param receiver The object captured and forwarded to the root process.
     * @return The result to be transported back to the caller's process.
     * @throws Exception Any exception; it will be wrapped and re-thrown in the caller's process.
     */
    R call(T receiver, RootOptions options) throws Exception;
}

package dev.mmrlx.thread;

import java.io.Serializable;
import android.os.Parcelable;
import android.content.Context;

/**
 * A serializable closure that executes in the root process and returns a value of type {@code T}.
 *
 * <p>Implementations <strong>must</strong> be serializable by Kryo (i.e. not capture references
 * to non-serializable objects such as Android {@link Context}).  Capture only
 * primitive values, {@link Parcelable} objects, or other Kryo-compatible types.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * RootCallable<String> callable = () -> {
 *     return new File("/data/local/tmp/test.txt").exists() ? "found" : "missing";
 * };
 * String result = RootThread.executeBlocking(callable);
 * }</pre>
 *
 * @param <T> The return type.  Use {@link Void} (returning {@code null}) for fire-and-forget
 *            operations that produce no result.
 */
@FunctionalInterface
public interface RootCallable<T> extends Serializable {
    /**
     * Executes the work in the root process.
     *
     * @return The result to be transported back to the caller's process.
     * @throws Exception Any exception; it is caught, serialized, and re-thrown as an
     *                   {@link java.io.IOException} in the caller's process.
     */
    T call(RootOptions options) throws Exception;
}
package dev.mmrlx.thread;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Static utility methods that extend {@link RootThread} functionality.
 */
public final class RootThreadExtensions {

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private RootThreadExtensions() {}

    /**
     * Submits a {@link RootCallable} for execution in the root process and delivers the
     * result on the supplied {@link Executor}.
     *
     * @param callable  The work to run in the root process.
     * @param callback  Optional callback invoked with the result or exception.  May be {@code null}.
     * @param executor  Executor on which {@code callback} is invoked.
     * @param <T>       Return type.
     * @return          A {@link Future} that can be used to cancel or join the operation.
     */
    @NonNull
    public static <T> Future<T> rootLaunch(
            @NonNull RootCallable<T> callable,
            @Nullable RootCallback<T> callback,
            @NonNull Executor executor
    ) {
        Future<T> future = RootThread.submit(callable);
        if (callback != null) {
            executor.execute(() -> {
                try {
                    T result = future.get();
                    executor.execute(() -> callback.onSuccess(result));
                } catch (Exception e) {
                    Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                    executor.execute(() -> callback.onFailure(cause));
                }
            });
        }
        return future;
    }

    /**
     * Convenience overload of {@link #rootLaunch} that delivers the callback on the
     * Android main thread.
     *
     * @param callable The work to run in the root process.
     * @param callback Optional callback invoked on the main thread with the result or exception.
     * @param <T>      Return type.
     * @return         A {@link Future} that can be used to cancel or join the operation.
     */
    @NonNull
    public static <T> Future<T> rootLaunch(
            @NonNull RootCallable<T> callable,
            @Nullable RootCallback<T> callback
    ) {
        return rootLaunch(callable, callback, sMainHandler::post);
    }

    /**
     * Minimal overload for fire-and-forget callers that need no result or error handling.
     *
     * @param callable The work to run in the root process.
     * @return         A {@link Future} for optional cancellation / joining.
     */
    @NonNull
    public static Future<Void> rootLaunch(@NonNull RootCallable<Void> callable) {
        return RootThread.submit(callable);
    }

    /**
     * Executes {@code block} in the root process with {@code receiver} as its first argument,
     * blocking the calling thread until a result (or exception) is available.
     *
     * <p>Must NOT be called on the Android main thread.
     *
     * @param receiver The object to pass to the root callable.
     * @param block    The work to perform in the root process.
     * @param <T>      Type of the receiver.
     * @param <R>      Return type.
     * @return         The value returned by {@code block}.
     * @throws IOException          If IPC or remote execution fails.
     * @throws InterruptedException If the calling thread is interrupted while waiting.
     */
    @Nullable
    public static <T, R> R rootBlocking(
            @NonNull T receiver,
            @NonNull RootConsumer<T, R> block
    ) throws IOException, InterruptedException {
        return RootThread.executeBlocking((options) -> block.call(receiver, options));
    }

    /**
     * Timed variant of {@link #rootBlocking(Object, RootConsumer)}.
     *
     * @param receiver The object to pass to the root callable.
     * @param block    The work to perform in the root process.
     * @param timeout  Maximum time to wait.
     * @param unit     Time unit for {@code timeout}.
     * @param <T>      Type of the receiver.
     * @param <R>      Return type.
     * @return         The value returned by {@code block}.
     * @throws IOException          If IPC or remote execution fails.
     * @throws InterruptedException If the calling thread is interrupted while waiting.
     * @throws TimeoutException     If the operation exceeds the timeout.
     */
    @Nullable
    public static <T, R> R rootBlocking(
            @NonNull T receiver,
            @NonNull RootConsumer<T, R> block,
            long timeout,
            @NonNull TimeUnit unit
    ) throws IOException, InterruptedException, TimeoutException {
        return RootThread.executeBlocking((options) -> block.call(receiver, options), timeout, unit);
    }

    /**
     * Attaches {@link RootThread} to the given {@link LifecycleOwner}'s lifecycle.
     *
     * <ul>
     *   <li>{@link DefaultLifecycleObserver#onStart} → {@link RootThread#bind(Context)}</li>
     *   <li>{@link DefaultLifecycleObserver#onStop}  → {@link RootThread#unbind()}</li>
     * </ul>
     *
     * <p>Replaces the Kotlin extension function {@code LifecycleOwner.addRootThread(context)}.
     *
     * @param owner   The lifecycle owner (typically an Activity or Fragment).
     * @param context A context used to construct the service intent.
     */
    public static void addRootThread(
            @NonNull LifecycleOwner owner,
            @NonNull Context context
    ) {
        owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner o) {
                RootThread.bind(context);
            }

            @Override
            public void onStop(@NonNull LifecycleOwner o) {
                RootThread.unbind();
            }
        });
    }

    /**
     * Callback interface for async root operations launched via {@link #rootLaunch}.
     *
     * @param <T> The result type.
     */
    public interface RootCallback<T> {

        /**
         * Called when the root callable completes successfully.
         *
         * @param result The value returned by the callable.
         */
        void onSuccess(@Nullable T result);

        /**
         * Called when the root callable throws an exception or IPC fails.
         *
         * @param error The cause of the failure.
         */
        void onFailure(@NonNull Throwable error);
    }
}
package dev.mmrlx.thread;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * A {@link DefaultLifecycleObserver} that automatically binds and unbinds {@link RootThread}
 * in step with the host {@link LifecycleOwner}.
 *
 * <p>Attach it once in {@code onCreate} (or constructor):
 * <pre>{@code
 * // In Activity.onCreate():
 * getLifecycle().addObserver(new RootThreadLifecycleObserver(this));
 *
 * // In Fragment.onViewCreated() / constructor:
 * getLifecycle().addObserver(new RootThreadLifecycleObserver(requireContext()));
 * }</pre>
 *
 * <p>This is the Java equivalent of the Kotlin extension function
 * {@code LifecycleOwner.addRootThread(context)}.
 */
public final class RootThreadLifecycleObserver implements DefaultLifecycleObserver {

    @NonNull
    private final Context mContext;

    /**
     * Creates an observer that uses {@code context} to bind the root service.
     *
     * @param context Application or Activity context; stored internally as
     *                {@link Context#getApplicationContext()} to prevent leaks.
     */
    public RootThreadLifecycleObserver(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        RootThread.bind(mContext);
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        RootThread.unbind();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        RootThread.unbind();
    }
}
package dev.mmrlx.thread;

import android.content.Context;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.ipc.RootService;

/**
 * Represents the configuration options for root-related operations within the threading framework.
 * <p>
 * This class encapsulates necessary dependencies and environment settings, such as the
 * {@link Context} and any custom {@link RootArgs}, required for executing tasks that interact
 * with the system at a root level.
 */
public class RootOptions {
    @NonNull
    private final Context mContext;

    @NonNull
    private final RootArgs mArgs;

    public RootOptions(@NonNull Context context, @NonNull RootArgs args) {
        mContext = context;
        mArgs = args;
    }

    /**
     * Returns the context associated with these root options.
     * <p>
     * <b>Note:</b> Although this context is derived from a {@link RootService}, the
     */
    @NonNull
    public Context getContext() {
        return mContext;
    }

    /**
     * Returns the custom arguments that were supplied when the callable was submitted.
     * Returns {@link RootArgs#EMPTY} if no arguments were provided.
     */
    @NonNull
    public RootArgs getArgs() {
        return mArgs;
    }
}

package dev.mmrlx.thread;

import android.content.Context;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.ipc.RootService;

/**
 * Represents the configuration options for root-related operations within the threading framework.
 * <p>
 * This class encapsulates necessary dependencies and environment settings, such as the
 * {@link Context}, required for executing tasks that interact with the system at a root level.
 */
public class RootOptions {
    @NonNull
    private final Context mContext;

    RootOptions(@NonNull Context context) {
        mContext = context;
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
}

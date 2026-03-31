package dev.mmrlx.thread;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import org.jetbrains.annotations.Contract;

@RestrictTo(RestrictTo.Scope.LIBRARY)
class LazyContext {
    private static final String TAG = "LazyContext";

    // TODO: Alternative?
    @SuppressLint({"StaticFieldLeak"})
    private static volatile LazyContext instance;

    private Context context;

    private LazyContext() {
        // private constructor
    }

    @NonNull
    @Contract(" -> new")
    public static Context get() {
        LazyContext ctx = instance;
        if (ctx == null) {
            synchronized (LazyContext.class) {
                ctx = instance;
                if (ctx == null) {
                    ctx = new LazyContext();
                    instance = ctx;
                }
            }
        }
        return new ContextWrapper(ctx.getContext());
    }

    public Context getContext() {
        if (context == null) {
            context = resolveApplicationContext();
            if (context == null) {
                throw new IllegalStateException("Application context could not be retrieved");
            }
        }
        return context;
    }

    public Context getContextImpl(Context context) {
        Context ctx = context;
        while (ctx instanceof ContextWrapper) {
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return ctx;
    }

    public Context getDeContext() {
        Context deContext = getContext().createDeviceProtectedStorageContext();
        if (deContext == null) {
            throw new IllegalStateException("Device-protected storage context unavailable");
        }
        return deContext;
    }

    @SuppressLint("PrivateApi")
    private Context resolveApplicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread
                    .getMethod("currentApplication")
                    .invoke(null);

            if (app instanceof Context) {
                return getContextImpl((Context) app);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to retrieve application context", e);
        }
        return null;
    }
}

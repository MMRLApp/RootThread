package dev.mmrlx.thread;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import com.topjohnwu.superuser.ipc.RootService;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * Root-side IPC service.  Runs as a privileged process.
 *
 * <p>The service receives a {@link RootCallable} via {@link IRootThread#execute(ParcelFileDescriptor, ParcelFileDescriptor)},
 * deserializes it from the {@code callableRead} pipe, executes it on a background thread,
 * and writes the result (or any {@link Throwable}) back through the {@code resultWrite} pipe.
 *
 * <p>All execution happens on the shared {@link ExecutorService}
 * managed by the AIDL stub.  The service holds no thread-local state.
 */
public final class RootThreadService extends RootService {

    private static final String TAG = "RootThreadService";

    @Override
    public android.os.IBinder onBind(@NonNull android.content.Intent intent) {
        return new RootThreadBinder(this);
    }

    /**
     * AIDL stub that receives FD pairs and dispatches execution.
     *
     * <p>FD ownership:
     * <ul>
     *   <li>{@code callableRead} and {@code resultWrite} are consumed by this method;
     *       they are closed when the pipe streams are closed.</li>
     *   <li>The caller retains {@code resultRead} and waits for EOF on it.</li>
     * </ul>
     */
    private static final class RootThreadBinder extends IRootThread.Stub {
        private final Context mContext;

        private RootThreadBinder(Context context) {
            this.mContext = context;
        }

        private volatile ApkClassResolver appClassResolver;

        private ApkClassResolver getAppClassResolver() {
            if (appClassResolver == null) {
                synchronized (this) {
                    if (appClassResolver == null) {
                        try {
                            android.content.pm.ApplicationInfo appInfo =
                                    mContext.getPackageManager()
                                            .getApplicationInfo(mContext.getPackageName(), 0);

                            appClassResolver = new ApkClassResolver(
                                    appInfo.sourceDir,
                                    appInfo.splitSourceDirs,
                                    appInfo.nativeLibraryDir,
                                    getClass().getClassLoader()
                            );
                        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
            return appClassResolver;
        }

        @Override
        public void execute(
                @NonNull ParcelFileDescriptor callableRead,
                @NonNull ParcelFileDescriptor resultWrite
        ) {
            Thread worker = new Thread(() -> {
                Object result;

                try (ParcelFileDescriptor.AutoCloseInputStream fis =
                             new ParcelFileDescriptor.AutoCloseInputStream(callableRead);
                     Input input = new Input(fis)) {

                    RootThread.KryoManager kryo = RootThread.buildKryo(
                            getAppClassResolver(),
                            RootThreadBinder.class.getClassLoader());
                    RootThread.CallableEnvelope envelope =
                            (RootThread.CallableEnvelope) kryo.readClassAndObject(input);

                    try {
                        RootOptions options = new RootOptions(mContext, envelope.args());
                        result = envelope.callable().call(options);
                    } catch (Throwable t) {
                        Log.e(TAG, "Root callable threw", t);
                        result = t;
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Failed to deserialize callable", e);
                    result = new IOException("Deserialisation failed in root process", e);
                }

                try (ParcelFileDescriptor.AutoCloseOutputStream fos =
                             new ParcelFileDescriptor.AutoCloseOutputStream(resultWrite);
                     Output output = new Output(fos)) {

                    RootThread.KryoManager kryo = RootThread.buildKryo(
                            getAppClassResolver(),
                            RootThreadBinder.class.getClassLoader());
                    kryo.writeClassAndObject(output, result);
                    output.flush();

                } catch (Exception e) {
                    Log.e(TAG, "Failed to serialise result", e);
                    // resultWrite may be partially written or already closed;
                    // the caller will see an IOException when it reads.
                    RootThread.closeQuietly(resultWrite);
                }
            }, "RootThread-Worker");

            worker.setDaemon(true);
            worker.start();
        }
    }
}

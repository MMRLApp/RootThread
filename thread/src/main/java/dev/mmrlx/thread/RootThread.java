package dev.mmrlx.thread;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.esotericsoftware.kryo.ClassResolver;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultClassResolver;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import com.esotericsoftware.kryo.util.MapReferenceResolver;
import com.topjohnwu.superuser.ipc.RootService;

import org.objenesis.strategy.StdInstantiatorStrategy;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Use {@code RootThread} to execute arbitrary
 * {@link RootCallable} closures inside a privileged root process via Binder IPC,
 * using {@link android.os.ParcelFileDescriptor} pipes and Kryo serialisation.
 *
 * <h3>Threading model</h3>
 * All IPC work is dispatched onto a shared cached {@link ExecutorService}.
 * Callers receive a {@link Future} and may block or compose asynchronously.
 *
 * <h3>FD ownership contract</h3>
 * <ol>
 *   <li>Caller writes the callable into {@code callableWrite}; the stream is closed (EOF) by
 *       {@link ParcelFileDescriptor.AutoCloseOutputStream}.</li>
 *   <li>{@code callableRead} and {@code resultWrite} are handed to the service via
 *       {@link IRootThread#execute}; the service owns them from that point.</li>
 *   <li>Caller reads the result from {@code resultRead}; it is closed by
 *       {@link ParcelFileDescriptor.AutoCloseInputStream}.</li>
 *   <li>On any error before {@code execute()} the caller closes all four FDs.</li>
 * </ol>
 *
 * <h3>Lifecycle</h3>
 * Call {@link #bind(Context)} when the host component starts and {@link #unbind()} when it stops.
 * See {@link RootThreadLifecycleObserver} for the {@code LifecycleOwner}-aware helper.
 */
public final class RootThread {

    private static final String TAG = "RootThread";

    /**
     * Fulfilled when the root service connects; replaced with a new incomplete future
     * on disconnect so subsequent callers block until reconnect.
     */
    private static volatile CompletableFuture<IRootThread> sRootServiceFuture =
            new CompletableFuture<>();

    /**
     * Cached thread pool for IPC work.  Binder calls are blocking I/O — a cached pool
     * prevents head-of-line blocking between concurrent calls.
     */
    private static final ExecutorService sExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "RootThread-IPC");
        t.setDaemon(true);
        return t;
    });

    private static final ServiceConnection sConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.d(TAG, "Root service connected");
            sRootServiceFuture.complete(IRootThread.Stub.asInterface(binder));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "Root service disconnected — resetting future");
            sRootServiceFuture = new CompletableFuture<>();
        }
    };

    // Private constructor — static API only.
    private RootThread() {
    }

    /**
     * Binds to the {@link RootThreadService}.  Safe to call multiple times; the previous
     * binding is stopped first so the connection state is always consistent.
     *
     * @param context Application or Activity context used to resolve the service intent.
     */
    public static void bind(@NonNull Context context) {
        Intent intent =
                new Intent(context, RootThreadService.class);
        RootService.stop(intent);
        RootService.bind(intent, sConnection);
    }

    /**
     * Releases the binding established by {@link #bind(Context)}.
     */
    public static void unbind() {
        RootService.unbind(sConnection);
    }

    private static volatile ApkClassResolver appClassResolver;

    private static ClassResolver getAppClassResolver() {
        try {
            Context ctx = LazyContext.get();
            if (appClassResolver == null) {
                try {
                    android.content.pm.ApplicationInfo appInfo =
                            ctx.getPackageManager()
                                    .getApplicationInfo(ctx.getPackageName(), 0);

                    appClassResolver = new ApkClassResolver(
                            appInfo.sourceDir,
                            appInfo.splitSourceDirs,
                            appInfo.nativeLibraryDir,
                            ctx.getClassLoader()
                    );
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
            return appClassResolver;
        } catch (Exception e) {
            return new DefaultClassResolver();
        }
    }

    /**
     * Submits {@code callable} to the root process and returns a {@link Future} that
     * resolves with the callable's return value, or fails with an {@link IOException}
     * wrapping the remote exception.
     *
     * <p>This method returns immediately; callers must not call {@link Future#get()} on
     * the calling thread if it is the main thread without using an appropriate executor.
     *
     * @param callable The closure to execute in the root process.
     * @param <T>      Return type of the callable.
     * @return A {@link Future} representing the pending result.
     */
    @NonNull
    public static <T> Future<T> submit(@NonNull RootCallable<T> callable) {
        return sExecutor.submit(() -> executeSync(callable));
    }

    /**
     * Submits {@code callable} to the root process and blocks the calling thread until
     * the result is available.  Must NOT be called on the main thread.
     *
     * @param callable The closure to execute in the root process.
     * @param <T>      Return type of the callable.
     * @return The value returned by the callable.
     * @throws IOException          If IPC serialisation, transport, or the remote call fails.
     * @throws InterruptedException If the calling thread is interrupted while waiting.
     */
    @Nullable
    public static <T> T executeBlocking(@NonNull RootCallable<T> callable)
            throws IOException, InterruptedException {
        try {
            return submit(callable).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Root execution failed", cause);
        }
    }

    /**
     * Submits {@code callable} to the root process and blocks the calling thread for
     * at most {@code timeout} {@code unit}s.
     *
     * @param callable The closure to execute in the root process.
     * @param timeout  Maximum time to wait.
     * @param unit     Time unit for {@code timeout}.
     * @param <T>      Return type of the callable.
     * @return The value returned by the callable.
     * @throws IOException          If IPC serialisation, transport, or the remote call fails.
     * @throws InterruptedException If the calling thread is interrupted while waiting.
     * @throws TimeoutException     If the operation does not complete within the timeout.
     */
    @Nullable
    public static <T> T executeBlocking(
            @NonNull RootCallable<T> callable,
            long timeout,
            @NonNull TimeUnit unit
    ) throws IOException, InterruptedException, TimeoutException {
        try {
            return submit(callable).get(timeout, unit);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Root execution failed", cause);
        }
    }

    /**
     * Core synchronous IPC routine.  Serialises {@code callable} into a pipe, hands the
     * read-end to the root service, then reads and deserializes the result from a second
     * pipe.
     *
     * @throws IOException On any serialization, IPC, or type-mismatch error.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    static <T> T executeSync(@NonNull RootCallable<T> callable) throws IOException {
        IRootThread svc;
        try {
            svc = sRootServiceFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for root service", e);
        } catch (ExecutionException e) {
            throw new IOException("Root service connection failed", e.getCause());
        }

        ParcelFileDescriptor[] callablePipes = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] resultPipes = ParcelFileDescriptor.createPipe();

        ParcelFileDescriptor callableRead = callablePipes[0];
        ParcelFileDescriptor callableWrite = callablePipes[1];
        ParcelFileDescriptor resultRead = resultPipes[0];
        ParcelFileDescriptor resultWrite = resultPipes[1];

        try {
            try (ParcelFileDescriptor.AutoCloseOutputStream fos =
                         new ParcelFileDescriptor.AutoCloseOutputStream(callableWrite);
                 Output output = new Output(fos)) {
                KryoManager kryo = buildKryo(null);
                kryo.writeClassAndObject(output, callable);
                output.flush();
            }

            svc.execute(callableRead, resultWrite);

        } catch (Exception e) {
            closeQuietly(callableWrite);
            closeQuietly(callableRead);
            closeQuietly(resultWrite);
            closeQuietly(resultRead);
            throw new IOException("IPC write/execute failed", e);
        }

        try (ParcelFileDescriptor.AutoCloseInputStream fis =
                     new ParcelFileDescriptor.AutoCloseInputStream(resultRead);
             Input input = new Input(fis)) {

            KryoManager kryo = buildKryo(null);
            Object result = kryo.readClassAndObject(input);

            if (result instanceof Throwable) {
                throw new IOException("Remote exception", (Throwable) result);
            }

            return (T) result;
        }
    }

    /**
     * Builds and returns a fully configured {@link KryoManager} instance tailored for
     * cross-process communication.
     *
     * <p>The instance is initialized with a {@link DefaultClassResolver}, a
     * {@link MapReferenceResolver}, and a custom {@link ParcelableSerializer} to
     * ensure {@link Parcelable} objects are correctly handled across process boundaries.
     * It also utilizes {@link StdInstantiatorStrategy} to support classes without
     * default constructors.
     *
     * @param loader An optional {@link ClassLoader} to be used by Kryo for resolving classes;
     *               if {@code null}, the default class loader will be used.
     * @return A pre-configured {@link KryoManager} instance.
     */
    @NonNull
    public static KryoManager buildKryo(@Nullable ClassResolver classResolver, @Nullable ClassLoader loader) {
        return new KryoManager(Objects.requireNonNullElseGet(classResolver, RootThread::getAppClassResolver), loader);
    }

    /**
     * Builds and returns a fully configured {@link KryoManager} instance.
     *
     * <p>This method initializes Kryo with custom settings for cross-process IPC, including
     * a {@link StdInstantiatorStrategy} for classes without no-arg constructors and a
     * specialized serializer for {@link Parcelable} types.
     *
     * @param loader Optional {@link ClassLoader} used to resolve classes during deserialization;
     *               if {@code null}, the default class loader is used.
     * @return A pre-configured {@link KryoManager} ready for object serialization.
     */
    @NonNull
    public static KryoManager buildKryo(@Nullable ClassLoader loader) {
        return buildKryo(null, loader);
    }

    /**
     * Closes {@code pfd}, swallowing any exception.
     */
    static void closeQuietly(@Nullable ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Kryo instance pre-configured for cross-process use.
     *
     * <p>{@link Parcelable} objects are serialized via {@link Parcel} (not Kryo's own
     * mechanism) to avoid reference-ID divergence between the writer and reader process.
     */
    public static final class KryoManager extends Kryo {
        KryoManager(ClassResolver classResolver, @Nullable ClassLoader loader) {
            super(classResolver, new MapReferenceResolver());
            setRegistrationRequired(false);
            addDefaultSerializer(Parcelable.class, new ParcelableSerializer());
            setReferences(true);
            setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
            if (loader != null) {
                setClassLoader(loader);
            }
        }
    }

    /**
     * Kryo {@link Serializer} for {@link Parcelable} objects.
     *
     * <p>Using {@link Parcel} avoids the cross-process reference-ID divergence that occurs
     * when Kryo tries to track references across two processes with independently ordered
     * class registrations.  The marshaled bytes are opaque to Kryo, so its reference
     * tracker is never involved for the Parcelable payload.
     */
    static final class ParcelableSerializer extends Serializer<Parcelable> {

        ParcelableSerializer() {
            super(false, true);
        }

        @Override
        public void write(Kryo kryo, Output output, Parcelable obj) {
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeValue(obj);
                byte[] bytes = parcel.marshall();
                output.writeInt(bytes.length);
                output.writeBytes(bytes);
            } finally {
                parcel.recycle();
            }
        }

        @Override
        public Parcelable read(Kryo kryo, Input input, Class<? extends Parcelable> type) {
            int size = input.readInt();
            byte[] bytes = input.readBytes(size);
            Parcel parcel = Parcel.obtain();
            try {
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);
                // Use the classLoader registered on the Kryo instance so that custom class
                // resolvers (e.g. ApkClassResolver) can supply the correct ClassLoader.
                ClassLoader cl = kryo.getClassLoader() != null
                        ? kryo.getClassLoader()
                        : type.getClassLoader();
                return (Parcelable) parcel.readValue(cl);
            } finally {
                parcel.recycle();
            }
        }
    }

    public static final Companion Companion = new Companion();
    public static final class Companion {
        private Companion() {}
    }
}

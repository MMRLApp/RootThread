package dev.mmrlx.thread;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A typed, serializable container for custom arguments that are passed alongside a
 * {@link RootCallable} and survive the cross-process boundary.
 *
 * <p>All values must be serializable by Kryo (e.g. primitives, {@link String},
 * {@link java.io.Serializable} objects, or {@link android.os.Parcelable} types).
 *
 * <h3>Example</h3>
 * <pre>{@code
 * RootArgs args = RootArgs.of("path", "/data/local/tmp/test.txt")
 *                         .with("overwrite", true);
 *
 * RootThread.submit(options -> {
 *     String path = options.getArgs().get("path");
 *     boolean overwrite = options.getArgs().get("overwrite", false);
 *     // …
 *     return null;
 * }, args);
 * }</pre>
 */
public final class RootArgs implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Shared empty instance; avoids allocations when no args are needed. */
    public static final RootArgs EMPTY = new RootArgs(Collections.emptyMap());

    private final Map<String, Object> mArgs;

    private RootArgs(@NonNull Map<String, Object> args) {
        mArgs = args;
    }

    /**
     * Creates a new {@code RootArgs} with a single key-value pair.
     *
     * @param key   The argument name.
     * @param value The argument value; must be Kryo-serializable.
     */
    @NonNull
    public static RootArgs of(@NonNull String key, @Nullable Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return new RootArgs(map);
    }

    /**
     * Creates a new {@code RootArgs} from an existing map.
     *
     * @param args Key-value pairs; the map is copied defensively.
     */
    @NonNull
    public static RootArgs of(@NonNull Map<String, ?> args) {
        return new RootArgs(new HashMap<>(args));
    }

    /**
     * Returns a new {@code RootArgs} with the given key-value pair added (or replaced).
     */
    @NonNull
    public RootArgs with(@NonNull String key, @Nullable Object value) {
        Map<String, Object> copy = new HashMap<>(mArgs);
        copy.put(key, value);
        return new RootArgs(copy);
    }

    /**
     * Returns the value associated with {@code key}, or {@code null} if absent.
     *
     * @param key The argument name.
     * @param <T> The expected type.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(@NonNull String key) {
        return (T) mArgs.get(key);
    }

    /**
     * Returns the value associated with {@code key}, or {@code defaultValue} if absent.
     *
     * @param key          The argument name.
     * @param defaultValue Fallback if the key is not present.
     * @param <T>          The expected type.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(@NonNull String key, @NonNull T defaultValue) {
        Object value = mArgs.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * Returns {@code true} if an argument with the given key exists.
     */
    public boolean has(@NonNull String key) {
        return mArgs.containsKey(key);
    }

    /**
     * Returns an unmodifiable view of all argument keys.
     */
    @NonNull
    public Set<String> keys() {
        return Collections.unmodifiableSet(mArgs.keySet());
    }

    /**
     * Returns {@code true} if no arguments are stored.
     */
    public boolean isEmpty() {
        return mArgs.isEmpty();
    }

    @NonNull
    @Override
    public String toString() {
        return "RootArgs" + mArgs;
    }
}

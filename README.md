# RootThread

An Android library for executing arbitrary code in a privileged root process via Binder IPC, with first-class support for both **Java** (`Future`, `ExecutorService`) and **Kotlin** (coroutines, Flow, DSL).

---

## Table of Contents

- [RootThread](#rootthread)
  - [Table of Contents](#table-of-contents)
  - [How It Works](#how-it-works)
  - [Setup](#setup)
    - [JitPack](#jitpack)
    - [Dependencies](#dependencies)
    - [Lifecycle binding](#lifecycle-binding)
  - [KSP Code Generation](#ksp-code-generation)
    - [KSP Setup](#ksp-setup)
    - [`@RootFunction`](#rootfunction)
    - [Generated API](#generated-api)
  - [Core Concepts](#core-concepts)
    - [RootCallable](#rootcallable)
    - [RootConsumer](#rootconsumer)
    - [Serialisation rules](#serialisation-rules)
  - [API Reference — Kotlin](#api-reference--kotlin)
    - [`rootThread { }`](#rootthread--)
    - [`T.rootThread { }`](#trootthread--)
    - [`rootLaunch` / `rootAsync`](#rootlaunch--rootasync)
    - [`rootFlow { }`](#rootflow--)
    - [`rootBlocking { }`](#rootblocking--)
    - [`T.rootBlocking { }`](#trootblocking--)
    - [`rootBlocking` with timeout](#rootblocking-with-timeout)
    - [`rootBlock` DSL](#rootblock-dsl)
    - [`rootThreadCatching` / `rootBlockingCatching`](#rootthreadcatching--rootblockingcatching)
    - [`RootThread { }` invoke syntax](#rootthread---invoke-syntax)
    - [`Future.awaitRoot()`](#futureawaitroot)
  - [API Reference — Java](#api-reference--java)
    - [`RootThread.submit()`](#rootthreadsubmit)
    - [`RootThread.executeBlocking()`](#rootthreadexecuteblocking)
    - [`RootThread.executeBlocking()` with timeout](#rootthreadexecuteblocking-with-timeout)
    - [`RootThreadExtensions.rootLaunch()`](#rootthreadextensionsrootlaunch)
    - [`RootThreadExtensions.rootBlocking()`](#rootthreadextensionsrootblocking)
    - [`RootThreadExtensions.addRootThread()`](#rootthreadextensionsaddrootthread)
  - [Lifecycle](#lifecycle)
    - [`RootThreadLifecycleObserver`](#rootthreadlifecycleobserver)
    - [Manual bind / unbind](#manual-bind--unbind)
  - [Threading model](#threading-model)
  - [FD ownership contract](#fd-ownership-contract)
  - [Serialisation internals](#serialisation-internals)
  - [Error handling](#error-handling)
  - [Rules and gotchas](#rules-and-gotchas)

---

## How It Works

```
┌─────────────────────────────────┐        ┌──────────────────────────────────┐
│           App Process           │        │           Root Process           │
│                                 │        │                                  │
│  RootCallable ──► Kryo ──► pipe ├──────► │ pipe ──► Kryo ──► RootCallable   │
│                                 │  IPC   │              │                   │
│  result ◄── Kryo ◄── pipe      ◄┤        │         call()                   │
│                                 │        │              │                   │
│                                 │        │   result ──► Kryo ──► pipe ──►   │
└─────────────────────────────────┘        └──────────────────────────────────┘
```

1. The caller serializes a `RootCallable` via **Kryo** into a `ParcelFileDescriptor` write pipe.
2. The read-end of that pipe and the write-end of a result pipe are handed to `RootThreadService` over **Binder**.
3. The root service deserializes and executes the callable on a daemon thread.
4. The result is serialized back into the result pipe.
5. The caller reads the result pipe and resumes.

`Parcelable` objects are serialized using Android's own `Parcel` mechanism instead of Kryo to avoid cross-process reference-ID divergence.

---

## Setup

### JitPack

Add the JitPack repository to your settings file:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

### Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.MMRLApp.RootThread:thread:<version>")

    // KSP code generation (optional — see KSP Code Generation below)
    ksp("com.github.MMRLApp.RootThread:thread-ksp:<version>")
}
```

### Lifecycle binding

The simplest setup — attach the observer once in `onCreate`:

```kotlin
// Kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addRootThread(this) // binds onStart, unbinds onStop
    }
}
```

```java
// Java
public class MainActivity extends ComponentActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getLifecycle().addObserver(new RootThreadLifecycleObserver(this));
    }
}
```

---

## KSP Code Generation

The optional `thread-ksp` artifact provides a KSP processor that generates boilerplate-free `RootCallable` wrappers from annotated functions.

### KSP Setup

```kotlin
// build.gradle.kts
plugins {
    id("com.google.devtools.ksp") version "<ksp-version>"
}

dependencies {
    implementation("com.github.MMRLApp.RootThread:thread:<version>")
    ksp("com.github.MMRLApp.RootThread:thread-ksp:<version>")
}
```

### `@RootFunction`

Annotate any top-level function or companion object function that should run in the root process. If the function accepts a `RootOptions` parameter, it is automatically injected from `call(options)` at runtime and excluded from the constructor.

```kotlin
// Top-level
@RootFunction
fun loadModules(): List<Module> {
    return File("/data/adb/modules").listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { parseModule(it) }
        .orEmpty()
}

// With RootOptions (if you use RootOptions, always place it first)
@RootFunction
fun readFile(options: RootOptions, path: String): String {
    return File(path).readText()
}

// Inside a companion object
class ModulesRepository {
    companion object {
        @RootFunction
        fun loadModules(): List<Module> { ... }
    }
}
```

### Generated API

For each annotated function the processor generates a file under `dev.mmrlx.threading`:

```kotlin
// Generated: dev/mmrlx/threading/RootedLoadModules.kt
public class RootedLoadModules : RootCallable<List<Module>>, Serializable {
    override fun call(options: RootOptions): List<Module> = loadModules()
}

// Extension on RootScope — the public API surface
fun RootScope.loadModules(): RootCallable<List<Module>> = RootedLoadModules()
```

Usage:

```kotlin
// Suspend call via companion
val modules = RootedLoadModules().asThread()

// As a Flow via companion (if you compose)
val modules by RootedLoadModules().asFlow().collectAsState(emptyList())
```

---

## Core Concepts

### RootCallable

`RootCallable<T>` is a `@FunctionalInterface` (usable as a lambda in both Java and Kotlin) that represents work to execute in the root process.

```kotlin
val callable = RootCallable<String> {
    File("/proc/version").readText()
}
```

```java
RootCallable<String> callable = options -> new File("/proc/version").readText();
```

### RootConsumer

`RootConsumer<T, R>` is a receiver-scoped variant — it receives a typed object from the caller's process and returns a result. Used by the `rootBlocking` receiver extension and `RootThreadExtensions.rootBlocking`.

```kotlin
val consumer = RootConsumer<PackageManager, List<PackageInfo>> { pm ->
    pm.getInstalledPackages(0)
}
```

### Serialisation rules

Because callables are serialized **across a process boundary**, they must be Kryo-compatible:

| ✅ Safe to capture                          | ❌ Never capture                               |
|--------------------------------------------|-----------------------------------------------|
| Primitives (`Int`, `Boolean`, `String`, …) | `Context` / `Activity` / `Fragment`           |
| `Parcelable` objects                       | `View` or any UI object                       |
| Plain data classes                         | Non-serialisable lambdas or anonymous classes |
| Enums                                      | `ViewModel`, `LiveData`, `Flow`               |
| `Serializable` objects                     | Binder objects (other than via Parcel)        |

---

## API Reference — Kotlin

### `rootThread { }`

Suspends the coroutine, executes the block in the root process, and resumes with the result. Dispatches onto `Dispatchers.IO` automatically.

```kotlin
// In any suspend function:
val kernel = rootThread { File("/proc/version").readText() }
val hasSu  = rootThread { File("/system/bin/su").exists() }
```

**Signature:**
```kotlin
suspend fun <T> rootThread(block: RootCallable<T>): T
```

**Throws:** `IOException` on IPC or remote failure.

---

### `T.rootThread { }`

Receiver-scoped variant. Passes `this` into the root process as the first argument of the callable.

```kotlin
val packages = packageManager.rootThread { pm ->
    pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
}
```

**Signature:**
```kotlin
suspend fun <T, R> T.rootThread(block: RootConsumer<T, R>): R
```

**Throws:** `IOException` on IPC or remote failure.

---

### `rootLaunch` / `rootAsync`

Launch-style wrappers for use inside a `CoroutineScope`. Exceptions propagate through the scope's job like any other coroutine failure.

```kotlin
// Fire and forget
viewModelScope.rootLaunch {
    Runtime.getRuntime().exec("chmod 777 /data/local/tmp/file")
}

// With a result via Deferred
val deferred = viewModelScope.rootAsync { readRootDatabase() }
val rows = deferred.await()
```

**Signatures:**
```kotlin
fun CoroutineScope.rootLaunch(block: RootCallable<Unit>): Job
fun <T> CoroutineScope.rootAsync(block: RootCallable<T>): Deferred<T>
```

---

### `rootFlow { }`

Returns a cold `Flow<T>` that executes the callable on each collection and emits a single value.

```kotlin
rootFlow { File("/proc/version").readText() }
    .onEach { version -> textView.text = version }
    .launchIn(lifecycleScope)

// Combine with other operators
rootFlow { getPrivilegedData() }
    .map { it.transform() }
    .catch { e -> showError(e) }
    .flowOn(Dispatchers.IO)
    .collect { result -> updateUi(result) }
```

**Signature:**
```kotlin
fun <T> rootFlow(block: RootCallable<T>): Flow<T>
```

---

### `rootBlocking { }`

Executes the block in the root process, **blocking the calling thread**. Must not be called on the main thread.

```kotlin
// On a background thread / Worker / HandlerThread:
val exists = rootBlocking { File("/system/bin/su").exists() }
```

**Signature:**
```kotlin
@Throws(IOException::class, InterruptedException::class)
fun <T> rootBlocking(block: RootCallable<T>): T?
```

---

### `T.rootBlocking { }`

Receiver-scoped blocking variant.

```kotlin
val packages = packageManager.rootBlocking { pm ->
    pm.getInstalledPackages(0)
}
```

**Signature:**
```kotlin
@Throws(IOException::class, InterruptedException::class)
fun <T, R> T.rootBlocking(block: RootConsumer<T, R>): R?
```

---

### `rootBlocking` with timeout

Blocking execution with a deadline. Throws `TimeoutException` if the root process does not respond in time.

```kotlin
val result = rootBlocking(5, TimeUnit.SECONDS) { readHeavyRootFile() }
```

**Signature:**
```kotlin
@Throws(IOException::class, InterruptedException::class, TimeoutException::class)
fun <T> rootBlocking(timeout: Long, unit: TimeUnit, block: RootCallable<T>): T?
```

---

### `rootBlock` DSL

Groups multiple root calls into a structured block. Each `exec { }` call is an independent IPC round-trip but they share a readable sequential scope.

```kotlin
val data = rootBlock {
    val hasSu   = exec { File("/system/bin/su").exists() }
    val kernel  = exec { File("/proc/version").readText() }
    val modules = exec { File("/data/adb/modules").listFiles()?.size ?: 0 }

    mapOf(
        "hasSu"   to hasSu,
        "kernel"  to kernel,
        "modules" to modules,
    )
}
```

**Signatures:**
```kotlin
suspend fun <T> rootBlock(block: suspend RootBlockScope.() -> T): T

class RootBlockScope {
    suspend fun <T> exec(block: RootCallable<T>): T
}
```

---

### `rootThreadCatching` / `rootBlockingCatching`

Result-wrapped variants for railway-oriented error handling. Never throw — failures are delivered as `Result.failure`.

```kotlin
// Suspend
rootThreadCatching { riskyRootOperation() }
    .onSuccess { result -> updateUi(result) }
    .onFailure { error -> Log.e(TAG, "Root failed", error) }

// Blocking (off main thread)
val result = rootBlockingCatching { File("/proc/version").readText() }
if (result.isSuccess) {
    textView.text = result.getOrNull()
}
```

**Signatures:**
```kotlin
suspend fun <T> rootThreadCatching(block: RootCallable<T>): Result<T>
fun     <T> rootBlockingCatching(block: RootCallable<T>): Result<T>
```

---

### `RootThread { }` invoke syntax

Syntactic sugar allowing `RootThread` to be called like a function inside any suspend context.

```kotlin
// Equivalent to rootThread { ... }
val result = RootThread { doPrivilegedWork() }
```

---

### `Future.awaitRoot()`

Suspends a coroutine until a `Future<T>` (returned by `RootThread.submit()`) completes.
Implemented with `suspendCancellableCoroutine` — **no `kotlinx-coroutines-jdk8` dependency required**.

- Runs `Future.get()` on `Dispatchers.IO` so the main thread is never blocked.
- Cancels the `Future` if the coroutine is cancelled.
- Unwraps `ExecutionException` so callers see the real cause.

```kotlin
val future = RootThread.submit<String> { readPrivilegedFile() }

// Cancel if needed:
future.cancel(true)

// Or await in a coroutine:
val result = future.awaitRoot()
```

**Signature:**
```kotlin
suspend fun <T> Future<T>.awaitRoot(): T
```

---

## API Reference — Java

### `RootThread.submit()`

Submits a callable to the root process and returns a `Future<T>` immediately. The future resolves with the result or fails with an `IOException`.

```java
Future<Boolean> future = RootThread.submit(() ->
    new File("/system/bin/su").exists()
);

// Optional cancellation
future.cancel(true);

// Join elsewhere (not on main thread)
boolean result = future.get(5, TimeUnit.SECONDS);
```

**Signature:**
```java
public static <T> Future<T> submit(@NonNull RootCallable<T> callable)
```

---

### `RootThread.executeBlocking()`

Submits a callable and blocks the calling thread until the result is available. Must not be called on the main thread.

```java
executorService.execute(() -> {
    try {
        String kernel = RootThread.executeBlocking(
            () -> new String(Files.readAllBytes(Paths.get("/proc/version")))
        );
        runOnUiThread(() -> textView.setText(kernel));
    } catch (IOException | InterruptedException e) {
        Log.e(TAG, "Root IPC failed", e);
    }
});
```

**Signature:**
```java
public static <T> T executeBlocking(@NonNull RootCallable<T> callable)
    throws IOException, InterruptedException
```

---

### `RootThread.executeBlocking()` with timeout

```java
try {
    Boolean exists = RootThread.executeBlocking(
        () -> new File("/system/bin/su").exists(),
        5, TimeUnit.SECONDS
    );
} catch (TimeoutException e) {
    Log.e(TAG, "Root process timed out");
} catch (IOException | InterruptedException e) {
    Log.e(TAG, "IPC error", e);
}
```

**Signature:**
```java
public static <T> T executeBlocking(
    @NonNull RootCallable<T> callable,
    long timeout,
    @NonNull TimeUnit unit
) throws IOException, InterruptedException, TimeoutException
```

---

### `RootThreadExtensions.rootLaunch()`

Async fire-and-forget with an optional callback delivered on a specified `Executor` (or the main thread by default).

```java
// Callback on main thread (default)
RootThreadExtensions.rootLaunch(
    () -> readRootData(),
    new RootThreadExtensions.RootCallback<String>() {
        @Override public void onSuccess(String result) {
            textView.setText(result); // main thread
        }
        @Override public void onFailure(Throwable error) {
            Log.e(TAG, "Failed", error);
        }
    }
);

// Callback on a custom executor
Executor dbExecutor = Executors.newSingleThreadExecutor();
RootThreadExtensions.rootLaunch(
    () -> readRootDatabase(),
    new RootThreadExtensions.RootCallback<List<Row>>() {
        @Override public void onSuccess(List<Row> rows) {
            dao.insertAll(rows); // already on dbExecutor
        }
        @Override public void onFailure(Throwable e) { /* handle */ }
    },
    dbExecutor
);
```

**Signatures:**
```java
// Callback on main thread
public static <T> Future<T> rootLaunch(
    @NonNull RootCallable<T> callable,
    @Nullable RootCallback<T> callback
)

// Callback on custom executor
public static <T> Future<T> rootLaunch(
    @NonNull RootCallable<T> callable,
    @Nullable RootCallback<T> callback,
    @NonNull Executor executor
)

// Fire and forget, no callback
public static Future<Void> rootLaunch(@NonNull RootCallable<Void> callable)
```

---

### `RootThreadExtensions.rootBlocking()`

Receiver-scoped blocking execution. Equivalent to the Kotlin `T.rootBlocking { }` extension.

```java
PackageManager pm = getPackageManager();

executorService.execute(() -> {
    try {
        List<PackageInfo> packages = RootThreadExtensions.rootBlocking(
            pm,
            manager -> manager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        );
        runOnUiThread(() -> adapter.setData(packages));
    } catch (IOException | InterruptedException e) {
        Log.e(TAG, "Failed", e);
    }
});
```

**Signatures:**
```java
public static <T, R> R rootBlocking(
    @NonNull T receiver,
    @NonNull RootConsumer<T, R> block
) throws IOException, InterruptedException

public static <T, R> R rootBlocking(
    @NonNull T receiver,
    @NonNull RootConsumer<T, R> block,
    long timeout,
    @NonNull TimeUnit unit
) throws IOException, InterruptedException, TimeoutException
```

---

### `RootThreadExtensions.addRootThread()`

Lifecycle-aware bind/unbind as a static method (Java equivalent of the Kotlin extension).

```java
RootThreadExtensions.addRootThread(this, context);
```

---

## Lifecycle

### `RootThreadLifecycleObserver`

The preferred Java approach. Stores `applicationContext` internally to prevent leaks.

```java
// Activity
getLifecycle().addObserver(new RootThreadLifecycleObserver(this));

// Fragment
getViewLifecycleOwner().getLifecycle()
    .addObserver(new RootThreadLifecycleObserver(requireContext()));
```

```kotlin
// Kotlin extension — equivalent one-liner
addRootThread(requireContext())
```

### Manual bind / unbind

For cases where lifecycle integration is not appropriate (services, background components):

```java
RootThread.bind(context);  // call when ready
RootThread.unbind();       // call when done
```

```kotlin
RootThread.bind(context)
RootThread.unbind()
```

---

## Threading model

| Layer                  | Thread                                                                         |
|------------------------|--------------------------------------------------------------------------------|
| Caller (Kotlin)        | Any — dispatched to `Dispatchers.IO` internally                                |
| Caller (Java async)    | `RootThread` cached executor (`RootThread-IPC` threads)                        |
| Caller (Java blocking) | Caller's thread — must not be main thread                                      |
| Root service           | Binder thread (returns immediately); work on `RootThread-Worker` daemon thread |

The root service spawns a new named daemon thread per call so the Binder thread is never parked, eliminating ANR risk.

---

## FD ownership contract

```
createPipe() → [callableRead, callableWrite]
createPipe() → [resultRead,   resultWrite  ]

Caller:
  write callable → callableWrite → (AutoCloseOutputStream closes it, sends EOF)
  svc.execute(callableRead, resultWrite)   ← service owns these two from here
  read result  ← resultRead               ← caller owns this until done

On error before execute():
  caller closes all four FDs
```

---

## Serialisation internals

`KryoManager` is a pre-configured `Kryo` instance:

| Setting                 | Value                                                                                       |
|-------------------------|---------------------------------------------------------------------------------------------|
| Registration required   | `false` (class names are written to the stream)                                             |
| References              | `true` (handles cyclic graphs in non-Parcelable objects)                                    |
| Instantiation strategy  | `DefaultInstantiatorStrategy` + `StdInstantiatorStrategy` (no-arg constructor not required) |
| `Parcelable` serialiser | Custom `ParcelableSerializer` — uses `Parcel.marshall()` / `unmarshall()`                   |

A **fresh** `KryoManager` instance is used for each write and each read, keeping reference tables completely independent across the pipe boundary.

---

## Error handling

| Error scenario                | Behaviour                                                                                  |
|-------------------------------|--------------------------------------------------------------------------------------------|
| Remote callable throws        | Exception is serialised and re-thrown as `IOException("Remote exception", cause)`          |
| IPC write fails               | `IOException("IPC write/execute failed", cause)`                                           |
| Deserialisation fails in root | `IOException("Deserialisation failed in root process", cause)`                             |
| Root service disconnects      | `CompletableFuture` is replaced; next call blocks until reconnect                          |
| Coroutine cancelled           | `Future.cancel(true)` is called; `CancellationException` propagates normally               |
| `InterruptedException`        | Thread interrupt flag is restored; wrapped as `CancellationException` in coroutine context |

---

## Rules and gotchas

**Serialisation**
- `RootCallable` and `RootConsumer` lambdas must be Kryo-serializable. Do not capture `Context`, `View`, or any non-serializable object.
- Prefer capturing primitive values or `Parcelable` objects. For complex objects, pass them as the receiver via `T.rootThread { }` or `rootBlocking(receiver) { }`.

**Threading**
- Never call `executeBlocking` or `rootBlocking` on the **main thread** — they block the calling thread.
- Prefer `rootThread { }` (Kotlin suspend) or `rootLaunch` (Java async) in UI code.

**Lifecycle**
- Always use `RootThreadLifecycleObserver` or `addRootThread()` to ensure the service is unbound when the component stops. Failing to unbind leaks the root process connection.
- `RootThreadLifecycleObserver` stores `applicationContext` internally — passing an Activity context is safe.

**Cancellation**
- `rootLaunch` / `rootAsync` respect coroutine cancellation: the underlying `Future` is canceled and the root worker thread is interrupted.
- `rootFlow` is cold — collection starts a new IPC round-trip each time.
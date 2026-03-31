package dev.mmrlx.thread;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotate a top-level function with this to have the KSP processor generate
 * a named RootCallable wrapper class for it.
 * <p>
 * Example:
 * <pre>{@code
 * @RootFunction
 * fun loadModules(): List<Module> { ... }
 * }</pre>
 * </p>
 * Generates:
 * <pre>{@code
 * class RootedLoadModules implements RootCallable<List<Module>>, Serializable {
 *     @Override
 *     public List<Module> call() {
 *         return loadModules();
 *     }
 * }
 * }</pre>
 *
 * Use the generated class with rootFlow or RootThread.submit:
 * <pre>{@code
 * rootFlow(new RootedLoadModules());
 * }</pre>
 *
 * Alternative:
 * <pre>{@code
 * rootScope.loadModules().asFlow()
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RootFunction {
}
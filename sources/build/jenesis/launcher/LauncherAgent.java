package build.jenesis.launcher;

import module java.base;

import java.lang.instrument.Instrumentation;

/**
 * The launcher's own Java agent, used to obtain an {@link Instrumentation} for the agents bundled
 * inside an executable jar.
 *
 * <p>A bundled agent cannot be passed to the JVM as {@code -javaagent:foo.jar}: that switch resolves a
 * {@code Premain-Class} from the agent jar's <em>own</em> class path, whereas the application's agents
 * live in nested jars under {@code classpath/} and {@code modulepath/} that the JVM never sees. This
 * class stands in as the single agent the JVM does know about. Reference it from the executable jar's
 * manifest with any of:</p>
 *
 * <pre>
 *   Launcher-Agent-Class: build.jenesis.launcher.LauncherAgent   # java -jar foo.jar
 *   Premain-Class:        build.jenesis.launcher.LauncherAgent   # java -javaagent:foo.jar -jar foo.jar
 *   Agent-Class:          build.jenesis.launcher.LauncherAgent   # dynamic attach
 * </pre>
 *
 * <p>{@code Launcher-Agent-Class} is the one that matters for a plain {@code java -jar foo.jar}: when an
 * executable jar declares it, the JVM loads this class with the system class loader and calls
 * {@link #agentmain(String, Instrumentation)} <em>before</em> {@code main}, handing it a real
 * {@link Instrumentation}. This class only stashes that instance; {@link Launcher} reads it back through
 * {@link #instrumentation()} and passes it to each agent named by the {@code agentClass} property when it
 * bootstraps them in memory. Because both this class and {@link Launcher} are loaded by the system class
 * loader, they share the static field below.</p>
 *
 * <p>When the jar is run without any of these manifest attributes, no {@code Instrumentation} is ever
 * captured and {@link #instrumentation()} returns {@code null}; agents are then limited to a
 * {@code premain(String)} that needs no instrumentation.</p>
 */
public final class LauncherAgent {

    private static volatile Instrumentation instrumentation;

    private LauncherAgent() {
    }

    /** Captures the {@link Instrumentation} for a statically loaded agent ({@code -javaagent}). */
    public static void premain(String arguments, Instrumentation instrumentation) {
        LauncherAgent.instrumentation = instrumentation;
    }

    /** Captures the {@link Instrumentation} for {@code Launcher-Agent-Class} and dynamic attach. */
    public static void agentmain(String arguments, Instrumentation instrumentation) {
        LauncherAgent.instrumentation = instrumentation;
    }

    /**
     * The {@link Instrumentation} captured by the JVM, or {@code null} if the jar was not launched with
     * this class registered as its agent.
     */
    static Instrumentation instrumentation() {
        return instrumentation;
    }
}

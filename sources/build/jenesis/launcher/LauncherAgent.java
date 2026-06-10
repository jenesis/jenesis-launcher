package build.jenesis.launcher;

import module java.base;

import java.lang.instrument.Instrumentation;

/**
 * The launcher's own Java agent: the single agent the JVM knows about, standing in for the agents bundled
 * inside the jar.
 *
 * <p>A bundled agent cannot be passed to the JVM as {@code -javaagent:foo.jar} directly: that switch
 * resolves a {@code Premain-Class} from the agent jar's <em>own</em> class path, whereas the bundle's agents
 * live under {@code classpath/} and {@code modulepath/} subfolders that the JVM never sees. Reference this
 * class from the bundle's manifest instead:</p>
 *
 * <pre>
 *   Launcher-Agent-Class: build.jenesis.launcher.LauncherAgent   # java -jar foo.jar (an application bundle)
 *   Premain-Class:        build.jenesis.launcher.LauncherAgent   # java -javaagent:foo.jar ... (an agent bundle)
 *   Agent-Class:          build.jenesis.launcher.LauncherAgent   # dynamic attach
 * </pre>
 *
 * <p>Behaviour depends on whether the bundle declares a {@code mainClass}:</p>
 * <ul>
 *   <li><b>Application bundle</b> ({@code mainClass} present, run as {@code java -jar foo.jar} with
 *       {@code Launcher-Agent-Class}): the JVM calls {@link #agentmain} before {@code main}; this only
 *       stashes the {@link Instrumentation}, which {@link Launcher#run} reads back through
 *       {@link #instrumentation()} and hands to the bundled agents' {@code premain} before loading the main
 *       class.</li>
 *   <li><b>Agent bundle</b> (no {@code mainClass}, used as {@code -javaagent:foo.jar} on or attached to a
 *       host application): {@link #premain}/{@link #agentmain} enter {@link Launcher#runAgents}, which builds
 *       the bundle's isolated loader and runs the bundled agents' {@code premain}/{@code agentmain} against
 *       the host's {@link Instrumentation} - keeping the agent's dependencies off the host's class path.</li>
 * </ul>
 *
 * <p>This class and {@link Launcher} are both loaded by the system class loader, so they share the static
 * field below. Without any of these manifest attributes no {@code Instrumentation} is captured and
 * {@link #instrumentation()} returns {@code null}, limiting application-bundle agents to a
 * {@code premain(String)} that needs none.</p>
 */
public final class LauncherAgent {

    private static volatile Instrumentation instrumentation;

    private LauncherAgent() {
    }

    /** Entry for {@code -javaagent:foo.jar}: capture, then run an agent bundle's agents' {@code premain}. */
    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        start(false, arguments, instrumentation);
    }

    /** Entry for {@code Launcher-Agent-Class} and dynamic attach: capture, then for an agent bundle run
     * its agents' {@code agentmain}. */
    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        start(true, arguments, instrumentation);
    }

    private static void start(boolean attach, String arguments, Instrumentation instrumentation) throws Exception {
        LauncherAgent.instrumentation = instrumentation;
        // For an application bundle (a mainClass is present) runAgents returns immediately and Launcher.run
        // drives the agents; for an agent bundle it builds the loader and runs them here.
        Launcher.runAgents(Launcher.location(), attach, arguments, instrumentation);
    }

    /**
     * The {@link Instrumentation} captured by the JVM, or {@code null} if the jar was not launched with this
     * class registered as its agent.
     */
    static Instrumentation instrumentation() {
        return instrumentation;
    }
}

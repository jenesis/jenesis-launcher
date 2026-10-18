package build.jenesis.launcher.bridge;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs the bundled agents and the main method on behalf of the launcher, from a module of its own.
 *
 * <p>The launcher is the unnamed module of the system class loader whenever it runs from an executable jar,
 * and an unnamed module is open to every module: any code can obtain a full-privilege lookup in it. Whatever
 * the launcher were granted, anyone could therefore use. So the launcher grants nothing to itself. It defines
 * this class as the only class of a module in a layer of its own, which exports and opens nothing, and the
 * application's layer opens to that module alone the packages of the main class and of the agents. The
 * launcher reaches this class as the provider of a {@link Consumer} service, so its package need not be
 * exported either, and it keeps neither the layer nor the controller that granted the access.</p>
 *
 * <p>The class runs once: a second call is refused, so a reference to it that escapes - a class the main
 * method's stack walk returns, say - cannot be used to call into the opened packages again.</p>
 *
 * <p>The request is an array of the class loader, the agent method's name, the agents' class names and their
 * arguments, the {@link Instrumentation} or {@code null}, and the main class's name and its arguments, or
 * {@code null} for an agent bundle, because the launcher cannot name a type of this module. What the agents
 * or the main method throw is thrown as it was, checked or not.</p>
 */
public final class Bridge implements Consumer<Object[]> {

    private static final AtomicBoolean USED = new AtomicBoolean();

    @Override
    public void accept(Object[] request) {
        if (!USED.compareAndSet(false, true)) {
            throw new IllegalStateException("The launcher's bridge has run already and runs only once");
        }
        ClassLoader loader = (ClassLoader) request[0];
        String method = (String) request[1];
        String[] agents = (String[]) request[2], arguments = (String[]) request[3];
        Instrumentation instrumentation = (Instrumentation) request[4];
        String mainClass = (String) request[5];
        try {
            for (int index = 0; index < agents.length; index++) {
                agent(loader.loadClass(agents[index]), method, arguments[index], instrumentation);
            }
            if (mainClass != null) {
                main(loader.loadClass(mainClass), (String[]) request[6]);
            }
        } catch (Throwable thrown) {
            throw Bridge.<RuntimeException>rethrow(thrown);
        }
    }

    /**
     * Mirrors the JVM's own agent start-up: prefers {@code <method>(String, Instrumentation)} and falls back
     * to {@code <method>(String)}; the two-argument form is only usable when an {@code Instrumentation} is
     * available.
     */
    private static void agent(Class<?> agent, String method, String arguments, Instrumentation instrumentation)
            throws Throwable {
        Bridge.class.getModule().addReads(agent.getModule());
        Method entry = instrumentation == null ? null : declared(agent, method, String.class, Instrumentation.class);
        Object[] parameters = entry == null ? new Object[] {arguments} : new Object[] {arguments, instrumentation};
        if (entry == null) {
            entry = declared(agent, method, String.class);
        }
        if (entry == null) {
            throw new IllegalStateException("Agent class " + agent.getName() + " declares no static "
                    + method + "(String) or " + method + "(String, Instrumentation) method"
                    + (instrumentation == null
                            ? "; without instrumentation only " + method + "(String) can run - declare"
                              + " 'Launcher-Agent-Class: build.jenesis.launcher.LauncherAgent' in the manifest"
                              + " to capture it"
                            : ""));
        }
        entry.setAccessible(true);
        try {
            entry.invoke(null, parameters);
        } catch (InvocationTargetException thrownByAgent) {
            throw thrownByAgent.getCause();
        }
    }

    private static Method declared(Class<?> agent, String name, Class<?>... parameterTypes) {
        try {
            Method method = agent.getDeclaredMethod(name, parameterTypes);
            return Modifier.isStatic(method.getModifiers()) ? method : null;
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    /**
     * Invokes the main method of {@code type} as {@code java} does since JEP 512: a {@code main(String[])},
     * else a {@code main()}, static or not, declared or inherited, and not private; an instance main runs on
     * an instance made by the class's own non-private constructor without parameters. The JVM resolves the
     * method, as it resolves a call, so what the class inherits - a superclass's method, an interface's default
     * method - is found as the launcher's own finder finds it.
     */
    private static void main(Class<?> type, String[] arguments) throws Throwable {
        Bridge.class.getModule().addReads(type.getModule());
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException closed) {
            throw new IllegalStateException("Cannot access the main method of " + type.getName()
                    + ", which is neither on the class path nor in a module of the bundle", closed);
        }
        for (MethodType signature : List.of(MethodType.methodType(void.class, String[].class),
                MethodType.methodType(void.class))) {
            MethodHandle main;
            try {
                main = lookup.findStatic(type, "main", signature);
            } catch (NoSuchMethodException | IllegalAccessException noStaticMain) {
                try {
                    main = lookup.findVirtual(type, "main", signature);
                } catch (NoSuchMethodException | IllegalAccessException noInstanceMain) {
                    continue;
                }
            }
            int modifiers = lookup.revealDirect(main).getModifiers();
            if (Modifier.isPrivate(modifiers)) {
                continue;
            } else if (!Modifier.isStatic(modifiers)) {
                if (Modifier.isAbstract(type.getModifiers())) {
                    throw new IllegalStateException("The main class " + type.getName() + " is abstract or an"
                            + " interface but its main method is not static: make the class concrete, or the"
                            + " method static");
                }
                MethodHandle constructor;
                try {
                    constructor = lookup.findConstructor(type, MethodType.methodType(void.class));
                } catch (NoSuchMethodException absent) {
                    constructor = null;
                }
                if (constructor == null || Modifier.isPrivate(lookup.revealDirect(constructor).getModifiers())) {
                    throw new IllegalStateException("The main class " + type.getName() + " has an instance main"
                            + " method but no non-private constructor without parameters: declare one, declare"
                            + " the class static if it is an inner class, or make the main method static");
                }
                main = main.bindTo(constructor.invoke());
            }
            if (main.type().parameterCount() == 0) {
                main.invoke();
            } else {
                main.invoke(arguments);
            }
            return;
        }
        throw new IllegalStateException("The main class " + type.getName() + " declares or inherits no main"
                + " method: declare a non-private void main(String[] args) or void main(), static or not");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T rethrow(Throwable thrown) throws T {
        throw (T) thrown;
    }
}

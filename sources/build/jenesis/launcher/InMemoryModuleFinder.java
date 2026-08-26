package build.jenesis.launcher;

import module java.base;

/**
 * A {@link ModuleFinder} over the modular dependencies, resolving each exploded module on demand.
 *
 * <p>For a module that carries a {@code module-info.class} the descriptor is read straight from the
 * compiled descriptor. For an automatic module (one with no {@code module-info.class}) the name is taken
 * from the {@code Automatic-Module-Name} manifest header when present, otherwise derived from the original
 * jar file name with the same algorithm the JDK's {@code ModulePath} uses; its packages and
 * {@code META-INF/services} providers are scanned out of the entry names so {@link java.util.ServiceLoader}
 * keeps working. Bytes are read from the {@link Archive.Jar} lazily.</p>
 *
 * <p>A bundled module can declare {@code Jenesis-Aliases} in its manifest, the header a Jenesis build writes
 * for every {@code @jenesis.alias} of the module it compiles. Each entry maps a module name onto the Maven
 * coordinate of a dependency that carries no module identity of its own, and names the module the author
 * wrote a {@code requires} for. A build renames the jar so that the name derives from the file name, but a
 * bundle that kept the resolved file name would derive an unusable name (or none at all) from an encoded
 * coordinate, so the header is honoured here as well.</p>
 */
final class InMemoryModuleFinder implements ModuleFinder {

    private static final String SERVICES = "META-INF/services/";
    private static final String MANIFEST = "META-INF/MANIFEST.MF";
    private static final String ALIASES = "Jenesis-Aliases";
    private static final String AUTOMATIC_MODULE_NAME = "Automatic-Module-Name";
    private static final Pattern DASH_VERSION = Pattern.compile("-(\\d+(\\.|$))");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]");
    private static final Pattern REPEATING_DOTS = Pattern.compile("\\.{2,}");

    private final Map<String, ModuleReference> references = new LinkedHashMap<>();

    InMemoryModuleFinder(List<Archive.Jar> jars) {
        Map<String, String> aliases = aliases(jars);
        for (Archive.Jar jar : jars) {
            ModuleReference reference = reference(jar, aliases.get(jar.name()));
            String name = reference.descriptor().name();
            // A real module path rejects two modules of the same name; fail rather than silently dropping one
            // (which would shadow a dependency and load the wrong code).
            if (references.putIfAbsent(name, reference) != null) {
                throw new IllegalStateException("Two bundled modules resolve to the same name: " + name);
            }
        }
    }

    @Override
    public Optional<ModuleReference> find(String name) {
        return Optional.ofNullable(references.get(name));
    }

    @Override
    public Set<ModuleReference> findAll() {
        return new LinkedHashSet<>(references.values());
    }

    Set<String> moduleNames() {
        return new LinkedHashSet<>(references.keySet());
    }

    /**
     * Resolves the {@code Jenesis-Aliases} headers of all bundled modules into a mapping of jar file name onto
     * the module name the jar is to be found under. Only a jar without any identity of its own is considered:
     * an alias exists precisely because its target declares neither a {@code module-info.class} nor an
     * {@code Automatic-Module-Name}, and a jar that names itself is never aliased by a build. A declaration
     * whose target is not among those jars is ignored - the build already renamed it, or the target is on the
     * class path - whereas a declaration that would give one jar two names is an error, as it can only be
     * found under one.
     */
    private static Map<String, String> aliases(List<Archive.Jar> jars) {
        Map<String, String> declarations = new LinkedHashMap<>(), candidates = new LinkedHashMap<>();
        for (Archive.Jar jar : jars) {
            if (jar.open("module-info.class") == null && header(jar, AUTOMATIC_MODULE_NAME) == null) {
                String coordinate = coordinate(jar.name());
                if (coordinate != null) {
                    candidates.putIfAbsent(coordinate, jar.name());
                }
            }
            String declaration = header(jar, ALIASES);
            if (declaration == null || declaration.isBlank()) {
                continue;
            }
            for (String entry : declaration.split(",")) {
                String pair = entry.trim();
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String alias = equals < 0 ? "" : pair.substring(0, equals).trim();
                String target = equals < 0 ? "" : pair.substring(equals + 1).trim();
                if (alias.isEmpty() || target.isEmpty()) {
                    throw new IllegalStateException("Malformed " + ALIASES + " entry '"
                            + pair
                            + "' in "
                            + jar.name());
                }
                String previous = declarations.putIfAbsent(alias, target);
                if (previous != null && !previous.equals(target)) {
                    throw new IllegalStateException("Module alias " + alias
                            + " is declared for "
                            + previous
                            + " and for "
                            + target);
                }
            }
        }
        Map<String, String> aliases = new LinkedHashMap<>(), owners = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : declarations.entrySet()) {
            String name = candidates.get(entry.getValue());
            if (name == null) {
                continue;
            }
            String previous = owners.putIfAbsent(name, entry.getKey());
            if (previous != null) {
                throw new IllegalStateException(name + " is aliased as both "
                        + previous
                        + " and "
                        + entry.getKey()
                        + " - a jar can carry only one module name");
            }
            aliases.put(name, entry.getKey());
        }
        return aliases;
    }

    /**
     * The Maven coordinate a resolved jar file name encodes, without its trailing version segment, or
     * {@code null} for a file name that does not encode one.
     */
    private static String coordinate(String name) {
        String decoded;
        try {
            decoded = URLDecoder.decode(name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name,
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException _) {
            return null;
        }
        int slash = decoded.lastIndexOf('/');
        return slash < 1 ? null : decoded.substring(0, slash);
    }

    private static ModuleReference reference(Archive.Jar jar, String alias) {
        Set<String> packages = packages(jar.names());
        byte[] moduleInfo = jar.open("module-info.class");
        ModuleDescriptor descriptor = moduleInfo != null
                ? ModuleDescriptor.read(ByteBuffer.wrap(moduleInfo), () -> packages)
                : automatic(jar, packages, alias);
        // The module's location is its exploded folder URL, so a class the loader defines from it carries a
        // CodeSource pointing there - as a real module-path class does. Resources are still served through
        // the reader's own jar:/file: URLs, not this location.
        URI location;
        try {
            location = jar.url().toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot derive a module location for " + jar.name(), e);
        }
        return new ModuleReference(descriptor, location) {
            @Override
            public ModuleReader open() {
                return new ArchiveModuleReader(jar);
            }
        };
    }

    private static ModuleDescriptor automatic(Archive.Jar jar, Set<String> packages, String alias) {
        ModuleDescriptor.Builder builder = ModuleDescriptor.newAutomaticModule(
                alias == null ? automaticName(jar) : alias);
        String version = automaticVersion(jar);
        if (version != null) {
            builder.version(version);
        }
        if (!packages.isEmpty()) {
            builder.packages(packages);
        }
        for (String name : jar.names()) {
            if (name.startsWith(SERVICES) && name.indexOf('/', SERVICES.length()) == -1) {
                String service = name.substring(SERVICES.length());
                List<String> providers = providers(jar.open(name));
                if (!service.isEmpty() && !providers.isEmpty()) {
                    try {
                        builder.provides(service, providers);
                    } catch (IllegalArgumentException _) {
                        // Provider class outside the module's packages; skip as the JDK would.
                    }
                }
            }
        }
        return builder.build();
    }

    private static String header(Archive.Jar jar, String name) {
        byte[] manifestBytes = jar.open(MANIFEST);
        if (manifestBytes == null) {
            return null;
        }
        try {
            return new Manifest(new ByteArrayInputStream(manifestBytes)).getMainAttributes().getValue(name);
        } catch (IOException _) {
            return null;
        }
    }

    private static String automaticName(Archive.Jar jar) {
        String declared = header(jar, AUTOMATIC_MODULE_NAME);
        if (declared != null && !declared.isBlank()) {
            return declared.trim();
        }
        String name = jar.name();
        name = name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
        Matcher version = DASH_VERSION.matcher(name);
        if (version.find()) {
            name = name.substring(0, version.start());
        }
        name = REPEATING_DOTS.matcher(NON_ALPHANUMERIC.matcher(name).replaceAll(".")).replaceAll(".");
        if (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    /**
     * The version a module path derives for this dependency, which is the tail of its name behind the first
     * dash followed by digits, and only when it parses as a {@link ModuleDescriptor.Version} - as
     * {@code ModulePath.deriveModuleDescriptor} does, so a bundled automatic module reports the identity it
     * would report on a real module path, in {@code Module::getDescriptor} and in stack traces alike. A name
     * declared by {@code Automatic-Module-Name} does not suppress it: the manifest names the module, the file
     * still versions it.
     */
    private static String automaticVersion(Archive.Jar jar) {
        String name = jar.name();
        name = name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
        Matcher version = DASH_VERSION.matcher(name);
        if (!version.find()) {
            return null;
        }
        String tail = name.substring(version.start() + 1);
        try {
            ModuleDescriptor.Version.parse(tail);
        } catch (IllegalArgumentException _) {
            return null;
        }
        return tail;
    }

    private static Set<String> packages(List<String> names) {
        Set<String> packages = new HashSet<>();
        for (String name : names) {
            if (!name.endsWith(".class") || name.equals("module-info.class") || name.startsWith("META-INF/")) {
                continue;
            }
            int slash = name.lastIndexOf('/');
            if (slash <= 0) {
                continue;
            }
            String packageName = name.substring(0, slash).replace('/', '.');
            if (isValidPackage(packageName)) {
                packages.add(packageName);
            }
        }
        return packages;
    }

    private static boolean isValidPackage(String name) {
        int start = 0;
        for (int index = 0; index <= name.length(); index++) {
            if (index == name.length() || name.charAt(index) == '.') {
                if (index == start) {
                    return false;
                }
                if (!Character.isJavaIdentifierStart(name.charAt(start))) {
                    return false;
                }
                for (int inner = start + 1; inner < index; inner++) {
                    if (!Character.isJavaIdentifierPart(name.charAt(inner))) {
                        return false;
                    }
                }
                start = index + 1;
            }
        }
        return true;
    }

    private static List<String> providers(byte[] data) {
        List<String> providers = new ArrayList<>();
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
            int comment = line.indexOf('#');
            if (comment != -1) {
                line = line.substring(0, comment);
            }
            line = line.trim();
            if (!line.isEmpty()) {
                providers.add(line);
            }
        }
        return providers;
    }
}

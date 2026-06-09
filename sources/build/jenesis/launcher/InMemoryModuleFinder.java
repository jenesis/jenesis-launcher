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
 */
final class InMemoryModuleFinder implements ModuleFinder {

    private static final String SERVICES = "META-INF/services/";
    private static final Pattern DASH_VERSION = Pattern.compile("-(\\d+(\\.|$))");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]");
    private static final Pattern REPEATING_DOTS = Pattern.compile("\\.{2,}");

    private final Map<String, ModuleReference> references = new LinkedHashMap<>();

    InMemoryModuleFinder(List<Archive.Jar> jars) {
        for (Archive.Jar jar : jars) {
            ModuleReference reference = reference(jar);
            references.putIfAbsent(reference.descriptor().name(), reference);
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

    private static ModuleReference reference(Archive.Jar jar) {
        Set<String> packages = packages(jar.names());
        byte[] moduleInfo = jar.open("module-info.class");
        ModuleDescriptor descriptor = moduleInfo != null
                ? ModuleDescriptor.read(ByteBuffer.wrap(moduleInfo), () -> packages)
                : automatic(jar, packages);
        // The location is metadata only (resources are served through the reader's jar:/file: URLs), so an
        // absent location is fine.
        return new ModuleReference(descriptor, null) {
            @Override
            public ModuleReader open() {
                return new ArchiveModuleReader(jar);
            }
        };
    }

    private static ModuleDescriptor automatic(Archive.Jar jar, Set<String> packages) {
        ModuleDescriptor.Builder builder = ModuleDescriptor.newAutomaticModule(automaticName(jar));
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

    private static String automaticName(Archive.Jar jar) {
        byte[] manifestBytes = jar.open("META-INF/MANIFEST.MF");
        if (manifestBytes != null) {
            try {
                String declared = new Manifest(new ByteArrayInputStream(manifestBytes))
                        .getMainAttributes()
                        .getValue("Automatic-Module-Name");
                if (declared != null && !declared.isBlank()) {
                    return declared.trim();
                }
            } catch (IOException _) {
                // Fall through to file-name derivation.
            }
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

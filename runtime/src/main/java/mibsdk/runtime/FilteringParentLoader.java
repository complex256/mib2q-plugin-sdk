package mibsdk.runtime;

/**
 * Parent of every plugin class loader. Only class names under the allowed prefixes are visible;
 * everything else (stock Audi classes, the runtime's own internals) is a {@link ClassNotFoundException}.
 */
public final class FilteringParentLoader extends ClassLoader {

    private final ClassLoader delegate;
    private final String[] allowedPrefixes;

    /**
     * @param delegate where allowed names are resolved; {@code null} means the bootstrap loader
     *                 (J9 may represent the system/bootstrap loader as null)
     */
    public FilteringParentLoader(ClassLoader delegate, String[] allowedPrefixes) {
        super(null);
        this.delegate = delegate;
        this.allowedPrefixes = (String[]) allowedPrefixes.clone();
    }

    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!allowed(name)) {
            throw new ClassNotFoundException(name);
        }
        if (delegate == null) {
            return Class.forName(name, false, null);
        }
        return delegate.loadClass(name);
    }

    private boolean allowed(String name) {
        for (int i = 0; i < allowedPrefixes.length; i++) {
            if (name.startsWith(allowedPrefixes[i])) {
                return true;
            }
        }
        return false;
    }
}

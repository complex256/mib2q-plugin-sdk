package mibsdk.runtime;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

/** Creates the class loader for one plugin jar snapshot. */
public final class PluginClassLoaders {

    private PluginClassLoaders() {
    }

    /** {@code File.toURL()} is the only File-to-URL conversion on the car ({@code toURI()} does not exist there). */
    @SuppressWarnings("deprecation")
    public static ClassLoader create(File jarCopy, ClassLoader filteringParent) throws IOException {
        return new URLClassLoader(new URL[] {jarCopy.toURL()}, filteringParent);
    }
}

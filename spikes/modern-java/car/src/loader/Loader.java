package loader;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
// Spike: load a plugin jar in its own class loader, run it, drop it, load it again (simulated hot reload).
public class Loader {
    public static void main(String[] a) throws Exception {
        URL u = new File(a[0]).toURL();
        for (int i = 1; i <= 2; i++) {
            URLClassLoader cl = new URLClassLoader(new URL[] { u }, Loader.class.getClassLoader());
            Class c = cl.loadClass(a[1]);
            System.out.println("LOAD#" + i + " loader=" + c.getClassLoader() + " hash=" + System.identityHashCode(c));
            Method m = c.getMethod("main", new Class[] { String[].class });
            m.invoke(null, new Object[] { new String[0] });
        }
        System.out.println("LOADER-OK");
    }
}

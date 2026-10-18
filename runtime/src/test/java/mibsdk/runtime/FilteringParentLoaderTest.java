package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FilteringParentLoaderTest {

    private static FilteringParentLoader loader() {
        return new FilteringParentLoader(FilteringParentLoaderTest.class.getClassLoader(),
                RuntimeConstants.PLUGIN_PARENT_PREFIXES);
    }

    @Test
    void blocksStockPackages() throws Exception {
        FilteringParentLoader l = loader();
        assertThrows(ClassNotFoundException.class, () -> l.loadClass("de.audi.Foo"));
        assertThrows(ClassNotFoundException.class, () -> l.loadClass("mibsdk.runtime.SdkRuntime"));
        assertThrows(ClassNotFoundException.class, () -> l.loadClass("mibsdk.runtime.PluginJarCache"));
        assertNotNull(l.loadClass("mibsdk.api.MibPlugin"));
        assertNotNull(l.loadClass("java.util.List"));
    }

    @Test
    void nullDelegateFallsBackToBootstrap() throws Exception {
        FilteringParentLoader l = new FilteringParentLoader(null, RuntimeConstants.PLUGIN_PARENT_PREFIXES);
        assertNotNull(l.loadClass("java.util.List"));
        assertThrows(ClassNotFoundException.class, () -> l.loadClass("mibsdk.api.MibPlugin"));
    }

    @Test
    void prefixesMatchGlobalConstraints() {
        assertArrayEquals(new String[] {
            "java.", "javax.", "mibsdk.api.", "mibsdk.compat.", "net.sf.retrotranslator.runtime."
        }, RuntimeConstants.PLUGIN_PARENT_PREFIXES);
    }
}

package mibsdk.compat.java.lang;

/**
 * {@code Class.getGenericInterfaces()} (Java 5), missing from the car's JCL. Retrotranslator's own version
 * needs backport-util-concurrent, which is not on the car; Kotlin reaches it from every lambda's toString.
 * The car has no generic signatures to read, so this returns the raw interfaces: exactly what the JDK
 * returns for interfaces that are not parameterized.
 */
public final class _Class {
    private _Class() {}

    public static Object[] getGenericInterfaces(Class c) {
        return c.getInterfaces();
    }
}

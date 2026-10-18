package mibsdk.compat.java.lang;

/**
 * {@code Thread.getStackTrace()} (Java 5), missing from the car's JCL. Retrotranslator's own version needs
 * backport-util-concurrent, which is not on the car. CDC cannot read another thread's stack: for any thread
 * but the caller's own this returns an empty trace, which the JDK also allows.
 */
public final class _Thread {
    private _Thread() {}

    public static StackTraceElement[] getStackTrace(Thread thread) {
        if (thread != Thread.currentThread()) {
            return new StackTraceElement[0];
        }
        return new Throwable().getStackTrace();
    }
}

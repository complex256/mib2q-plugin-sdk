package mibsdk.compat.java.util;
public final class Objects {
    private Objects() {}
    public static boolean equals(Object a, Object b) { return a == b || (a != null && a.equals(b)); }
    public static int hashCode(Object o) { return o == null ? 0 : o.hashCode(); }
    public static String toString(Object o) { return String.valueOf(o); }
    public static Object requireNonNull(Object o) { if (o == null) throw new NullPointerException(); return o; }
}

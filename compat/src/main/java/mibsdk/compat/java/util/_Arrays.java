package mibsdk.compat.java.util;

import java.lang.reflect.Array;

/**
 * {@code java.util.Arrays.copyOf}/{@code copyOfRange} (Java 6), missing from the car's JCL. Without this shim
 * Retrotranslator maps them to backport-util-concurrent, which is not on the car. Same semantics as the JDK:
 * the copy keeps the original's component type (or {@code newType}), is padded with zero/null, and a bad
 * range throws.
 */
public final class _Arrays {
    private _Arrays() {}

    public static Object[] copyOf(Object[] a, int n) {
        return copyOf(a, n, a.getClass());
    }

    public static Object[] copyOf(Object[] a, int n, Class newType) {
        return (Object[]) copy(a, a.length, 0, Array.newInstance(newType.getComponentType(), n), n);
    }

    public static Object[] copyOfRange(Object[] a, int from, int to) {
        return copyOfRange(a, from, to, a.getClass());
    }

    public static Object[] copyOfRange(Object[] a, int from, int to, Class newType) {
        int n = rangeLength(a.length, from, to);
        return (Object[]) copy(a, a.length, from, Array.newInstance(newType.getComponentType(), n), n);
    }

    public static boolean[] copyOf(boolean[] a, int n) {
        return (boolean[]) copy(a, a.length, 0, new boolean[n], n);
    }

    public static boolean[] copyOfRange(boolean[] a, int from, int to) {
        int n = rangeLength(a.length, from, to);
        return (boolean[]) copy(a, a.length, from, new boolean[n], n);
    }

    public static byte[] copyOf(byte[] a, int n) {
        return (byte[]) copy(a, a.length, 0, new byte[n], n);
    }

    public static byte[] copyOfRange(byte[] a, int from, int to) {
        int n = rangeLength(a.length, from, to);
        return (byte[]) copy(a, a.length, from, new byte[n], n);
    }

    public static char[] copyOf(char[] a, int n) {
        return (char[]) copy(a, a.length, 0, new char[n], n);
    }

    public static char[] copyOfRange(char[] a, int from, int to) {
        int n = rangeLength(a.length, from, to);
        return (char[]) copy(a, a.length, from, new char[n], n);
    }

    public static short[] copyOf(short[] a, int n) {
        return (short[]) copy(a, a.length, 0, new short[n], n);
    }

    public static short[] copyOfRange(short[] a, int from, int to) {
        int n = rangeLength(a.length, from, to);
        return (short[]) copy(a, a.length, from, new short[n], n);
    }

    public static int[] copyOf(int[] a, int n) {
        return (int[]) copy(a, a.length, 0, new int[n], n);
    }

    public static int[] copyOfRange(int[] a, int from, int to) {
        int n = rangeLength(a.length, from, to);
        return (int[]) copy(a, a.length, from, new int[n], n);
    }

    public static long[] copyOf(long[] a, int n) {
        return (long[]) copy(a, a.length, 0, new long[n], n);
    }

    public static long[] copyOfRange(long[] a, int from, int to) {
        int n = rangeLength(a.length, from, to);
        return (long[]) copy(a, a.length, from, new long[n], n);
    }

    public static float[] copyOf(float[] a, int n) {
        return (float[]) copy(a, a.length, 0, new float[n], n);
    }

    public static float[] copyOfRange(float[] a, int from, int to) {
        int n = rangeLength(a.length, from, to);
        return (float[]) copy(a, a.length, from, new float[n], n);
    }

    public static double[] copyOf(double[] a, int n) {
        return (double[]) copy(a, a.length, 0, new double[n], n);
    }

    public static double[] copyOfRange(double[] a, int from, int to) {
        int n = rangeLength(a.length, from, to);
        return (double[]) copy(a, a.length, from, new double[n], n);
    }

    /** Copies {@code src[from..]} into the fresh {@code dst} of length {@code n}; returns {@code dst}. */
    private static Object copy(Object src, int srcLength, int from, Object dst, int n) {
        System.arraycopy(src, from, dst, 0, Math.min(srcLength - from, n));
        return dst;
    }

    private static int rangeLength(int length, int from, int to) {
        if (from < 0 || from > length) {
            throw new ArrayIndexOutOfBoundsException(from);
        }
        if (from > to) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        return to - from;
    }
}

package spike;

import java.util.*;
import java.util.function.*;

// Throwaway spike: exercises modern-Java syntax that must survive backporting to J9 2.3 / Foundation 1.1.
public class Hello {

    enum Gear { P, R, N, D }

    interface Plugin {
        String name();
        default String describe() { return "plugin:" + name(); }
    }

    static int passed = 0, failed = 0;

    static void check(String label, Supplier<Object> body, Object expected) {
        try {
            Object got = body.get();
            if (Objects.equals(got, expected)) { passed++; System.out.println("PASS " + label); }
            else { failed++; System.out.println("FAIL " + label + " got=" + got + " want=" + expected); }
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + label + " threw " + t);
        }
    }

    static int sum(int... xs) { int s = 0; for (int x : xs) s += x; return s; }

    static class Res implements AutoCloseable {
        static int closed = 0;
        public void close() { closed++; }
    }

    public static void main(String[] args) {
        System.out.println("spike.Hello on " + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version") + " / " + System.getProperty("microedition.profiles"));

        check("lambda+generics", () -> {
            List<Integer> xs = new ArrayList<>();
            for (int i = 1; i <= 4; i++) xs.add(i);              // autoboxing
            Function<Integer, Integer> sq = x -> x * x;
            int total = 0;
            for (Integer x : xs) total += sq.apply(x);
            return total;
        }, 30);

        check("method ref + comparator", () -> {
            List<String> names = new ArrayList<>(Arrays.asList("tuner", "media", "car"));
            Collections.sort(names, String::compareTo);
            return names.toString();
        }, "[car, media, tuner]");

        check("var + varargs", () -> { var n = sum(1, 2, 3); return n; }, 6);

        check("enum switch expression", () -> {
            var g = Gear.R;
            return switch (g) { case R -> "reverse"; case D -> "drive"; default -> "other"; };
        }, "reverse");

        check("string switch", () -> {
            String s = "nav";
            switch (s) { case "nav": return 1; case "media": return 2; default: return 0; }
        }, 1);

        check("instanceof pattern", () -> {
            Object o = "hello";
            if (o instanceof String str) return str.length();
            return -1;
        }, 5);

        check("text block", () -> """
                a
                b""".length(), 3);

        check("default method", () -> {
            Plugin p = () -> "carmqtt";
            return p.describe();
        }, "plugin:carmqtt");

        check("try-with-resources", () -> {
            try (Res r = new Res()) { /* use */ }
            return Res.closed;
        }, 1);

        check("StringBuilder concat", () -> {
            int speed = 88;
            return "speed=" + speed + "km/h";
        }, "speed=88km/h");

        check("HashMap<String,Long>", () -> {
            Map<String, Long> m = new HashMap<>();
            m.put("a", 5L);
            m.merge("a", 2L, Long::sum);
            return m.get("a");
        }, 7L);

        check("Runnable thread", () -> {
            final int[] box = {0};
            Thread t = new Thread(() -> box[0] = 42);
            t.start();
            try { t.join(); } catch (InterruptedException e) { return -1; }
            return box[0];
        }, 42);



        System.out.println("RESULT passed=" + passed + " failed=" + failed);
    }
}

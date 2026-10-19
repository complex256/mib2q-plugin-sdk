package mibsdk.adapter.hqc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** java.lang.reflect.Proxy fakes for stock interfaces (only the host-repaired stock jar is on the test classpath). */
final class Fakes {

    private Fakes() {
    }

    /** One recorded call. */
    static final class Call {
        final String method;
        final Object[] args;

        Call(String method, Object[] args) {
            this.method = method;
            this.args = args == null ? new Object[0] : args;
        }
    }

    /** Records every non-Object call; optionally throws or answers. Object methods behave like Object's. */
    static class Recorder implements InvocationHandler {
        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        volatile Throwable toThrow;
        volatile Object answer;

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if (m.getDeclaringClass() == Object.class) {
                switch (m.getName()) {
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return "fake@" + Integer.toHexString(System.identityHashCode(proxy));
                }
            }
            calls.add(new Call(m.getName(), args));
            if (toThrow != null) {
                throw toThrow;
            }
            return answer;
        }

        List<Call> named(String method) {
            List<Call> out = new ArrayList<>();
            synchronized (calls) {
                for (Call c : calls) {
                    if (c.method.equals(method)) {
                        out.add(c);
                    }
                }
            }
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> iface, Recorder r) {
        return (T) Proxy.newProxyInstance(Fakes.class.getClassLoader(), new Class<?>[] {iface}, r);
    }
}

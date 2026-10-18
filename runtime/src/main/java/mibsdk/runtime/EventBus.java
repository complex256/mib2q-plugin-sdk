package mibsdk.runtime;

import java.util.ArrayList;
import java.util.List;
import mibsdk.api.Listener;
import mibsdk.api.Subscription;

/**
 * Delivers API events to plugin listeners. A listener always runs on its plugin's {@link SerialExecutor},
 * never on the publishing thread; an exception it throws surfaces as that executor's task failure.
 */
public final class EventBus {

    private static final class Sub implements Subscription {
        final String pluginId;
        final Class type;
        final Listener listener;
        final SerialExecutor exec;
        volatile boolean cancelled;
        private final EventBus bus;

        Sub(EventBus bus, String pluginId, Class type, Listener listener, SerialExecutor exec) {
            this.bus = bus;
            this.pluginId = pluginId;
            this.type = type;
            this.listener = listener;
            this.exec = exec;
        }

        @Override
        public void cancel() {
            cancelled = true;
            bus.remove(this);
        }
    }

    private final List subs = new ArrayList(); // Sub; guarded by this

    public <E> Subscription subscribe(String pluginId, Class<E> type, Listener<? super E> l, SerialExecutor exec) {
        Sub s = new Sub(this, pluginId, type, l, exec);
        synchronized (this) {
            subs.add(s);
        }
        return s;
    }

    /** Queues the event for every subscriber whose type accepts it. Never blocks, never throws. */
    public void publish(final Object event) {
        if (event == null) {
            return;
        }
        Object[] snapshot;
        synchronized (this) {
            snapshot = subs.toArray();
        }
        for (int i = 0; i < snapshot.length; i++) {
            final Sub s = (Sub) snapshot[i];
            if (s.cancelled || !s.type.isInstance(event)) {
                continue;
            }
            try {
                String label = "event:" + s.pluginId + ":" + event.getClass().getName();
                boolean accepted = s.exec.submit(label, new Runnable() {
                    public void run() {
                        if (s.cancelled) {
                            return;
                        }
                        try {
                            s.listener.on(event);
                        } catch (Exception e) {
                            // Rethrown unchanged so the executor reports the plugin's own exception.
                            EventBus.<RuntimeException>sneakyThrow(e);
                        }
                    }
                });
                if (!accepted) {
                    s.cancel(); // the executor was shut down: this subscription can never fire again
                }
            } catch (Throwable ignored) {
                // a broken executor must not stop delivery to the others
            }
        }
    }

    /** Test hook. */
    synchronized int subscriberCount() {
        return subs.size();
    }

    private synchronized void remove(Sub s) {
        subs.remove(s);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}

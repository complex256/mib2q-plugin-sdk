package mibsdk.api;

public interface Events {

    <E> Subscription on(Class<E> type, Listener<? super E> listener);
}

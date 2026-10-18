package mibsdk.api;

public interface Listener<E> {

    void on(E event) throws Exception;
}

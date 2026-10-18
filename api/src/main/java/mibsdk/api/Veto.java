package mibsdk.api;

public interface Veto {

    void setActive(boolean active);

    boolean isActive();

    void release();
}

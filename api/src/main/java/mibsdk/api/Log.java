package mibsdk.api;

public interface Log {

    void info(String m);

    void warn(String m);

    void error(String m, Throwable t);
}

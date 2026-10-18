package mibsdk.api;

public class PermissionDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String permission;

    public PermissionDeniedException(String permission) {
        super("permission not granted: " + permission);
        this.permission = permission;
    }

    public String permission() {
        return permission;
    }
}

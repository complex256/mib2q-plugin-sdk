package mibsdk.api;

/**
 * A declared permission was not granted by the user.
 *
 * <p>Normally a plugin with an ungranted permission is {@code BLOCKED_PERMISSIONS} and never starts, so
 * this is rare. {@link #permission()} names the permission.
 *
 * @since api 1
 */
public class PermissionDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String permission;

    /**
     * @param permission the missing permission id
     */
    public PermissionDeniedException(String permission) {
        super("permission not granted: " + permission);
        this.permission = permission;
    }

    /** The permission that was not granted. */
    public String permission() {
        return permission;
    }
}

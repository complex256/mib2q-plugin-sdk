package mibsdk.runtime;

import java.util.Set;
import mibsdk.api.PermissionDeniedException;

/**
 * A plugin used something its {@code plugin.json} does not list under {@code "permissions"}. Plugin code still
 * catches {@link PermissionDeniedException} and reads {@link #permission()}; the message tells the plugin author
 * the fix is in the manifest, where a declared-but-ungranted permission (the owner did not approve it) keeps
 * {@code permission not granted: <p>}.
 */
final class UndeclaredPermissionException extends PermissionDeniedException {

    private static final long serialVersionUID = 1L;

    UndeclaredPermissionException(String permission) {
        super(permission);
    }

    @Override
    public String getMessage() {
        return "permission not declared in plugin.json: " + permission();
    }

    /**
     * The exception for a denied {@code permission}: undeclared if the manifest's {@code declared} permissions
     * lack it, else the plain "not granted" denial.
     */
    static PermissionDeniedException denial(String permission, Set<String> declared) {
        return declared.contains(permission)
                ? new PermissionDeniedException(permission)
                : new UndeclaredPermissionException(permission);
    }
}

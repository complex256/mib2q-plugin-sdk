package mibsdk.build;

/**
 * Turns a {@link LinkCheck} missing-reference line into something the author can act on: the JDK name behind a
 * rewritten (backported or Retrotranslator-renamed) class, and a one-line hint for the gaps seen most often.
 *
 * <p>The mappings are pure: they read the name the bytecode carries and say nothing when the origin is unknown.
 */
public final class LinkCheckHints {
    private LinkCheckHints() {}

    private static final String BACKPORT_PREFIX = "edu/emory/mathcs/backport/";
    private static final String RETROTRANSLATOR_PREFIX = "net/sf/retrotranslator/runtime/";

    private static final String REGEX_HINT = "no regex on the car: use indexOf/substring or a loop";
    private static final String CONCURRENT_HINT =
            "no java.util.concurrent: the plugin runs on one SDK worker, so plain fields need no locks";
    private static final String FUNCTION_HINT = "compat has only some java.util.function interfaces;"
            + " see compat/src/main/java/mibsdk/compat/java/util/function";

    /** The JDK name behind {@code internalName}: backport and Retrotranslator renames are undone, others kept. */
    public static String jdkName(String internalName) {
        if (internalName.startsWith(BACKPORT_PREFIX)) {
            return internalName.substring(BACKPORT_PREFIX.length());
        }
        if (internalName.startsWith(RETROTRANSLATOR_PREFIX)) {
            String rest = internalName.substring(RETROTRANSLATOR_PREFIX.length());
            int nameStart = rest.lastIndexOf('/') + 1;
            // Retrotranslator prefixes a renamed JDK class's simple name with '_' (java/lang/_String).
            if (nameStart < rest.length() && rest.charAt(nameStart) == '_') {
                return rest.substring(0, nameStart) + rest.substring(nameStart + 1);
            }
            return rest;
        }
        return internalName;
    }

    /** One line of advice for a {@code missing()} line, or null when there is nothing specific to say. */
    public static String hint(String missingLine) {
        String owner = ownerOf(missingLine);
        if (owner == null) {
            return null;
        }
        String member = memberOf(missingLine);
        String jdk = jdkName(owner);
        if ("java/lang/String".equals(jdk) && member != null
                && (member.equals("matches") || member.equals("replaceAll")
                        || member.equals("replaceFirst") || member.equals("split"))) {
            return REGEX_HINT;
        }
        if (jdk.startsWith("java/util/concurrent/")) {
            return CONCURRENT_HINT;
        }
        if (jdk.startsWith("java/util/function/")) {
            return FUNCTION_HINT;
        }
        return null;
    }

    /** The class an internal name in a missing line belongs to, or null if the line has no class part. */
    static String ownerOf(String missingLine) {
        boolean member = missingLine.startsWith("member ");
        String rest;
        if (missingLine.startsWith("class ")) {
            rest = missingLine.substring("class ".length());
        } else if (member) {
            rest = missingLine.substring("member ".length());
        } else {
            return null;
        }
        if (member) {
            // Internal names use '/', so the first '.' is the owner/member separator.
            int dot = rest.indexOf('.');
            return dot > 0 ? rest.substring(0, dot) : rest;
        }
        int space = rest.indexOf(' '); // class lines: " (interface of ...)" / " (via ...)"
        return space >= 0 ? rest.substring(0, space) : rest;
    }

    /** The member name after the owner in a {@code member} line, or null for a class line. */
    private static String memberOf(String missingLine) {
        if (!missingLine.startsWith("member ")) {
            return null;
        }
        String rest = missingLine.substring("member ".length());
        // Internal names use '/', so the first '.' is the owner/member separator.
        int dot = rest.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        String tail = rest.substring(dot + 1);
        int paren = tail.indexOf('(');
        return paren < 0 ? tail : tail.substring(0, paren);
    }
}

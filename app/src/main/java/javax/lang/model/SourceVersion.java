package javax.lang.model;

/**
 * Stub for javax.lang.model.SourceVersion, which is part of the Java compiler API
 * and is absent from the Android runtime. GraphHopper uses SourceVersion.isIdentifier()
 * to validate encoded value names.
 */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5, RELEASE_6,
    RELEASE_7, RELEASE_8, RELEASE_9, RELEASE_10, RELEASE_11, RELEASE_12, RELEASE_13,
    RELEASE_14, RELEASE_15, RELEASE_16, RELEASE_17;

    public static boolean isIdentifier(CharSequence name) {
        String id = name.toString();
        if (id.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(id.charAt(0))) return false;
        for (int i = 1; i < id.length(); i++) {
            if (!Character.isJavaIdentifierPart(id.charAt(i))) return false;
        }
        return true;
    }

    public static boolean isKeyword(CharSequence s) {
        return false;
    }

    public static boolean isName(CharSequence name) {
        String nm = name.toString();
        if (nm.isEmpty()) return false;
        for (String id : nm.split("\\.", -1)) {
            if (!isIdentifier(id)) return false;
        }
        return true;
    }

    public static SourceVersion latest() {
        return RELEASE_17;
    }

    public static SourceVersion latestSupported() {
        return RELEASE_17;
    }
}

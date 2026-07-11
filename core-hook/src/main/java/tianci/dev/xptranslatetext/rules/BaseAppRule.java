package tianci.dev.xptranslatetext.rules;

/**
 * Base class for per-app translation rules. Subclasses may skip specific classes
 * that should not be translated (e.g. emoji / sticker / avatar / input fields).
 *
 * The default common keywords cover classes that are clearly not user-facing post
 * or comment text and would otherwise produce broken or meaningless translations.
 */
public abstract class BaseAppRule {
    // Common non-content classes that should not be translated across social apps.
    private static final String[] COMMON_SKIP_KEYWORDS = {
            "emoji", "sticker", "gif", "avatar", "image", "photo",
            "icon", "drawable", "input", "edittext", "edit_text"
    };

    protected static boolean skipByCommonKeyword(String className) {
        if (className == null) return false;
        String lower = className.toLowerCase();
        for (String kw : COMMON_SKIP_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }
}

package tianci.dev.xptranslatetext.rules;

/**
 * App specific rules for Instagram (com.instagram.android).
 */
public class Instagram extends BaseAppRule {
    public static boolean shouldSkipClass(String className) {
        return skipByCommonKeyword(className);
    }
}

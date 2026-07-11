package tianci.dev.xptranslatetext.rules;

/**
 * App specific rules for Twitter / X (com.twitter.android and common forks).
 */
public class Twitter extends BaseAppRule {
    public static boolean shouldSkipClass(String className) {
        return skipByCommonKeyword(className);
    }
}

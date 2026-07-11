package tianci.dev.xptranslatetext.rules;

/**
 * App specific rules for Reddit (com.reddit.frontpage).
 */
public class Reddit extends BaseAppRule {
    public static boolean shouldSkipClass(String className) {
        return skipByCommonKeyword(className);
    }
}

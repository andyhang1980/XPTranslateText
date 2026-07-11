package tianci.dev.xptranslatetext.rules;

/**
 * App specific rules for Discord (com.discord).
 */
public class Discord extends BaseAppRule {
    public static boolean shouldSkipClass(String className) {
        return skipByCommonKeyword(className);
    }
}

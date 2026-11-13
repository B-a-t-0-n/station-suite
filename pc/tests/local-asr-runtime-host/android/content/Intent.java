package android.content;
public final class Intent {
    public static final String ACTION_MAIN = "android.intent.action.MAIN", CATEGORY_HOME = "android.intent.category.HOME";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public Intent(String ignored) { }
    public Intent addCategory(String ignored) { return this; }
    public Intent addFlags(int ignored) { return this; }
}

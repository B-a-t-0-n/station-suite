package android.content;
import java.io.File;
public class Context {
    public Context getApplicationContext() { return this; }
    public File getFilesDir() { return new File("synthetic-unopened-directory"); }
    public void startActivity(Intent ignored) { throw new AssertionError("no_android_actions_allowed"); }
}

package android.content;

import android.content.res.AssetManager;

/** Host-only Android seam: only the public bundled CA asset can be opened. */
public class Context {
    private final AssetManager assets = new AssetManager();
    public Context getApplicationContext() { return this; }
    public AssetManager getAssets() { return assets; }
}

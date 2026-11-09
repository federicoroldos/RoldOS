package com.roldos;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Google Sans Flex (Google's own typeface, open licensed: assets/fonts/OFL.txt), regular and
 * medium, cut down to Latin letters to keep RoldOS small. Characters outside it (other
 * alphabets in app names) fall back to the system font automatically.
 */
final class Fonts {
    private Fonts() { }

    private static Typeface regular, medium;

    static Typeface regular(Context c) {
        if (regular == null) regular = load(c, "fonts/GoogleSansFlex-Regular.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
        return regular;
    }

    static Typeface medium(Context c) {
        if (medium == null) medium = load(c, "fonts/GoogleSansFlex-Medium.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return medium;
    }

    private static Typeface load(Context c, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getApplicationContext().getAssets(), path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}

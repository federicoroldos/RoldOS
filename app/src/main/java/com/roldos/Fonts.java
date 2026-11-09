package com.roldos;

import android.content.Context;
import android.graphics.Typeface;

/**
 * RoldOS's text typeface, regular and medium. One place to swap it.
 *
 * Right now: the system's Roboto. Google Sans Flex was tried (2026-11-08) and dropped: it
 * clashed with the Unbounded wordmark. Federico is choosing a replacement; to use one, put
 * Latin-subset TTFs in assets/fonts/ and set the two file names below (a missing file falls
 * back to the system font).
 */
final class Fonts {
    private Fonts() { }

    /** assets/ paths, or null for the system font. */
    private static final String REGULAR = null, MEDIUM = null;

    private static Typeface regular, medium;

    static Typeface regular(Context c) {
        if (regular == null) regular = load(c, REGULAR, Typeface.create("sans-serif", Typeface.NORMAL));
        return regular;
    }

    static Typeface medium(Context c) {
        if (medium == null) medium = load(c, MEDIUM, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return medium;
    }

    private static Typeface load(Context c, String path, Typeface fallback) {
        if (path == null) return fallback;
        try {
            return Typeface.createFromAsset(c.getApplicationContext().getAssets(), path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}

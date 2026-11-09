package com.roldos;

import android.content.Context;
import android.graphics.Typeface;

/**
 * RoldOS's text typeface, regular and medium. One place to swap it.
 *
 * Unbounded, the wordmark's own family (the owner's pick, 2026-11-08): Light for text, Regular
 * where RoldOS used medium. Latin-subset TTFs from Google Fonts (`text=` parameter), license in
 * assets/fonts/OFL.txt. Other alphabets and symbols fall back to the system font. (Google Sans
 * Flex was tried first and dropped: it clashed with the wordmark.) To swap: new TTFs in
 * assets/fonts/ and the two names below; null = system font.
 */
final class Fonts {
    private Fonts() { }

    /** assets/ paths, or null for the system font. */
    private static final String REGULAR = "fonts/Unbounded-Light.ttf", MEDIUM = "fonts/Unbounded-Regular.ttf";

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

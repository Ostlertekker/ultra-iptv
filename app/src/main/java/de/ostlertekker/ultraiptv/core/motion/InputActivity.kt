package de.ostlertekker.ultraiptv.core.motion

/**
 * Merkt sich die letzte Tasteneingabe. Während aktiv navigiert/gescrollt wird, pausieren
 * dekorative Animationen (Hintergrund, Eingangsanimationen) – die Liste bekommt die volle Leistung.
 */
object InputActivity {
    @Volatile var lastKeyNanos: Long = 0L
        private set

    /** Zuletzt gedrückte Taste (Android-Keycode). */
    @Volatile var lastKeyCode: Int = 0
        private set

    fun mark(keyCode: Int = 0) { lastKeyNanos = System.nanoTime(); if (keyCode != 0) lastKeyCode = keyCode }

    /** true, wenn der Fokus gerade durch eine Pfeiltaste gewandert ist (und nicht z. B. weil ein Element verschwand). */
    fun navigatedByArrow(windowMs: Long = 500): Boolean = isBusy(windowMs = windowMs) && lastKeyCode in setOf(
        android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)

    /** true, wenn in den letzten [windowMs] Millisekunden eine Taste gedrückt wurde. */
    fun isBusy(nowNanos: Long = System.nanoTime(), windowMs: Long = 650): Boolean =
        lastKeyNanos != 0L && nowNanos - lastKeyNanos < windowMs * 1_000_000L
}

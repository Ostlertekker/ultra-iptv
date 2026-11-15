package de.ostlertekker.ultraiptv.core.motion

/**
 * Merkt sich die letzte Tasteneingabe. Während aktiv navigiert/gescrollt wird, pausieren
 * dekorative Animationen (Hintergrund, Eingangsanimationen) – die Liste bekommt die volle Leistung.
 */
object InputActivity {
    @Volatile var lastKeyNanos: Long = 0L
        private set

    fun mark() { lastKeyNanos = System.nanoTime() }

    /** true, wenn in den letzten [windowMs] Millisekunden eine Taste gedrückt wurde. */
    fun isBusy(nowNanos: Long = System.nanoTime(), windowMs: Long = 650): Boolean =
        lastKeyNanos != 0L && nowNanos - lastKeyNanos < windowMs * 1_000_000L
}

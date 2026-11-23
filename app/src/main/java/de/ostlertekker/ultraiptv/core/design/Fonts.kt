package de.ostlertekker.ultraiptv.core.design

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import de.ostlertekker.ultraiptv.R

/**
 * Mitgelieferte Schriften (Google Fonts, SIL Open Font License – Lizenztexte unter assets/licenses).
 * Auf Latein + Umlaute reduziert, damit die App schlank bleibt.
 */
object AppFonts {
    private fun fam(regular: Int, bold: Int? = null, regWeight: FontWeight = FontWeight.Normal, boldWeight: FontWeight = FontWeight.Bold) =
        if (bold == null) FontFamily(Font(regular, FontWeight.Normal))
        else FontFamily(Font(regular, regWeight), Font(bold, boldWeight))

    /** Sehr nah an der klaren Schrift aus Apples Werbung (San Francisco ist nicht frei verfügbar). */
    val Inter = fam(R.font.inter_regular, R.font.inter_bold)
    val Montserrat = fam(R.font.montserrat_regular, R.font.montserrat_bold, boldWeight = FontWeight.ExtraBold)
    val Poppins = fam(R.font.poppins_regular, R.font.poppins_bold)
    val Manrope = fam(R.font.manrope_regular, R.font.manrope_bold, boldWeight = FontWeight.ExtraBold)
    val Lexend = fam(R.font.lexend_regular, R.font.lexend_bold)
    val Nunito = fam(R.font.nunito_regular, R.font.nunito_bold, boldWeight = FontWeight.ExtraBold)
    val SpaceGrotesk = fam(R.font.spacegrotesk_regular, R.font.spacegrotesk_bold)
    val Comfortaa = fam(R.font.comfortaa_regular, R.font.comfortaa_bold)
    val Orbitron = fam(R.font.orbitron_regular, R.font.orbitron_bold, regWeight = FontWeight.Medium, boldWeight = FontWeight.Black)
    val Audiowide = fam(R.font.audiowide_regular)
    val Exo2 = fam(R.font.exo2_regular, R.font.exo2_bold, boldWeight = FontWeight.ExtraBold)
    val Rajdhani = fam(R.font.rajdhani_regular, R.font.rajdhani_bold, regWeight = FontWeight.Medium)
    val Titillium = fam(R.font.titillium_regular, R.font.titillium_bold)
    val Teko = fam(R.font.teko_regular, R.font.teko_bold, boldWeight = FontWeight.SemiBold)
    val BebasNeue = fam(R.font.bebasneue_regular)
    val Oswald = fam(R.font.oswald_regular, R.font.oswald_bold)
    val RussoOne = fam(R.font.russoone_regular)
    val Righteous = fam(R.font.righteous_regular)
    val Playfair = fam(R.font.playfair_regular, R.font.playfair_bold, boldWeight = FontWeight.ExtraBold)
    val PressStart = fam(R.font.pressstart_regular)

    /** Auswahlliste für die Einstellungen: Name, Beschreibung, Schrift. */
    val catalog: List<Triple<String, String, FontFamily>> = listOf(
        Triple("Inter", tr("Apple-Werbe-Look – klar und edel"), Inter),
        Triple("Manrope", tr("Modern, wie Smartphone-Menüs"), Manrope),
        Triple("Montserrat", tr("Kräftig wie Kinoplakate"), Montserrat),
        Triple("Poppins", tr("Rund und modern"), Poppins),
        Triple("Lexend", tr("Besonders gut lesbar"), Lexend),
        Triple("Nunito", tr("Freundlich und weich"), Nunito),
        Triple(tr("Space Grotesk"), "Technisch-modern", SpaceGrotesk),
        Triple("Comfortaa", tr("Verspielt und rund"), Comfortaa),
        Triple("Titillium", tr("Konsolen-Menü-Stil"), Titillium),
        Triple("Rajdhani", "Gaming-HUD", Rajdhani),
        Triple(tr("Exo 2"), "Futuristisch", Exo2),
        Triple("Orbitron", "Science-Fiction", Orbitron),
        Triple("Audiowide", tr("Rennspiel und Retro-Zukunft"), Audiowide),
        Triple(tr("Russo One"), tr("Sport und Action"), RussoOne),
        Triple("Righteous", "Retro-Arcade", Righteous),
        Triple("Oswald", tr("Schmal und stark"), Oswald),
        Triple("Teko", "Sportsender-Look", Teko),
        Triple(tr("Bebas Neue"), tr("Schlagzeilen in Großbuchstaben"), BebasNeue),
        Triple("Playfair", tr("Elegante Serifen"), Playfair),
        Triple("Pixel", "8-Bit-Konsole", PressStart),
    )
}

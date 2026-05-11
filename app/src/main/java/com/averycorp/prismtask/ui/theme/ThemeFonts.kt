package com.averycorp.prismtask.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.averycorp.prismtask.R

/**
 * Per-theme font trio bundled as TTF assets in `res/font/`.
 *
 * - [body]:    Body and UI text — readable at small sizes, matches the theme's personality.
 * - [display]: Hero headlines and large numerals — decorative, unmistakably per-theme.
 * - [mono]:    Code-like labels, terminal prompts, timer digits. Currently equals [body]
 *              for all themes; kept as a distinct field for future flexibility.
 *
 * Prefer reading from [LocalPrismFonts] inside composables rather than calling
 * [prismThemeFonts] directly.
 */
data class PrismThemeFonts(val body: FontFamily, val display: FontFamily, val mono: FontFamily)

// ── Chakra Petch — Cyberpunk body/mono ──────────────────────────────────────
// Angular techno sans-serif with sci-fi flair.
private val ChakraPetch: FontFamily by lazy {
    FontFamily(
        Font(R.font.chakra_petch_regular, FontWeight.Normal),
        Font(R.font.chakra_petch_bold, FontWeight.Bold)
    )
}

// ── Audiowide — Cyberpunk display ────────────────────────────────────────────
// Wide futurist face with strong geometric forms; single weight only.
private val Audiowide: FontFamily by lazy {
    FontFamily(
        Font(R.font.audiowide_regular, FontWeight.Normal)
    )
}

// ── Rajdhani — Synthwave body/mono ───────────────────────────────────────────
// Condensed sans-serif that reads cleanly next to heavy display type.
private val Rajdhani: FontFamily by lazy {
    FontFamily(
        Font(R.font.rajdhani_regular, FontWeight.Normal),
        Font(R.font.rajdhani_medium, FontWeight.Medium),
        Font(R.font.rajdhani_bold, FontWeight.Bold)
    )
}

// ── Monoton — Synthwave display ──────────────────────────────────────────────
// Iconic 80s neon-stripe letters; single weight only.
private val Monoton: FontFamily by lazy {
    FontFamily(
        Font(R.font.monoton_regular, FontWeight.Normal)
    )
}

// ── Share Tech Mono — Matrix body/mono ───────────────────────────────────────
// Terminal monospace; single weight only.
private val ShareTechMono: FontFamily by lazy {
    FontFamily(
        Font(R.font.share_tech_mono_regular, FontWeight.Normal)
    )
}

// ── VT323 — Matrix display ───────────────────────────────────────────────────
// Pixelated CRT terminal face; single weight only.
private val Vt323: FontFamily by lazy {
    FontFamily(
        Font(R.font.vt323_regular, FontWeight.Normal)
    )
}

// ── Space Grotesk — Void body/mono ───────────────────────────────────────────
// Variable font (wght axis 300–700); one file serves all weights.
@OptIn(ExperimentalTextApi::class)
private val SpaceGrotesk: FontFamily by lazy {
    FontFamily(
        Font(
            R.font.space_grotesk,
            FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400))
        ),
        Font(
            R.font.space_grotesk,
            FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500))
        ),
        Font(
            R.font.space_grotesk,
            FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700))
        )
    )
}

// ── Fraunces — Void display ───────────────────────────────────────────────────
// Variable font with separate upright and italic files.
// fraunces.ttf covers the upright axis; fraunces_italic.ttf the italic axis.
@OptIn(ExperimentalTextApi::class)
private val Fraunces: FontFamily by lazy {
    FontFamily(
        Font(
            R.font.fraunces,
            FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400))
        ),
        Font(
            R.font.fraunces,
            FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700))
        ),
        Font(
            R.font.fraunces_italic,
            FontWeight.Normal,
            FontStyle.Italic,
            variationSettings = FontVariation.Settings(FontVariation.weight(400))
        )
    )
}

// ── Per-theme font sets ──────────────────────────────────────────────────────

private val CyberpunkFonts = PrismThemeFonts(
    body = ChakraPetch,
    display = Audiowide,
    mono = ChakraPetch
)

private val SynthwaveFonts = PrismThemeFonts(
    body = Rajdhani,
    display = Monoton,
    mono = Rajdhani
)

private val MatrixFonts = PrismThemeFonts(
    body = ShareTechMono,
    display = Vt323,
    mono = ShareTechMono
)

private val VoidFonts = PrismThemeFonts(
    body = SpaceGrotesk,
    display = Fraunces,
    mono = SpaceGrotesk
)

/**
 * Returns the [PrismThemeFonts] trio for [theme]. Results are stable singletons.
 * Inside composables, prefer [LocalPrismFonts].current over calling this directly.
 */
fun prismThemeFonts(theme: PrismTheme): PrismThemeFonts = when (theme) {
    PrismTheme.CYBERPUNK -> CyberpunkFonts
    PrismTheme.SYNTHWAVE -> SynthwaveFonts
    PrismTheme.MATRIX -> MatrixFonts
    PrismTheme.VOID -> VoidFonts
}

@Deprecated(
    message = "Use LocalPrismFonts.current.display",
    replaceWith = ReplaceWith(
        "LocalPrismFonts.current.display",
        "com.averycorp.prismtask.ui.theme.LocalPrismFonts"
    )
)
fun prismDisplayFont(theme: PrismTheme): FontFamily = prismThemeFonts(theme).display

@Deprecated(
    message = "Use LocalPrismFonts.current.mono",
    replaceWith = ReplaceWith(
        "LocalPrismFonts.current.mono",
        "com.averycorp.prismtask.ui.theme.LocalPrismFonts"
    )
)
fun prismMonoFont(theme: PrismTheme): FontFamily = prismThemeFonts(theme).mono

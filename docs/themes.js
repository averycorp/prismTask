// PrismTask theme tokens — mirrors
// app/src/main/java/com/averycorp/prismtask/ui/theme/ThemeColors.kt + ThemeFonts.kt
const PRISM_THEMES = {
  CYBERPUNK: {
    id: 'CYBERPUNK',
    label: 'Cyberpunk',
    tagline: 'Neon terminal · cyan / magenta',
    colors: {
      // ── core 13 ─────────────────────────────────────────
      background: '#0A0A0F',
      surface: '#0D0D18',
      surfaceVariant: '#111120',
      border: 'rgba(0,245,255,0.10)',
      primary: '#00F5FF',
      secondary: '#FF00AA',
      onBackground: '#E0F8FF',
      onSurface: '#A0CCD4',
      muted: '#4A8A9A',
      urgentAccent: '#FF00AA',
      urgentSurface: '#1A0010',
      tagSurface: '#001A1A',
      tagText: '#00F5FF',

      // ── semantic state (electric neons) ────────────────
      successColor:     '#00FFB3', // icy mint, hums alongside cyan primary
      warningColor:     '#FFD100', // hazard yellow, reads as a caution bar
      destructiveColor: '#FF2E6C', // hot magenta-red, hotter than urgentAccent
      infoColor:        '#66E0FF', // washed cyan — neutral, not the primary

      // ── swipe actions (saturated flat backgrounds) ─────
      swipeComplete:    '#00D48C', // deep emerald — decisive completion
      swipeDelete:      '#FF1A55', // "alarm red" for destructive reveal
      swipeReschedule:  '#FFB020', // amber — time-shift
      swipeArchive:     '#3E6B7A', // muted teal — out of sight
      swipeMove:        '#B84DFF', // violet — to another bucket
      swipeFlag:        '#FF5AF0', // hot pink flag

      // ── Eisenhower quadrants ───────────────────────────
      quadrantQ1:       '#FF2E6C', // urgent+important: alarm red
      quadrantQ2:       '#00F5FF', // important: signature cyan
      quadrantQ3:       '#FFD100', // urgent: hazard yellow
      quadrantQ4:       '#4A8A9A', // neither: muted cyan-grey

      // ── data-viz (8 categorical colors) ────────────────
      dataVisualizationPalette: [
        '#00F5FF', // signal cyan
        '#FF00AA', // hot magenta
        '#FFD100', // hazard yellow
        '#00FFB3', // mint
        '#B84DFF', // violet
        '#FF7A00', // warning orange
        '#66E0FF', // ice cyan
        '#FF2E6C', // alarm red
      ],
    },
    fonts: {
      body: '"Chakra Petch", system-ui, sans-serif',
      display: '"Audiowide", "Chakra Petch", sans-serif',
      mono: '"Chakra Petch", monospace',
    },
    displayUpper: true,
    displayTracking: '0.06em',
    scanlines: true,
    chipShape: 'sharp',
    radius: 2,
    cardRadius: 4,
    glow: 'strong',
    brackets: true,       // corner brackets on hero cards
    hudDividers: true,    // dashed separator lines
    density: 'tight',
  },
  SYNTHWAVE: {
    id: 'SYNTHWAVE',
    label: 'Synthwave',
    tagline: 'Neon sunset · pink / purple',
    colors: {
      background: '#0D0717',
      surface: '#130820',
      surfaceVariant: '#1A0F2E',
      border: 'rgba(110,63,255,0.18)',
      primary: '#FF2D87',
      secondary: '#6E3FFF',
      onBackground: '#F0D0FF',
      onSurface: '#B080D0',
      muted: '#5E3A7A',
      urgentAccent: '#FF2D87',
      urgentSurface: '#1F0015',
      tagSurface: '#12082A',
      tagText: '#6E3FFF',

      // ── semantic state (retrowave vibrance) ────────────
      successColor:     '#3EE8B8', // turquoise — Miami aqua
      warningColor:     '#FFB347', // sunset amber
      destructiveColor: '#FF3D5A', // sun-hot coral red
      infoColor:        '#8ED1FF', // cold neon sky

      // ── swipe actions (VHS gradients, saturated) ───────
      swipeComplete:    '#1FC9A0', // aqua gradient base
      swipeDelete:      '#E81F48', // coral/crimson
      swipeReschedule:  '#FF8C3B', // sunset orange
      swipeArchive:     '#4A3E82', // dusk purple
      swipeMove:        '#8B5AFF', // lavender
      swipeFlag:        '#FF6AC8', // bubblegum flag

      // ── Eisenhower quadrants ───────────────────────────
      quadrantQ1:       '#FF3D5A', // sun-hot coral
      quadrantQ2:       '#6E3FFF', // purple signature
      quadrantQ3:       '#FFB347', // sunset amber
      quadrantQ4:       '#5E3A7A', // dusk — recedes

      // ── data-viz (8 categorical colors) ────────────────
      dataVisualizationPalette: [
        '#FF2D87', // hot pink
        '#6E3FFF', // electric purple
        '#FFB347', // sunset amber
        '#3EE8B8', // miami aqua
        '#FF6AC8', // bubblegum
        '#8ED1FF', // neon sky
        '#FF3D5A', // coral
        '#B080D0', // dusty lilac
      ],
    },
    fonts: {
      body: '"Rajdhani", system-ui, sans-serif',
      display: '"Monoton", "Rajdhani", sans-serif',
      mono: '"Rajdhani", monospace',
    },
    displayUpper: true,
    displayTracking: '0.08em',
    gridFloor: true,
    chipShape: 'pill',
    radius: 18,
    cardRadius: 22,
    glow: 'heavy',
    sunset: true,         // radial sunset behind hero cards
    density: 'airy',
  },
  MATRIX: {
    id: 'MATRIX',
    label: 'Matrix',
    tagline: 'Terminal green · monospace CRT',
    colors: {
      background: '#010D03',
      surface: '#010F04',
      surfaceVariant: '#021206',
      border: 'rgba(0,255,65,0.14)',
      primary: '#00FF41',
      secondary: '#AAFF00',
      onBackground: '#B0FFB8',
      onSurface: '#70CC80',
      muted: '#1A5E25',
      urgentAccent: '#AAFF00',
      urgentSurface: '#0A1400',
      tagSurface: '#001A06',
      tagText: '#00FF41',

      // ── semantic state (green hue variations) ──────────
      // Matrix is monochrome-green on principle. Semantic states
      // differ by BRIGHTNESS / TINT within the green spectrum, plus
      // a rare amber-green for warning and a phosphor-red for destruct.
      successColor:     '#00FF41', // full phosphor green = completion
      warningColor:     '#E6FF3C', // chartreuse — edge of green spectrum
      destructiveColor: '#FF3C3C', // CRT alarm red — the only non-green
      infoColor:        '#7FFFB2', // pale mint — dim/muted informational

      // ── swipe actions (green CRT cells) ────────────────
      swipeComplete:    '#008F24', // dark green cell w/ bright text
      swipeDelete:      '#8F0000', // phosphor-dim red
      swipeReschedule:  '#7A8F00', // olive-green
      swipeArchive:     '#063A12', // archive — near black with green tint
      swipeMove:        '#1A5E25', // terminal muted green
      swipeFlag:        '#AAFF00', // bright yellow-green

      // ── Eisenhower quadrants ───────────────────────────
      quadrantQ1:       '#FF3C3C', // alarm red (the only break from green)
      quadrantQ2:       '#00FF41', // phosphor primary
      quadrantQ3:       '#AAFF00', // yellow-green warn
      quadrantQ4:       '#1A5E25', // dim terminal green

      // ── data-viz (8 categorical — green ladder + breaks) ─
      dataVisualizationPalette: [
        '#00FF41', // phosphor bright
        '#AAFF00', // yellow-green
        '#00B82D', // mid green
        '#7FFFB2', // pale mint
        '#E6FF3C', // chartreuse
        '#008F24', // dark green
        '#00FFAA', // cyan-green
        '#FF3C3C', // alarm red (break — for final/outlier category)
      ],
    },
    fonts: {
      body: '"Share Tech Mono", ui-monospace, monospace',
      display: '"VT323", "Share Tech Mono", monospace',
      mono: '"Share Tech Mono", ui-monospace, monospace',
    },
    displayUpper: false,
    displayTracking: '0.02em',
    scanlines: true,
    chipShape: 'sharp',
    radius: 0,
    cardRadius: 0,
    glow: 'soft',
    terminal: true,       // > prompts, caret cursor, rain backdrop
    density: 'tight',
  },
  VOID: {
    id: 'VOID',
    label: 'Void',
    tagline: 'Editorial minimal · serif + sans',
    colors: {
      background: '#111113',
      surface: '#161618',
      surfaceVariant: '#1E1E22',
      border: 'rgba(46,46,52,0.5)',
      primary: '#C8B8FF',
      secondary: '#8888CC',
      onBackground: '#DCDCE4',
      onSurface: '#A0A0AB',
      muted: '#3E3E4A',
      urgentAccent: '#E8A0A0',
      urgentSurface: '#261616',
      tagSurface: '#1A1A26',
      tagText: '#8888CC',

      // ── semantic state (muted editorial) ───────────────
      // Void avoids saturation. All states are desaturated, print-inspired.
      successColor:     '#8FB896', // sage / eucalyptus
      warningColor:     '#D4A87A', // warm sand / burnished gold
      destructiveColor: '#C68888', // dusty rose (not alarm red)
      infoColor:        '#8A9CC0', // slate blue

      // ── swipe actions (dusty, paperback book jacket) ───
      swipeComplete:    '#4A6B52', // forest
      swipeDelete:      '#8B4545', // oxblood
      swipeReschedule:  '#8B6A3A', // mustard
      swipeArchive:     '#38383F', // ink — darker than surface
      swipeMove:        '#5A5378', // indigo
      swipeFlag:        '#A86B82', // plum

      // ── Eisenhower quadrants ───────────────────────────
      quadrantQ1:       '#C68888', // dusty rose
      quadrantQ2:       '#C8B8FF', // lilac primary
      quadrantQ3:       '#D4A87A', // burnished gold
      quadrantQ4:       '#3E3E4A', // muted ink

      // ── data-viz (8 editorial tones — pigments, not neons) ─
      dataVisualizationPalette: [
        '#C8B8FF', // lilac
        '#8FB896', // sage
        '#D4A87A', // burnished gold
        '#8A9CC0', // slate blue
        '#C68888', // dusty rose
        '#B8A4D4', // grape
        '#7FA89E', // eucalyptus
        '#A89876', // khaki
      ],
    },
    fonts: {
      body: '"Space Grotesk", system-ui, sans-serif',
      display: '"Fraunces", "Space Grotesk", serif',
      mono: '"Space Grotesk", monospace',
    },
    displayUpper: false,
    displayTracking: '-0.02em',
    chipShape: 'pill',
    radius: 10,
    cardRadius: 14,
    glow: 'none',
    editorial: true,      // hairlines, serif numerals, generous space
    density: 'airy',
  },
};

const THEME_ORDER = ['CYBERPUNK', 'SYNTHWAVE', 'MATRIX', 'VOID'];
const SCREEN_ORDER = ['today', 'tasks', 'matrix', 'insights', 'meds', 'daily', 'recurring', 'timer', 'settings'];

window.PRISM_THEMES = PRISM_THEMES;
window.THEME_ORDER = THEME_ORDER;
window.SCREEN_ORDER = SCREEN_ORDER;

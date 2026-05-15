// MedicationScreen — slot-based dose tracker.
// Mirrors app/.../ui/screens/medication/MedicationScreen.kt:
//   - Top bar: bulk-mark + history + edit actions
//   - One card per active slot (Morning, Afternoon, Evening, Night)
//   - Each card: slot name, ideal time ± drift, "taken at" line, tier chip
//     (Skipped / Essential / Prescription / Complete), then a list of
//     medications linked to the slot with circular tap-to-mark checkboxes.
//
// Tier colors map to PER-THEME semantic tokens (instead of the source's
// hard-coded #EF4444/#3B82F6/#10B981) so the screen reads natively in each
// theme:
//     SKIPPED      → muted
//     ESSENTIAL    → destructiveColor   (red-ish in every theme)
//     PRESCRIPTION → infoColor          (blue-ish — Prescription / "info")
//     COMPLETE     → successColor       (green / mint / sage / phosphor)

function tierColors(theme, tier) {
  const c = theme.colors;
  switch (tier) {
    case 'SKIPPED':      return c.muted;
    case 'ESSENTIAL':    return c.destructiveColor;
    case 'PRESCRIPTION': return c.infoColor;
    case 'COMPLETE':     return c.successColor;
    default:             return c.muted;
  }
}
function tierLabel(tier) {
  return { SKIPPED: 'Skipped', ESSENTIAL: 'Essential', PRESCRIPTION: 'Prescription', COMPLETE: 'Complete' }[tier];
}

// Single-button selector for one tier. Active = filled, inactive = outlined.
// Renders as a segmented row of 4 (one per tier) inside SlotCard.
function TierButton({ theme, tier, active, isUserSet, isBacklogged, compact }) {
  const color = tierColors(theme, tier);
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;
  const up = theme.displayUpper ? 'uppercase' : 'none';

  // Short label so 4 buttons fit on a phone-narrow row.
  // Keep these short — Cyberpunk/Synthwave display in mixed-case to stay compact.
  const shortLabel = {
    SKIPPED: isMatrix ? 'SKIP' : (isVoid ? 'SKIP' : 'Skip'),
    ESSENTIAL: isMatrix ? 'ESS' : (isVoid ? 'ESS' : 'Ess'),
    PRESCRIPTION: isMatrix ? 'RX' : (isVoid ? 'RX' : 'Rx'),
    COMPLETE: isMatrix ? 'DONE' : (isVoid ? 'DONE' : 'Done'),
  }[tier];

  const radius = theme.chipShape === 'sharp' ? (isMatrix ? 0 : Math.min(theme.radius, 8)) : 8;
  return (
    <div style={{
      flex: 1, minWidth: 0,
      position: 'relative',
      display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 4,
      padding: compact ? '6px 2px' : '7px 4px',
      borderRadius: radius,
      background: active ? `${color}26` : 'transparent',
      border: `1px solid ${active ? color : theme.colors.border}`,
      color: active ? color : theme.colors.muted,
      fontFamily: theme.fonts.body,
      fontSize: isVoid ? 10 : 11,
      fontWeight: active ? 700 : 500,
      letterSpacing: isVoid ? 1.2 : 0.2,
      textTransform: 'none',
      whiteSpace: 'nowrap',
      cursor: 'pointer',
      ...(active && isCyber ? { boxShadow: `0 0 10px ${color}55, inset 0 0 6px ${color}25` } : {}),
      ...(active && isSynth ? { boxShadow: `0 0 12px ${color}50` } : {}),
    }}>
      {active && isBacklogged && (
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0 }}>
          <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
        </svg>
      )}
      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
        {isMatrix && active ? `[${shortLabel}]` : shortLabel}
      </span>
      {/* tiny user-set dot — indicates the tier was overridden by hand */}
      {active && isUserSet && (
        <span style={{
          position: 'absolute', top: 3, right: 4,
          width: 5, height: 5, borderRadius: '50%',
          background: color,
          ...(theme.glow !== 'none' ? { boxShadow: `0 0 4px ${color}` } : {}),
        }}/>
      )}
    </div>
  );
}

function DoseRow({ theme, name, tierShort, taken }) {
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const c = theme.colors;
  const radius = theme.chipShape === 'sharp' ? (isMatrix ? 0 : 6) : 10;

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 8,
      padding: '6px 10px',
      marginBottom: 5,
      borderRadius: radius,
      background: taken ? `${c.primary}1F` : c.surfaceVariant,
      border: `1px solid ${taken ? c.primary : c.border}`,
      ...(isCyber && taken ? { boxShadow: `inset 0 0 12px ${c.primary}30` } : {}),
    }}>
      {/* tap-to-mark square */}
      {isMatrix ? (
        <div style={{
          width: 18, height: 18, flexShrink: 0,
          border: `1px solid ${taken ? c.primary : c.muted}`,
          background: taken ? c.primary : 'transparent',
          color: c.background,
          fontFamily: theme.fonts.body, fontSize: 12, fontWeight: 700,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>{taken ? '✓' : ''}</div>
      ) : (
        <div style={{
          width: 18, height: 18, flexShrink: 0,
          borderRadius: 4,
          border: `1.5px solid ${taken ? c.primary : c.muted}`,
          background: taken ? c.primary : 'transparent',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          {taken && (
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke={c.background} strokeWidth="3.4" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="20 6 9 17 4 12"/>
            </svg>
          )}
        </div>
      )}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{
          fontFamily: isVoid ? theme.fonts.display : theme.fonts.body,
          fontSize: isVoid ? 14 : 13,
          fontWeight: taken ? 600 : 500,
          color: c.onBackground,
          letterSpacing: isVoid ? -0.1 : 0.1,
          lineHeight: 1.2,
          textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap',
        }}>{name}</div>
      </div>
      {/* tier dot — keeps tier color visible without a duplicate label */}
      <span style={{
        flexShrink: 0,
        width: 7, height: 7,
        borderRadius: theme.chipShape === 'sharp' ? 0 : '50%',
        background: tierColors(theme, tierShort.toUpperCase()),
        ...(theme.glow !== 'none' ? { boxShadow: `0 0 5px ${tierColors(theme, tierShort.toUpperCase())}90` } : {}),
      }}/>
    </div>
  );
}

function SlotCard({ theme, slot }) {
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const c = theme.colors;
  const tintColor = tierColors(theme, slot.tier);

  return (
    <div style={{
      position: 'relative',
      background: c.surface,
      border: `1px solid ${c.border}`,
      borderRadius: theme.cardRadius,
      padding: isVoid ? '12px 14px' : '11px 12px',
      marginBottom: 8,
      overflow: 'hidden',
      ...(isCyber ? { boxShadow: `inset 0 0 0 1px ${tintColor}18` } : {}),
      ...(isSynth ? { boxShadow: `0 8px 22px ${tintColor}25` } : {}),
    }}>
      {/* Cyberpunk corner brackets */}
      {isCyber && (
        <>
          <div style={{ position: 'absolute', top: 6, left: 6, width: 10, height: 10, borderTop: `2px solid ${tintColor}`, borderLeft: `2px solid ${tintColor}` }}/>
          <div style={{ position: 'absolute', top: 6, right: 6, width: 10, height: 10, borderTop: `2px solid ${tintColor}`, borderRight: `2px solid ${tintColor}` }}/>
        </>
      )}
      {/* Synthwave radial wash */}
      {isSynth && (
        <div style={{
          position: 'absolute', inset: 0,
          background: `radial-gradient(circle at 90% 0%, ${tintColor}28, transparent 65%)`,
          pointerEvents: 'none',
        }}/>
      )}

      <div style={{ position: 'relative', zIndex: 1 }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            {/* Slot icon + name row */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <div style={{
                width: 22, height: 22, flexShrink: 0,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: tintColor,
              }}>{slot.icon}</div>
              <div style={{
                fontFamily: isVoid ? theme.fonts.display : theme.fonts.body,
                fontSize: isVoid ? 18 : 16,
                fontWeight: 700,
                color: c.onBackground,
                letterSpacing: isVoid ? -0.2 : 0.2,
                textTransform: up,
              }}>
                {isMatrix ? `> ${slot.name.toUpperCase()}` : slot.name}
              </div>
            </div>
            <div style={{
              marginTop: 4,
              fontFamily: theme.fonts.mono, fontSize: isVoid ? 10 : 11,
              color: c.muted,
              letterSpacing: isVoid ? 1.4 : 0.4,
              textTransform: isVoid ? 'uppercase' : 'none',
            }}>
              {isMatrix ? `${slot.idealTime} ±${slot.drift}m` : `${slot.idealTime} · ±${slot.drift}m`}
            </div>
            {slot.takenAt && (
              <div style={{
                marginTop: 3,
                fontFamily: theme.fonts.body, fontSize: isVoid ? 11 : 12,
                color: c.primary, fontWeight: 600,
                ...(isCyber ? { textShadow: `0 0 8px ${c.primary}60` } : {}),
              }}>
                {slot.backlogged
                  ? `Taken ${slot.takenAt} · Logged ${slot.loggedAt}`
                  : `Taken at ${slot.takenAt}`}
              </div>
            )}
          </div>
        </div>

        {/* 4-tier segmented selector — one button per tier */}
        <div style={{ display: 'flex', gap: 4, marginTop: 9 }}>
          {['SKIPPED', 'ESSENTIAL', 'PRESCRIPTION', 'COMPLETE'].map(t => (
            <TierButton
              key={t}
              theme={theme}
              tier={t}
              active={t === slot.tier}
              isUserSet={t === slot.tier && slot.isUserSet}
              isBacklogged={t === slot.tier && slot.backlogged}
            />
          ))}
        </div>

        {/* Med list */}
        {slot.meds.length > 0 ? (
          <div style={{ marginTop: 9 }}>
            {slot.meds.map((m, i) => (
              <DoseRow key={i} theme={theme} name={m.name} tierShort={m.tierShort} taken={m.taken}/>
            ))}
          </div>
        ) : (
          <div style={{
            marginTop: 10,
            fontFamily: theme.fonts.body,
            fontSize: isVoid ? 11 : 12,
            color: c.muted,
            letterSpacing: isVoid ? 1.4 : 0.2,
            textTransform: isVoid ? 'uppercase' : 'none',
            fontStyle: isVoid ? 'italic' : 'normal',
          }}>
            {isMatrix ? '// no meds linked' : 'No medications linked to this slot.'}
          </div>
        )}
      </div>
    </div>
  );
}

function MedicationScreen({ theme }) {
  const c = theme.colors;
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;

  // ── Mock data — exercises all 4 tier colors + a backlogged + a no-meds slot ──
  const slots = [
    {
      name: 'Morning',
      icon: (
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="4"/>
          <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>
        </svg>
      ),
      idealTime: '8:00 AM', drift: 30,
      takenAt: '8:05 AM',
      tier: 'COMPLETE', isUserSet: false, backlogged: false,
      meds: [
        { name: 'Vitamin D3 · 2000 IU',  tierShort: 'Essential',    taken: true  },
        { name: 'Magnesium glycinate',    tierShort: 'Complete',     taken: true  },
        { name: 'Omega-3 · 1g',          tierShort: 'Complete',     taken: true  },
      ],
    },
    {
      name: 'Afternoon',
      icon: (
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="5"/>
          <path d="M12 1v3M12 20v3M4.22 4.22l2.12 2.12M17.66 17.66l2.12 2.12M1 12h3M20 12h3"/>
        </svg>
      ),
      idealTime: '1:00 PM', drift: 45,
      takenAt: '1:30 PM', loggedAt: '3:45 PM', backlogged: true,
      tier: 'PRESCRIPTION', isUserSet: false,
      meds: [
        { name: 'Methylphenidate · 10mg', tierShort: 'Prescription', taken: true  },
        { name: 'L-theanine · 200mg',     tierShort: 'Complete',     taken: false },
      ],
    },
    {
      name: 'Evening',
      icon: (
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
          <path d="M17 18a5 5 0 0 0-10 0"/>
          <line x1="12" y1="2" x2="12" y2="9"/>
          <line x1="4.22" y1="10.22" x2="5.64" y2="11.64"/>
          <line x1="1" y1="18" x2="3" y2="18"/>
          <line x1="21" y1="18" x2="23" y2="18"/>
          <line x1="18.36" y1="11.64" x2="19.78" y2="10.22"/>
        </svg>
      ),
      idealTime: '7:00 PM', drift: 60,
      takenAt: null,
      tier: 'ESSENTIAL', isUserSet: false, backlogged: false,
      meds: [
        { name: 'Sertraline · 50mg',   tierShort: 'Essential',    taken: false },
        { name: 'Iron · 65mg',          tierShort: 'Essential',    taken: false },
        { name: 'Probiotic',            tierShort: 'Complete',     taken: false },
      ],
    },
    {
      name: 'Night',
      icon: (
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
        </svg>
      ),
      idealTime: '10:30 PM', drift: 30,
      takenAt: null,
      tier: 'SKIPPED', isUserSet: true, backlogged: false,
      meds: [
        { name: 'Melatonin · 0.5mg',     tierShort: 'Complete',     taken: false },
      ],
    },
  ];

  // Day summary (totals across all slots)
  const totalDoses = slots.reduce((n, s) => n + s.meds.length, 0);
  const takenDoses = slots.reduce((n, s) => n + s.meds.filter(m => m.taken).length, 0);

  return (
    <div className="no-scrollbar" style={{
      height: '100%', overflow: 'auto',
      padding: '4px 18px 90px',
      background: c.background,
    }}>
      {/* Top bar */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: 6 }}>
        <div style={{ fontSize: 10, letterSpacing: 2, color: c.muted, fontFamily: theme.fonts.body, textTransform: up, whiteSpace: 'nowrap' }}>
          {isMatrix && '◉ medication --today'}
          {isCyber && '// MEDS.LOG'}
          {isSynth && '◆ MEDS'}
          {isVoid && 'Medication'}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          {/* bulk-mark icon (PlaylistAddCheck-ish) */}
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke={c.onSurface} strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
            <line x1="3" y1="6" x2="13" y2="6"/>
            <line x1="3" y1="12" x2="13" y2="12"/>
            <line x1="3" y1="18" x2="9" y2="18"/>
            <polyline points="16 16 19 19 24 14" transform="translate(-1 -2)"/>
          </svg>
          {/* history clock icon */}
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke={c.onSurface} strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
            <path d="M3 12a9 9 0 1 0 3-6.7L3 8"/>
            <polyline points="3 3 3 8 8 8"/>
            <line x1="12" y1="8" x2="12" y2="12"/>
            <line x1="12" y1="12" x2="14.5" y2="14.5"/>
          </svg>
          {/* edit pencil */}
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke={c.onSurface} strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 20h9"/>
            <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4z"/>
          </svg>
        </div>
      </div>

      {/* Title */}
      <div style={{
        marginTop: isVoid ? 26 : 20, marginBottom: 4,
        fontFamily: theme.fonts.display,
        fontSize: isVoid ? 44 : 30, fontWeight: isVoid ? 500 : 700,
        color: c.onBackground,
        textTransform: up, letterSpacing: theme.displayTracking,
        lineHeight: 1,
        ...(isSynth ? { textShadow: `0 0 22px ${c.primary}70` } : {}),
        ...(isCyber ? { textShadow: `0 0 8px ${c.primary}70` } : {}),
      }}>
        {isMatrix ? 'MEDICATION' : isVoid ? <span>Meds<span style={{ color: c.primary }}>.</span></span> : 'Medication'}
      </div>
      <div style={{
        fontSize: isVoid ? 12 : 13, color: c.muted,
        fontFamily: theme.fonts.body, letterSpacing: isVoid ? 1.5 : 0.3,
        textTransform: isVoid ? 'uppercase' : 'none',
        marginBottom: 14,
      }}>
        {isMatrix
          ? `// ${takenDoses}/${totalDoses} doses · ${slots.length} slots`
          : `${takenDoses} of ${totalDoses} doses today · ${slots.length} slots`
        }
      </div>

      {/* Day-progress mini-bar (uses successColor) */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 10,
        marginBottom: 18,
        padding: isVoid ? '12px 14px' : '10px 12px',
        background: c.surface,
        border: `1px solid ${c.border}`,
        borderRadius: theme.cardRadius,
      }}>
        <div style={{
          fontFamily: theme.fonts.mono, fontSize: 11,
          color: c.muted, letterSpacing: 1.4, textTransform: 'uppercase',
          whiteSpace: 'nowrap',
        }}>
          {isMatrix ? '> day' : 'Today'}
        </div>
        <div style={{
          flex: 1, height: isVoid ? 6 : 8,
          background: c.surfaceVariant,
          borderRadius: theme.chipShape === 'sharp' ? 0 : 4,
          overflow: 'hidden', position: 'relative',
        }}>
          <div style={{
            position: 'absolute', inset: 0,
            width: `${Math.round(takenDoses / totalDoses * 100)}%`,
            background: c.successColor,
            ...(theme.glow !== 'none' ? { boxShadow: `0 0 10px ${c.successColor}90` } : {}),
            ...(isMatrix ? {
              backgroundImage: `repeating-linear-gradient(90deg, ${c.successColor} 0 6px, ${c.successColor}88 6px 8px)`,
            } : {}),
          }}/>
        </div>
        <div style={{
          fontFamily: theme.fonts.mono, fontSize: 12,
          color: c.successColor, fontWeight: 600,
          ...(theme.glow !== 'none' ? { textShadow: `0 0 6px ${c.successColor}80` } : {}),
        }}>
          {takenDoses}/{totalDoses}
        </div>
      </div>

      {/* Slot cards */}
      {slots.map((slot, i) => <SlotCard key={i} theme={theme} slot={slot}/>)}
    </div>
  );
}

window.MedicationScreen = MedicationScreen;

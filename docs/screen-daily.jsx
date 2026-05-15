// DailyScreen — daily habit check-off list (one of the 6 bottom tabs).

function HabitCard({ theme, icon, name, subtitle, subtitleColor, count, total, ringColor }) {
  const R = 18;
  const c = 2 * Math.PI * R;
  const pct = count / total;
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 14,
      padding: isVoid ? '18px 20px' : '14px 18px',
      background: theme.colors.surface,
      border: `1px solid ${theme.colors.border}`,
      borderRadius: theme.cardRadius,
      marginBottom: isVoid ? 14 : 10,
      position: 'relative',
      ...(isCyber ? { borderLeft: `3px solid ${ringColor}` } : {}),
    }}>
      {isMatrix && <span style={{ color: ringColor, opacity: 0.7, fontSize: 16, fontFamily: theme.fonts.body }}>▸</span>}
      <div style={{
        width: 44, height: 44,
        borderRadius: theme.chipShape === 'sharp' ? 6 : 22,
        background: `${ringColor}1F`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        border: `1px solid ${ringColor}55`,
        flexShrink: 0,
        ...(isCyber ? { boxShadow: `0 0 12px ${ringColor}40` } : {}),
      }}>
        <Icon name={icon} size={22} color={ringColor}/>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{
          fontSize: isVoid ? 17 : 16, fontWeight: isVoid ? 500 : 600,
          color: theme.colors.onBackground,
          letterSpacing: isVoid ? -0.2 : 0.1,
          fontFamily: isVoid ? theme.fonts.display : theme.fonts.body,
        }}>{name}</div>
        {subtitle && (
          <div style={{
            marginTop: 2, fontSize: isVoid ? 10 : 12,
            fontWeight: 500, color: subtitleColor,
            letterSpacing: isVoid ? 2 : 0.3,
            textTransform: theme.displayUpper || isVoid ? 'uppercase' : 'none',
            fontFamily: theme.fonts.body,
          }}>{subtitle}</div>
        )}
      </div>
      <div style={{ position: 'relative', width: 48, height: 48, flexShrink: 0 }}>
        <svg width="48" height="48" viewBox="0 0 48 48"
          style={theme.glow !== 'none' && pct > 0 ? { filter: `drop-shadow(0 0 6px ${ringColor}80)` } : {}}>
          <circle cx="24" cy="24" r={R} fill="none" stroke={`${ringColor}30`} strokeWidth="2.5"/>
          <circle cx="24" cy="24" r={R} fill="none"
            stroke={ringColor} strokeWidth="2.5"
            strokeLinecap={isMatrix || isCyber ? 'butt' : 'round'}
            strokeDasharray={`${c * pct} ${c}`}
            transform="rotate(-90 24 24)"/>
        </svg>
        <div style={{
          position: 'absolute', inset: 0,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 13, fontWeight: 700, color: ringColor,
          fontFamily: theme.fonts.body,
        }}>{count}/{total}</div>
      </div>
    </div>
  );
}

function DailyScreen({ theme }) {
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;

  const palettes = {
    CYBERPUNK: ['#FFB800', '#FF00AA', '#FF5555', '#00FF99', '#FFD23F', '#C084FC'],
    SYNTHWAVE: ['#FFB800', '#C084FC', '#FF5C8A', '#00E5A0', '#FFD23F', '#8A7BFF'],
    MATRIX:    ['#AAFF00', '#66FF99', '#FF5555', '#00FF41', '#FFFF33', '#9AFFB0'],
    VOID:      ['#D8B673', '#C8B8FF', '#E8A0A0', '#9EB8A8', '#D6C99C', '#B0A8D8'],
  };
  const rings = palettes[theme.id];

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', position: 'relative' }}>
      <div style={{ padding: '4px 20px 0' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: 6 }}>
          <div style={{ fontSize: 10, letterSpacing: 2, color: theme.colors.muted, fontFamily: theme.fonts.body, textTransform: up, whiteSpace: 'nowrap' }}>
            {isMatrix && '◉ daily --today'}
            {isCyber && '// DAILY.FEED'}
            {isSynth && '◆ DAILY'}
            {isVoid && 'Today\u2019s rituals'}
          </div>
          <Icon name="settings" size={22} color={theme.colors.onSurface}/>
        </div>
        <div style={{
          marginTop: isVoid ? 28 : 22, marginBottom: 6,
          fontFamily: theme.fonts.display,
          fontSize: isVoid ? 48 : 32, fontWeight: isVoid ? 500 : 700,
          color: theme.colors.onBackground,
          textTransform: up, letterSpacing: theme.displayTracking,
          lineHeight: 1,
          ...(isSynth ? { textShadow: `0 0 22px ${theme.colors.primary}70` } : {}),
          ...(isCyber ? { textShadow: `0 0 8px ${theme.colors.primary}70` } : {}),
        }}>
          {isMatrix ? 'DAILY' : isVoid ? <span>Daily<span style={{ color: theme.colors.primary }}>.</span></span> : 'Daily'}
        </div>
        <div style={{
          fontSize: isVoid ? 12 : 13, color: theme.colors.muted,
          fontFamily: theme.fonts.body, letterSpacing: isVoid ? 1.5 : 0.3,
          textTransform: isVoid ? 'uppercase' : 'none', marginBottom: 14, whiteSpace: 'nowrap',
        }}>{isMatrix ? '// 2/22 complete \u00b7 streak 4d' : '2 of 22 complete \u00b7 4-day streak'}</div>
      </div>

      <div className="no-scrollbar" style={{ flex: 1, overflow: 'auto', padding: isVoid ? '6px 20px 90px' : '4px 18px 90px' }}>
        <HabitCard theme={theme} icon="sun" name="Morning Routine"
          subtitle="Survival" subtitleColor={rings[0]}
          count={0} total={3} ringColor={rings[0]}/>
        <HabitCard theme={theme} icon="moon" name="Bedtime Routine"
          subtitle="Solid" subtitleColor={rings[1]}
          count={0} total={5} ringColor={rings[1]}/>
        <HabitCard theme={theme} icon="pill" name="Medication"
          count={2} total={4} ringColor={rings[2]}/>
        <HabitCard theme={theme} icon="home" name="Housework"
          subtitle="Regular" subtitleColor={rings[3]}
          count={0} total={6} ringColor={rings[3]}/>
        <HabitCard theme={theme} icon="grad" name="Schoolwork"
          count={0} total={1} ringColor={rings[4]}/>
        <HabitCard theme={theme} icon="music" name="Leisure"
          count={0} total={3} ringColor={rings[5]}/>
      </div>

      <div style={{
        position: 'absolute', right: 20, bottom: 24,
        width: isVoid ? 52 : 56, height: isVoid ? 52 : 56,
        borderRadius: theme.chipShape === 'sharp' ? (isMatrix ? 0 : 8) : (isVoid ? 26 : 18),
        background: theme.colors.primary,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        boxShadow: theme.glow === 'none'
          ? '0 8px 18px rgba(0,0,0,0.4)'
          : `0 10px 28px ${theme.colors.primary}40, 0 0 0 1px ${theme.colors.primary}60, 0 0 20px ${theme.colors.primary}50`,
      }}>
        <Icon name="plus" size={26} color={theme.colors.background} strokeWidth={2.5}/>
      </div>
    </div>
  );
}

window.DailyScreen = DailyScreen;

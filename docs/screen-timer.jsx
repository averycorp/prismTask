// TimerScreen — themed with per-theme signature treatments.

function Segmented({ theme, options, active }) {
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  return (
    <div style={{
      display: 'flex', padding: isMatrix ? 0 : 4,
      background: isMatrix ? 'transparent' : theme.colors.surface,
      border: `1px solid ${theme.colors.border}`,
      borderRadius: theme.chipShape === 'sharp' ? theme.radius : 999,
    }}>
      {options.map(o => {
        const on = o === active;
        return (
          <div key={o} style={{
            flex: 1, textAlign: 'center', padding: isMatrix ? '14px 0' : '10px 0',
            background: on ? (isMatrix ? `${theme.colors.primary}18` : theme.colors.surfaceVariant) : 'transparent',
            borderRadius: theme.chipShape === 'sharp' ? Math.max(0, theme.radius - 2) : 999,
            color: on ? theme.colors.onBackground : theme.colors.muted,
            fontSize: 14, fontWeight: 600,
            fontFamily: theme.fonts.body,
            textTransform: up, letterSpacing: isVoid ? 2 : theme.displayTracking,
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
            borderRight: isMatrix && o !== options[options.length - 1] ? `1px solid ${theme.colors.border}` : 'none',
          }}>
            {on && !isMatrix && <Icon name="check" size={14} color={theme.colors.onBackground} strokeWidth={2.5}/>}
            {isMatrix ? (on ? `[${o.toLowerCase()}]` : o.toLowerCase()) : o}
          </div>
        );
      })}
    </div>
  );
}

function TimerDial({ theme, minutes = 20, seconds = 0, pct = 1 }) {
  const size = 260, stroke = theme.terminal ? 6 : 10;
  const R = size/2 - stroke;
  const C = 2 * Math.PI * R;
  const mm = String(minutes).padStart(2, '0');
  const ss = String(seconds).padStart(2, '0');
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;
  const isVoid = theme.editorial;

  return (
    <div style={{ position: 'relative', width: size, height: size, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ position: 'absolute', inset: 0 }}>
        <defs>
          {isSynth && (
            <linearGradient id="synth-grad" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stopColor={theme.colors.primary}/>
              <stop offset="100%" stopColor={theme.colors.secondary}/>
            </linearGradient>
          )}
        </defs>
        {/* outer tick marks (Cyberpunk) */}
        {isCyber && [...Array(60)].map((_, i) => {
          const a = (i / 60) * 2 * Math.PI - Math.PI / 2;
          const isMajor = i % 5 === 0;
          const r1 = R + stroke/2 + 3;
          const r2 = R + stroke/2 + (isMajor ? 10 : 5);
          const x1 = size/2 + Math.cos(a) * r1;
          const y1 = size/2 + Math.sin(a) * r1;
          const x2 = size/2 + Math.cos(a) * r2;
          const y2 = size/2 + Math.sin(a) * r2;
          return <line key={i} x1={x1} y1={y1} x2={x2} y2={y2}
            stroke={theme.colors.primary}
            strokeOpacity={isMajor ? 0.7 : 0.25}
            strokeWidth={isMajor ? 1.3 : 0.8}/>;
        })}
        <circle cx={size/2} cy={size/2} r={R} fill="none"
          stroke={theme.colors.border} strokeWidth={stroke}
          {...(isMatrix ? { strokeDasharray: '3 4' } : {})}/>
        <circle cx={size/2} cy={size/2} r={R} fill="none"
          stroke={isSynth ? 'url(#synth-grad)' : theme.colors.primary}
          strokeWidth={stroke}
          strokeLinecap={isMatrix || isCyber ? 'butt' : 'round'}
          strokeDasharray={`${C*pct} ${C}`}
          transform={`rotate(-90 ${size/2} ${size/2})`}
          style={theme.glow === 'none' ? {} : { filter: `drop-shadow(0 0 ${isSynth ? 18 : 14}px ${theme.colors.primary}90)` }}/>
      </svg>
      <div style={{ textAlign: 'center', position: 'relative', zIndex: 1 }}>
        <div style={{
          fontSize: isVoid ? 11 : 14, fontWeight: 500,
          color: theme.colors.onSurface,
          letterSpacing: isVoid ? 3 : theme.displayTracking,
          textTransform: up, marginBottom: isVoid ? 10 : 4,
          fontFamily: theme.fonts.body,
        }}>{isMatrix ? '$ focus --work' : 'Focus'}</div>
        <div style={{
          fontFamily: theme.fonts.display,
          fontSize: isVoid ? 72 : 66, fontWeight: isVoid ? 500 : 700,
          color: theme.colors.onBackground,
          letterSpacing: theme.displayTracking,
          lineHeight: 1,
          ...(isSynth ? { textShadow: `0 0 20px ${theme.colors.primary}90, 0 0 40px ${theme.colors.primary}50` } : {}),
          ...(isCyber ? { textShadow: `0 0 10px ${theme.colors.primary}80` } : {}),
          ...(isMatrix ? { color: theme.colors.primary } : {}),
        }}>{mm}<span style={{ opacity: isVoid ? 0.3 : 1 }}>:</span>{ss}</div>
        {isMatrix && (
          <div style={{ marginTop: 8, fontSize: 11, color: theme.colors.muted, opacity: 0.8 }}>
            // 1200s remaining
          </div>
        )}
      </div>
    </div>
  );
}

function TimerScreen({ theme }) {
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;
  const isVoid = theme.editorial;

  return (
    <div style={{ padding: '4px 20px 20px', height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: 6 }}>
        <div style={{ fontSize: 10, letterSpacing: 2, color: theme.colors.muted, fontFamily: theme.fonts.body, textTransform: up }}>
          {isMatrix && '◉ pomodoro.sh'}
          {isCyber && '// FOCUS.CORE'}
          {isSynth && '◆ POMODORO'}
          {isVoid && 'Focus Timer'}
        </div>
        <Icon name="settings" size={22} color={theme.colors.onSurface}/>
      </div>
      <div style={{
        marginTop: isVoid ? 28 : 22,
        fontFamily: theme.fonts.display,
        fontSize: isVoid ? 48 : 32, fontWeight: isVoid ? 500 : 700,
        color: theme.colors.onBackground,
        textTransform: up, letterSpacing: theme.displayTracking,
        lineHeight: 1,
        ...(isSynth ? { textShadow: `0 0 22px ${theme.colors.primary}70` } : {}),
        ...(isCyber ? { textShadow: `0 0 8px ${theme.colors.primary}70` } : {}),
      }}>
        {isMatrix ? 'TIMER' : isVoid ? <span>Timer<span style={{ color: theme.colors.primary }}>.</span></span> : 'Timer'}
      </div>

      <div style={{ marginTop: isVoid ? 36 : 28 }}>
        <Segmented theme={theme} options={['Work', 'Break']} active="Work"/>
      </div>

      <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', marginTop: 10, position: 'relative' }}>
        <TimerDial theme={theme} minutes={20} seconds={0} pct={1}/>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 16, paddingBottom: 20 }}>
        {/* reset */}
        <div style={{
          width: 52, height: 52,
          borderRadius: theme.chipShape === 'sharp' ? (isMatrix ? 0 : 10) : 26,
          background: theme.colors.surfaceVariant,
          border: `1px solid ${theme.colors.border}`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: theme.colors.onSurface,
        }}>
          <Icon name="reset" size={22} color={theme.colors.onSurface}/>
        </div>
        {/* start */}
        <div style={{
          flex: 1, maxWidth: 220, height: 56,
          borderRadius: theme.chipShape === 'sharp' ? (isMatrix ? 0 : 10) : 28,
          background: isMatrix ? 'transparent' : theme.colors.primary,
          border: isMatrix ? `1px solid ${theme.colors.primary}` : 'none',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          gap: 10,
          color: isMatrix ? theme.colors.primary : theme.colors.background,
          fontWeight: 700, fontSize: 17,
          textTransform: up, letterSpacing: isVoid ? 2.5 : theme.displayTracking,
          fontFamily: theme.fonts.display,
          boxShadow: theme.glow === 'none'
            ? `0 6px 14px rgba(0,0,0,0.35)`
            : `0 10px 28px ${theme.colors.primary}40${isSynth ? `, 0 0 40px ${theme.colors.primary}40` : ''}`,
        }}>
          {!isMatrix && <Icon name="play" size={18} color={theme.colors.background} strokeWidth={2.2}/>}
          {isMatrix ? (
            <>
              <span style={{ opacity: 0.6 }}>$</span> start
              <span style={{ marginLeft: 2, animation: 'blink 1s steps(2) infinite', background: theme.colors.primary, color: theme.colors.background, padding: '0 2px', fontSize: 14 }}>_</span>
            </>
          ) : 'Start'}
        </div>
      </div>
    </div>
  );
}

window.TimerScreen = TimerScreen;

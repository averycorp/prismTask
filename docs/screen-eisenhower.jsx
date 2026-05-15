// EisenhowerScreen — 2×2 matrix of urgency × importance.
// Uses colors.quadrantQ1..Q4 tokens to paint each quadrant in a
// theme-native way. Q1 demands attention, Q2 is signature/focus,
// Q3 is caution, Q4 recedes.

function QuadCard({ theme, tint, label, subLabel, tasks, corner }) {
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;
  const up = theme.displayUpper ? 'uppercase' : 'none';

  // Translucent tint-fill for the quadrant card.
  const fill = isVoid ? `${tint}12` : `${tint}18`;
  const stroke = isVoid ? `${tint}50` : `${tint}70`;

  return (
    <div style={{
      position: 'relative',
      background: theme.colors.surface,
      border: `1px solid ${stroke}`,
      borderRadius: theme.cardRadius,
      padding: isVoid ? 14 : 12,
      minHeight: 150,
      overflow: 'hidden',
      ...(isCyber ? { boxShadow: `inset 0 0 0 1px ${tint}22, 0 0 12px ${tint}25` } : {}),
      ...(isSynth ? { boxShadow: `0 8px 22px ${tint}30, inset 0 1px 0 ${tint}30` } : {}),
    }}>
      {/* Tint wash */}
      <div style={{
        position: 'absolute', inset: 0,
        background: isSynth
          ? `radial-gradient(circle at 85% 0%, ${tint}35, transparent 70%)`
          : fill,
        pointerEvents: 'none',
      }}/>
      {/* Cyberpunk corner bracket */}
      {isCyber && corner && (
        <div style={{
          position: 'absolute', ...corner, width: 12, height: 12,
          borderTop: corner.top != null ? `2px solid ${tint}` : 'none',
          borderBottom: corner.bottom != null ? `2px solid ${tint}` : 'none',
          borderLeft: corner.left != null ? `2px solid ${tint}` : 'none',
          borderRight: corner.right != null ? `2px solid ${tint}` : 'none',
        }}/>
      )}
      <div style={{ position: 'relative', zIndex: 1, display: 'flex', flexDirection: 'column', height: '100%' }}>
        <div style={{
          display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
          gap: 6, marginBottom: 2,
        }}>
          <div style={{
            fontFamily: theme.fonts.display,
            fontSize: isVoid ? 22 : 18,
            fontWeight: isVoid ? 500 : 700,
            color: tint, letterSpacing: theme.displayTracking,
            textTransform: up,
            ...(theme.glow !== 'none' && !isVoid ? { textShadow: `0 0 10px ${tint}70` } : {}),
          }}>
            {isMatrix ? `[${label}]` : label}
          </div>
          <div style={{
            fontFamily: theme.fonts.mono, fontSize: 10,
            color: tint, opacity: 0.7, letterSpacing: 1.2,
          }}>{tasks.length}</div>
        </div>
        <div style={{
          fontSize: isVoid ? 9 : 10,
          color: theme.colors.muted, fontFamily: theme.fonts.body,
          letterSpacing: isVoid ? 1.8 : 1, textTransform: 'uppercase',
          marginBottom: isVoid ? 12 : 10,
        }}>{subLabel}</div>

        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: isVoid ? 8 : 6 }}>
          {tasks.map((t, i) => (
            <div key={i} style={{
              display: 'flex', alignItems: 'flex-start', gap: 8,
              fontSize: isVoid ? 11 : 12,
              color: theme.colors.onBackground,
              fontFamily: theme.fonts.body,
              lineHeight: 1.3,
            }}>
              <span style={{
                flexShrink: 0, marginTop: 4,
                width: isMatrix ? 'auto' : 5, height: isMatrix ? 'auto' : 5,
                borderRadius: isVoid ? '50%' : 0,
                background: isMatrix ? 'transparent' : tint,
                color: tint,
                fontFamily: theme.fonts.mono, fontSize: 10,
              }}>{isMatrix ? '>' : ''}</span>
              <span style={{ flex: 1 }}>{t}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function EisenhowerScreen({ theme }) {
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;

  const q1 = theme.colors.quadrantQ1;
  const q2 = theme.colors.quadrantQ2;
  const q3 = theme.colors.quadrantQ3;
  const q4 = theme.colors.quadrantQ4;

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', padding: '4px 20px 90px', overflow: 'auto' }} className="no-scrollbar">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: 6 }}>
        <div style={{ fontSize: 10, letterSpacing: 2, color: theme.colors.muted, fontFamily: theme.fonts.body, textTransform: up, whiteSpace: 'nowrap' }}>
          {isMatrix && '◉ priority --matrix'}
          {isCyber && '// MATRIX.PRIO'}
          {isSynth && '◆ MATRIX'}
          {isVoid && 'Prioritize'}
        </div>
        <Icon name="grid4" size={20} color={theme.colors.onSurface}/>
      </div>
      <div style={{
        marginTop: isVoid ? 24 : 18, marginBottom: 4,
        fontFamily: theme.fonts.display,
        fontSize: isVoid ? 40 : 28, fontWeight: isVoid ? 500 : 700,
        color: theme.colors.onBackground,
        textTransform: up, letterSpacing: theme.displayTracking,
        lineHeight: 1,
        ...(isSynth ? { textShadow: `0 0 22px ${theme.colors.primary}70` } : {}),
        ...(isCyber ? { textShadow: `0 0 8px ${theme.colors.primary}70` } : {}),
      }}>
        {isMatrix ? 'MATRIX' : isVoid ? <span>Matrix<span style={{ color: theme.colors.primary }}>.</span></span> : 'Eisenhower'}
      </div>
      <div style={{
        fontSize: isVoid ? 12 : 13, color: theme.colors.muted,
        fontFamily: theme.fonts.body, letterSpacing: isVoid ? 1.5 : 0.3,
        textTransform: isVoid ? 'uppercase' : 'none',
        marginBottom: 18,
      }}>
        {isMatrix ? '// urgency × importance' : '18 tasks · sorted by urgency × importance'}
      </div>

      {/* Legend axis labels */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: '1fr 1fr',
        gap: 6, marginBottom: 6,
        fontSize: 9, color: theme.colors.muted,
        fontFamily: theme.fonts.body, letterSpacing: 1.8, textTransform: 'uppercase',
      }}>
        <div style={{ textAlign: 'center' }}>Urgent</div>
        <div style={{ textAlign: 'center' }}>Not Urgent</div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '14px 1fr 1fr', gridTemplateRows: '1fr 1fr', gap: 10, flex: 1 }}>
        {/* Left-side "Important" label */}
        <div style={{
          writingMode: 'vertical-rl', transform: 'rotate(180deg)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 9, color: theme.colors.muted,
          fontFamily: theme.fonts.body, letterSpacing: 1.8, textTransform: 'uppercase',
          gridRow: '1',
        }}>Important</div>
        <QuadCard theme={theme} tint={q1} label="Do" subLabel="Crisis · Deadlines"
          corner={{ top: 8, left: 8 }}
          tasks={['Fix production payment bug', 'File tax extension today', 'Call hospital re: bill']}/>
        <QuadCard theme={theme} tint={q2} label="Plan" subLabel="Growth · Strategy"
          corner={{ top: 8, right: 8 }}
          tasks={['Q3 roadmap draft', 'Mentor onboarding doc', 'Strength training plan']}/>

        <div style={{
          writingMode: 'vertical-rl', transform: 'rotate(180deg)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 9, color: theme.colors.muted,
          fontFamily: theme.fonts.body, letterSpacing: 1.8, textTransform: 'uppercase',
          gridRow: '2',
        }}>Not Important</div>
        <QuadCard theme={theme} tint={q3} label="Delegate" subLabel="Noise · Interruptions"
          corner={{ bottom: 8, left: 8 }}
          tasks={['Reply to expense ping', 'Review PR #482', 'Schedule team lunch']}/>
        <QuadCard theme={theme} tint={q4} label="Drop" subLabel="Distractions"
          corner={{ bottom: 8, right: 8 }}
          tasks={['Doomscroll news feed', 'Reorganize Notion sidebar', 'Watch product demo #7']}/>
      </div>
    </div>
  );
}

window.EisenhowerScreen = EisenhowerScreen;

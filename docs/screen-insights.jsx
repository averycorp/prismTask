// InsightsScreen — Analytics dashboard demonstrating the
// colors.dataVisualizationPalette (8-color categorical array) across
// bar, donut, streak heatmap, and category legend.

function DonutChart({ theme, size = 160, stroke = 22, segments }) {
  // segments: [{ value, color, label }]
  const cx = size / 2;
  const cy = size / 2;
  const r = (size - stroke) / 2;
  const total = segments.reduce((s, x) => s + x.value, 0);
  const C = 2 * Math.PI * r;
  let offset = 0;
  const arcs = segments.map((seg, i) => {
    const frac = seg.value / total;
    const len = C * frac;
    const dashArray = `${len} ${C}`;
    const dashOffset = -offset;
    offset += len;
    return <circle key={i} cx={cx} cy={cy} r={r} fill="none"
      stroke={seg.color} strokeWidth={stroke}
      strokeDasharray={dashArray}
      strokeDashoffset={dashOffset}
      transform={`rotate(-90 ${cx} ${cy})`}
      style={theme.glow !== 'none' ? { filter: `drop-shadow(0 0 6px ${seg.color}80)` } : {}}
    />;
  });
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
      <circle cx={cx} cy={cy} r={r} fill="none" stroke={theme.colors.surfaceVariant} strokeWidth={stroke}/>
      {arcs}
    </svg>
  );
}

function BarRow({ theme, label, value, max, color }) {
  const pct = Math.max(4, Math.round((value / max) * 100));
  const isVoid = theme.editorial;
  const isMatrix = theme.terminal;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '74px 1fr 42px', alignItems: 'center', gap: 10, marginBottom: isVoid ? 10 : 7 }}>
      <div style={{
        fontSize: isVoid ? 11 : 11,
        color: theme.colors.onSurface, fontFamily: theme.fonts.body,
        letterSpacing: isVoid ? 1.6 : 0.3,
        textTransform: isVoid ? 'uppercase' : 'none',
        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
      }}>{label}</div>
      <div style={{
        height: isVoid ? 8 : (isMatrix ? 14 : 10),
        background: theme.colors.surfaceVariant,
        borderRadius: theme.chipShape === 'sharp' ? 0 : 6,
        overflow: 'hidden', position: 'relative',
      }}>
        <div style={{
          position: 'absolute', inset: 0, width: `${pct}%`,
          background: color,
          borderRadius: theme.chipShape === 'sharp' ? 0 : 6,
          ...(theme.glow !== 'none' ? { boxShadow: `0 0 10px ${color}90` } : {}),
          ...(isMatrix ? {
            backgroundImage: `repeating-linear-gradient(90deg, ${color} 0 6px, ${color}88 6px 8px)`,
          } : {}),
        }}/>
      </div>
      <div style={{
        fontSize: 11, color: theme.colors.onBackground,
        fontFamily: theme.fonts.mono, textAlign: 'right',
      }}>{value}h</div>
    </div>
  );
}

function StreakHeatmap({ theme, palette }) {
  // 7 rows × 15 cols grid. Density mapped to palette brightness.
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const rows = 7;
  const cols = 15;
  // deterministic pseudo-random
  const seed = (r, c) => ((r * 31 + c * 17 + 7) * 9301) % 233280 / 233280;
  const cells = [];
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      const s = seed(r, c);
      let color, alpha;
      if (s < 0.35) { color = theme.colors.surfaceVariant; alpha = 1; }
      else if (s < 0.6) { color = palette[0]; alpha = 0.25; }
      else if (s < 0.82) { color = palette[0]; alpha = 0.55; }
      else { color = palette[0]; alpha = 0.92; }
      cells.push(
        <div key={`${r}-${c}`} style={{
          background: color, opacity: alpha,
          borderRadius: theme.chipShape === 'sharp' ? (isVoid ? 2 : 0) : 3,
          aspectRatio: '1 / 1',
          ...(isMatrix && s >= 0.35 ? { border: `1px solid ${palette[0]}40` } : {}),
        }}/>
      );
    }
  }
  return (
    <div style={{
      display: 'grid',
      gridTemplateColumns: `repeat(${cols}, 1fr)`,
      gap: isVoid ? 4 : 3,
    }}>{cells}</div>
  );
}

function InsightsScreen({ theme }) {
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;
  const palette = theme.colors.dataVisualizationPalette;

  const categories = [
    { label: 'Deep work',  value: 14.5, color: palette[0] },
    { label: 'Meetings',   value: 8.2,  color: palette[1] },
    { label: 'Planning',   value: 5.4,  color: palette[2] },
    { label: 'Health',     value: 4.1,  color: palette[3] },
    { label: 'Learning',   value: 3.3,  color: palette[4] },
    { label: 'Admin',      value: 2.0,  color: palette[5] },
    { label: 'Creative',   value: 1.8,  color: palette[6] },
    { label: 'Other',      value: 1.1,  color: palette[7] },
  ];
  const max = Math.max(...categories.map(c => c.value));

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', padding: '4px 20px 90px', overflow: 'auto' }} className="no-scrollbar">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: 6 }}>
        <div style={{ fontSize: 10, letterSpacing: 2, color: theme.colors.muted, fontFamily: theme.fonts.body, textTransform: up, whiteSpace: 'nowrap' }}>
          {isMatrix && '◉ insights --week'}
          {isCyber && '// INSIGHTS.WEEK'}
          {isSynth && '◆ INSIGHTS'}
          {isVoid && 'Insights'}
        </div>
        <Icon name="chart" size={20} color={theme.colors.onSurface}/>
      </div>

      <div style={{
        marginTop: isVoid ? 22 : 16, marginBottom: 2,
        fontFamily: theme.fonts.display,
        fontSize: isVoid ? 38 : 26, fontWeight: isVoid ? 500 : 700,
        color: theme.colors.onBackground,
        textTransform: up, letterSpacing: theme.displayTracking,
        lineHeight: 1,
        ...(isSynth ? { textShadow: `0 0 22px ${theme.colors.primary}70` } : {}),
        ...(isCyber ? { textShadow: `0 0 8px ${theme.colors.primary}70` } : {}),
      }}>
        {isMatrix ? 'INSIGHTS' : isVoid ? <span>This week<span style={{ color: theme.colors.primary }}>.</span></span> : 'This week'}
      </div>
      <div style={{
        fontSize: isVoid ? 12 : 13, color: theme.colors.muted,
        fontFamily: theme.fonts.body, letterSpacing: isVoid ? 1.5 : 0.3,
        textTransform: isVoid ? 'uppercase' : 'none',
        marginBottom: 16,
      }}>
        {isMatrix ? '// 40.4 hrs tracked · 8 categories' : '40.4 hrs tracked · 8 categories'}
      </div>

      {/* Donut + totals */}
      <div style={{
        background: theme.colors.surface,
        border: `1px solid ${theme.colors.border}`,
        borderRadius: theme.cardRadius,
        padding: isVoid ? 18 : 14,
        display: 'flex', alignItems: 'center', gap: 14,
        marginBottom: 14,
      }}>
        <div style={{ position: 'relative', flexShrink: 0 }}>
          <DonutChart theme={theme} size={140} stroke={20} segments={categories}/>
          <div style={{
            position: 'absolute', inset: 0,
            display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
          }}>
            <div style={{
              fontFamily: theme.fonts.display,
              fontSize: isVoid ? 28 : 26, fontWeight: isVoid ? 500 : 700,
              color: theme.colors.onBackground, letterSpacing: theme.displayTracking,
            }}>40.4</div>
            <div style={{
              fontSize: 9, color: theme.colors.muted,
              letterSpacing: 1.8, textTransform: 'uppercase',
              fontFamily: theme.fonts.body,
            }}>Hours</div>
          </div>
        </div>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 4 }}>
          {categories.slice(0, 4).map((c, i) => (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11, fontFamily: theme.fonts.body, color: theme.colors.onSurface }}>
              <span style={{
                width: 9, height: 9,
                background: c.color,
                borderRadius: theme.chipShape === 'sharp' ? 0 : '50%',
                ...(theme.glow !== 'none' ? { boxShadow: `0 0 6px ${c.color}90` } : {}),
              }}/>
              <span style={{ flex: 1, letterSpacing: isVoid ? 1.4 : 0.2, textTransform: isVoid ? 'uppercase' : 'none' }}>{c.label}</span>
              <span style={{ color: theme.colors.onBackground, fontFamily: theme.fonts.mono }}>{Math.round(c.value / 40.4 * 100)}%</span>
            </div>
          ))}
        </div>
      </div>

      {/* Bars */}
      <div style={{
        background: theme.colors.surface,
        border: `1px solid ${theme.colors.border}`,
        borderRadius: theme.cardRadius,
        padding: isVoid ? 18 : 14,
        marginBottom: 14,
      }}>
        <div style={{
          fontSize: isVoid ? 10 : 11,
          color: theme.colors.onSurface, letterSpacing: isVoid ? 2 : 1.4,
          fontFamily: theme.fonts.body, textTransform: 'uppercase',
          marginBottom: 12,
        }}>
          {isMatrix ? '# by_category' : 'By category'}
        </div>
        {categories.map((c, i) => (
          <BarRow key={i} theme={theme} label={c.label} value={c.value} max={max} color={c.color}/>
        ))}
      </div>

      {/* Heatmap */}
      <div style={{
        background: theme.colors.surface,
        border: `1px solid ${theme.colors.border}`,
        borderRadius: theme.cardRadius,
        padding: isVoid ? 18 : 14,
      }}>
        <div style={{
          display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
          marginBottom: 12,
        }}>
          <div style={{
            fontSize: isVoid ? 10 : 11,
            color: theme.colors.onSurface, letterSpacing: isVoid ? 2 : 1.4,
            fontFamily: theme.fonts.body, textTransform: 'uppercase',
          }}>
            {isMatrix ? '# streak_heatmap' : 'Streak · 105 days'}
          </div>
          <div style={{
            fontFamily: theme.fonts.mono, fontSize: 11,
            color: palette[0],
          }}>
            {isMatrix ? '> 82%' : '82% avg'}
          </div>
        </div>
        <StreakHeatmap theme={theme} palette={palette}/>
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 6,
          marginTop: 10,
          fontSize: 9, color: theme.colors.muted,
          fontFamily: theme.fonts.body, letterSpacing: 1.4, textTransform: 'uppercase',
        }}>
          Less
          {[0.2, 0.45, 0.72, 0.95].map((a, i) => (
            <span key={i} style={{
              width: 10, height: 10,
              background: palette[0], opacity: a,
              borderRadius: theme.chipShape === 'sharp' ? 2 : 2,
            }}/>
          ))}
          More
        </div>
      </div>
    </div>
  );
}

window.InsightsScreen = InsightsScreen;

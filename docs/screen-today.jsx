// TodayScreen — themed recreation with strong per-theme signature treatments.

function ProgressRing({ theme, done = 5, total = 16, size = 190 }) {
  const r = size / 2 - 10;
  const C = 2 * Math.PI * r;
  const pct = done / total;
  const dash = C * pct;
  const numFont = theme.editorial ? theme.fonts.display : theme.fonts.display;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={theme.glow === 'none' ? {} : { filter: `drop-shadow(0 0 14px ${theme.colors.primary}60)` }}>
      <circle cx={size/2} cy={size/2} r={r} fill="none"
        stroke={theme.colors.border} strokeWidth="8"/>
      <circle cx={size/2} cy={size/2} r={r} fill="none"
        stroke={theme.colors.primary} strokeWidth={theme.terminal ? 6 : 8}
        strokeLinecap={theme.terminal || theme.brackets ? 'butt' : 'round'}
        strokeDasharray={`${dash} ${C}`}
        transform={`rotate(-90 ${size/2} ${size/2})`}/>
      {theme.brackets && (
        // tick marks around Cyberpunk ring
        [...Array(12)].map((_, i) => {
          const a = (i / 12) * 2 * Math.PI;
          const x1 = size/2 + Math.cos(a) * (r + 6);
          const y1 = size/2 + Math.sin(a) * (r + 6);
          const x2 = size/2 + Math.cos(a) * (r + 10);
          const y2 = size/2 + Math.sin(a) * (r + 10);
          return <line key={i} x1={x1} y1={y1} x2={x2} y2={y2} stroke={theme.colors.primary} strokeOpacity="0.4" strokeWidth="1"/>;
        })
      )}
      <text x="50%" y="52%" textAnchor="middle" dominantBaseline="middle"
        fontFamily={numFont}
        fontSize={theme.editorial ? 52 : 44}
        fontWeight={theme.editorial ? 500 : 600}
        fill={theme.colors.primary}
        letterSpacing={theme.displayTracking}>
        {theme.editorial ? `${done}/${total}` : `${done}/${total}`}
      </text>
    </svg>
  );
}

function HabitRow({ theme, icon, iconBg, iconColor, name }) {
  const terminal = theme.terminal;
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 14,
      padding: terminal ? '12px 14px' : '14px 16px',
      background: theme.colors.surface,
      border: `1px solid ${theme.colors.border}`,
      borderRadius: theme.cardRadius,
      marginBottom: theme.editorial ? 14 : 10,
      position: 'relative',
    }}>
      {terminal && <span style={{ color: theme.colors.primary, opacity: 0.6, fontSize: 14 }}>{'▸'}</span>}
      <div style={{
        width: 36, height: 36,
        borderRadius: theme.chipShape === 'sharp' ? 4 : 10,
        background: iconBg, color: iconColor,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
        border: theme.brackets ? `1px solid ${iconColor}40` : 'none',
      }}>
        <Icon name={icon} size={20} color={iconColor}/>
      </div>
      <div style={{ flex: 1, fontSize: 16, fontWeight: 500, color: theme.colors.onBackground, letterSpacing: 0.1,
        fontFamily: theme.editorial ? theme.fonts.display : theme.fonts.body,
      }}>{name}</div>
      <div style={{
        width: 22, height: 22,
        border: `1.5px solid ${theme.colors.muted}`,
        borderRadius: theme.chipShape === 'sharp' ? 3 : 5,
        ...(terminal ? { borderStyle: 'dashed' } : {}),
      }}/>
    </div>
  );
}

function TodayScreen({ theme }) {
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;
  const isVoid = theme.editorial;

  return (
    <div className="no-scrollbar" style={{ padding: '4px 20px 20px', height: '100%', overflow: 'auto' }}>
      {/* Top bar — gear + tiny theme-specific id */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: 6 }}>
        <div style={{ fontSize: 10, letterSpacing: 2, color: theme.colors.muted, fontFamily: theme.fonts.body,
          textTransform: up,
        }}>
          {isMatrix && '◉ prismtask ~ $'}
          {isCyber && '// PRISMTASK.SYS'}
          {isSynth && '◆ PRISMTASK'}
          {isVoid && 'PrismTask — Today'}
        </div>
        <Icon name="settings" size={22} color={theme.colors.onSurface}/>
      </div>

      {/* Greeting */}
      <div style={{ marginTop: isVoid ? 32 : 22, marginBottom: isVoid ? 28 : 20 }}>
        {isMatrix && (
          <div style={{ fontSize: 13, color: theme.colors.primary, opacity: 0.7, marginBottom: 6 }}>
            <span style={{ opacity: 0.5 }}>$</span> whoami<span style={{ animation: 'none' }}>_</span>
          </div>
        )}
        {isSynth && (
          <div style={{ display: 'inline-block', padding: '3px 10px', marginBottom: 10, border: `1px solid ${theme.colors.primary}70`, borderRadius: 999, fontSize: 10, letterSpacing: 2, textTransform: 'uppercase', color: theme.colors.primary, background: `${theme.colors.primary}15`, whiteSpace: 'nowrap',
          }}>Evening Session</div>
        )}
        <div style={{
          fontFamily: theme.fonts.display,
          fontSize: isVoid ? 46 : isSynth ? 40 : 34,
          fontWeight: isVoid ? 500 : 700,
          lineHeight: isVoid ? 1.0 : 1.05,
          color: theme.colors.onBackground,
          textTransform: up,
          letterSpacing: theme.displayTracking,
          ...(isSynth ? { textShadow: `0 0 24px ${theme.colors.primary}70` } : {}),
          ...(isCyber ? { textShadow: `0 0 8px ${theme.colors.primary}80` } : {}),
        }}>
          {isMatrix ? 'GOOD.EVENING' : isVoid ? <span>Good <em style={{ fontStyle: 'italic', color: theme.colors.primary }}>evening</em></span> : 'Good Evening'}
        </div>
        <div style={{
          marginTop: isVoid ? 10 : 6, fontSize: isVoid ? 13 : 14,
          color: theme.colors.onSurface, letterSpacing: isVoid ? 0.8 : 0.2,
          fontFamily: theme.fonts.body,
          ...(isVoid ? { textTransform: 'uppercase' } : {}),
          ...(isMatrix ? { opacity: 0.7 } : {}),
        }}>
          {isMatrix ? '// WED 2025-04-08 · 20:41 LOCAL' : 'Wednesday, Apr 8'}
        </div>
      </div>

      {/* Quick Add */}
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        gap: 8, padding: isVoid ? '14px 0' : '10px 0',
        marginBottom: 16,
        border: isCyber ? `1px dashed ${theme.colors.primary}60` : isMatrix ? `1px solid ${theme.colors.primary}50` : 'none',
        borderLeft: isVoid ? `2px solid ${theme.colors.primary}` : undefined,
        borderRight: isVoid ? `2px solid ${theme.colors.primary}` : undefined,
        borderTop: isVoid ? 'none' : undefined,
        borderBottom: isVoid ? 'none' : undefined,
        borderRadius: theme.radius,
        background: isSynth ? `linear-gradient(90deg, ${theme.colors.primary}18, ${theme.colors.secondary}18)` : 'transparent',
      }}>
        {isMatrix ? (
          <span style={{ color: theme.colors.primary, fontFamily: theme.fonts.body, fontSize: 15, letterSpacing: 0.2, whiteSpace: 'nowrap' }}>
            <span style={{ opacity: 0.5 }}>$</span> task -n
            <span style={{ marginLeft: 4, animation: 'blink 1s steps(2) infinite', background: theme.colors.primary, color: theme.colors.background, padding: '0 2px' }}>_</span>
          </span>
        ) : (
          <>
            <Icon name="plus" size={18} color={theme.colors.primary}/>
            <span style={{
              color: theme.colors.primary, fontSize: 16, fontWeight: 600,
              fontFamily: theme.fonts.body,
              textTransform: up, letterSpacing: isSynth ? 1.5 : isCyber ? 1.2 : 0.4,
              whiteSpace: 'nowrap',
              ...(isSynth ? { textShadow: `0 0 10px ${theme.colors.primary}90` } : {}),
            }}>Quick Add</span>
          </>
        )}
      </div>

      {/* Progress card */}
      <ThemedCard theme={theme} glowing padding={isVoid ? '32px 20px 28px' : '26px 20px 22px'} style={{
        display: 'flex', flexDirection: 'column', alignItems: 'center',
        marginBottom: 26,
      }}>
        {isMatrix && (
          <div style={{
            position: 'absolute', top: 6, left: 10,
            fontSize: 10, letterSpacing: 1.4, color: theme.colors.primary, opacity: 0.7,
            textTransform: 'uppercase',
          }}>[ progress.log ]</div>
        )}
        <ProgressRing theme={theme}/>
        <div style={{
          marginTop: isVoid ? 16 : 10, fontSize: isVoid ? 13 : 15,
          fontWeight: isVoid ? 400 : 600,
          color: theme.colors.onBackground,
          fontFamily: theme.fonts.body,
          textTransform: up, letterSpacing: isVoid ? 2.2 : 0.4,
          whiteSpace: 'nowrap',
        }}>
          {isMatrix ? 'TODAY.PROGRESS' : `Today's Progress`}
        </div>
        <div style={{ marginTop: 4, fontSize: 12, color: theme.colors.muted, fontFamily: theme.fonts.body, whiteSpace: 'nowrap' }}>
          {isMatrix ? '0 tasks · 6 habits pending' : '0 tasks · 6 habits remaining'}
        </div>
      </ThemedCard>

      {/* Daily habits header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <div style={{
          fontFamily: theme.fonts.body,
          fontSize: isVoid ? 11 : 16,
          fontWeight: isVoid ? 600 : 700,
          color: theme.colors.urgentAccent,
          textTransform: up, letterSpacing: isVoid ? 2.2 : 0.5,
          whiteSpace: 'nowrap',
          display: 'flex', alignItems: 'center', gap: 10,
        }}>
          {isVoid && <span style={{ width: 24, height: 1, background: theme.colors.urgentAccent, display: 'inline-block' }}/>}
          {isMatrix ? '# habits.daily' : 'Daily Habits'}
          <span style={{ color: theme.colors.muted, fontWeight: 400 }}>{isMatrix ? '[6]' : '6'}</span>
        </div>
        <div style={{ fontSize: 12, color: theme.colors.onSurface, letterSpacing: 0.3, textTransform: up, fontFamily: theme.fonts.body }}>
          {isMatrix ? '[hide]' : 'Hide'}
        </div>
      </div>

      <HabitRow theme={theme} icon="grad" name="School"
        iconBg={theme.colors.tagSurface} iconColor={theme.colors.tagText}/>
      <HabitRow theme={theme} icon="music" name="Leisure"
        iconBg={theme.colors.urgentSurface} iconColor={theme.colors.urgentAccent}/>
      <HabitRow theme={theme} icon="sun" name="Morning Self-Care"
        iconBg={theme.colors.tagSurface} iconColor={theme.colors.secondary}/>
    </div>
  );
}

window.TodayScreen = TodayScreen;

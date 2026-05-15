// RecurringScreen — weekly/monthly recurring habits (one of the 6 bottom tabs).

function RecurringRow({ theme, title, schedule, scheduleColor, streak, nextDue, tone, days }) {
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;

  return (
    <div style={{
      display: 'flex', flexDirection: 'column', gap: 10,
      padding: isVoid ? '18px 20px' : '14px 16px',
      background: theme.colors.surface,
      border: `1px solid ${theme.colors.border}`,
      borderRadius: theme.cardRadius,
      marginBottom: isVoid ? 14 : 10,
      ...(isCyber ? { borderLeft: `3px solid ${tone}` } : {}),
      ...(isSynth && theme.glow !== 'none' ? { boxShadow: `0 0 20px ${tone}15` } : {}),
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        {isMatrix && <span style={{ color: tone, fontFamily: theme.fonts.body, fontSize: 14 }}>▸</span>}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{
            fontSize: isVoid ? 17 : 16, fontWeight: isVoid ? 500 : 600,
            color: theme.colors.onBackground,
            fontFamily: isVoid ? theme.fonts.display : theme.fonts.body,
            letterSpacing: isVoid ? -0.1 : 0.1, lineHeight: 1.2,
          }}>{title}</div>
          <div style={{
            marginTop: 4, fontSize: isVoid ? 10 : 12,
            fontWeight: 500, color: scheduleColor,
            letterSpacing: isVoid ? 2 : 0.3,
            textTransform: theme.displayUpper || isVoid ? 'uppercase' : 'none',
            fontFamily: theme.fonts.body, whiteSpace: 'nowrap',
          }}>{schedule}</div>
        </div>

        <div style={{
          display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 2,
        }}>
          <div style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            fontSize: isVoid ? 11 : 13, fontWeight: 700,
            color: tone, fontFamily: theme.fonts.body,
            letterSpacing: isVoid ? 1.4 : 0,
            textTransform: isVoid ? 'uppercase' : 'none', whiteSpace: 'nowrap',
            ...(theme.glow !== 'none' ? { textShadow: `0 0 10px ${tone}80` } : {}),
          }}>
            {isMatrix ? `[${streak}]` : `\u25B2 ${streak}`}
          </div>
          <div style={{ fontSize: 10, color: theme.colors.muted, letterSpacing: 1.2, textTransform: 'uppercase', fontFamily: theme.fonts.body, whiteSpace: 'nowrap' }}>
            streak
          </div>
        </div>
      </div>

      {/* Day dots W T F S S M T */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 6, marginTop: 2 }}>
        {days.map((d, i) => {
          const on = d.state === 'done';
          const miss = d.state === 'miss';
          const isToday = d.today;
          return (
            <div key={i} style={{
              flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 5,
            }}>
              <div style={{
                fontSize: 9, letterSpacing: 1.4, textTransform: 'uppercase',
                color: isToday ? tone : theme.colors.muted,
                fontFamily: theme.fonts.body, fontWeight: isToday ? 700 : 500,
              }}>{d.label}</div>
              {isMatrix ? (
                <div style={{
                  width: 18, height: 18, border: `1px solid ${on ? tone : (miss ? theme.colors.urgentAccent + '70' : theme.colors.border)}`,
                  background: on ? tone : 'transparent',
                  color: on ? theme.colors.background : (miss ? theme.colors.urgentAccent : theme.colors.muted),
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontFamily: theme.fonts.body, fontSize: 11, fontWeight: 700,
                }}>{on ? 'x' : miss ? '.' : ''}</div>
              ) : (
                <div style={{
                  width: isVoid ? 10 : 12, height: isVoid ? 10 : 12,
                  borderRadius: isVoid ? 0 : '50%',
                  background: on ? tone : 'transparent',
                  border: `${on ? 0 : 1.5}px solid ${miss ? theme.colors.urgentAccent + '60' : theme.colors.border}`,
                  ...(on && theme.glow !== 'none' ? { boxShadow: `0 0 8px ${tone}` } : {}),
                  ...(isToday && !on ? { boxShadow: `0 0 0 2px ${tone}40` } : {}),
                }}/>
              )}
            </div>
          );
        })}
      </div>

      <div style={{
        marginTop: 6, fontSize: isVoid ? 10 : 11,
        color: theme.colors.muted, fontFamily: theme.fonts.body,
        letterSpacing: isVoid ? 1.6 : 0.3,
        textTransform: isVoid ? 'uppercase' : 'none', whiteSpace: 'nowrap',
      }}>
        {isMatrix ? `// next: ${nextDue.toLowerCase()}` : `Next: ${nextDue}`}
      </div>
    </div>
  );
}

function RecurringScreen({ theme }) {
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;

  const tones = {
    CYBERPUNK: ['#00E0FF', '#FF00AA', '#FFB800', '#00FF99'],
    SYNTHWAVE: ['#8A7BFF', '#FF5C8A', '#FFB800', '#00E5A0'],
    MATRIX:    ['#00FF41', '#AAFF00', '#66FF99', '#FFFF33'],
    VOID:      ['#D8B673', '#C8B8FF', '#E8A0A0', '#9EB8A8'],
  };
  const t = tones[theme.id];

  const mkDays = (pattern) => {
    const labels = ['W', 'T', 'F', 'S', 'S', 'M', 'T'];
    return pattern.map((state, i) => ({
      label: labels[i],
      state,
      today: i === 5,
    }));
  };

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', position: 'relative' }}>
      <div style={{ padding: '4px 20px 0' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: 6 }}>
          <div style={{ fontSize: 10, letterSpacing: 2, color: theme.colors.muted, fontFamily: theme.fonts.body, textTransform: up, whiteSpace: 'nowrap' }}>
            {isMatrix && '◉ recurring --list'}
            {isCyber && '// RECURRING.GRID'}
            {isSynth && '◆ RECURRING'}
            {isVoid && 'Weekly cadence'}
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
          {isMatrix ? 'RECURRING' : isVoid ? <span>Recurring<span style={{ color: theme.colors.primary }}>.</span></span> : 'Recurring'}
        </div>
        <div style={{
          fontSize: isVoid ? 12 : 13, color: theme.colors.muted,
          fontFamily: theme.fonts.body, letterSpacing: isVoid ? 1.5 : 0.3,
          textTransform: isVoid ? 'uppercase' : 'none', marginBottom: 14, whiteSpace: 'nowrap',
        }}>{isMatrix ? '// 4 active \u00b7 next in 1d' : '4 active \u00b7 next due in 1 day'}</div>
      </div>

      <div className="no-scrollbar" style={{ flex: 1, overflow: 'auto', padding: isVoid ? '4px 20px 90px' : '4px 18px 90px' }}>
        <RecurringRow theme={theme}
          title="Laundry"
          schedule={isMatrix ? 'every wednesday' : 'Every Wednesday'}
          scheduleColor={t[0]} streak="6w" nextDue={isMatrix ? 'wed' : 'Wed, in 1 day'} tone={t[0]}
          days={mkDays(['done','miss','done','miss','miss','done','skip'])}/>
        <RecurringRow theme={theme}
          title="Grocery shop"
          schedule={isMatrix ? 'sun, thu' : 'Sundays & Thursdays'}
          scheduleColor={t[1]} streak="12w" nextDue={isMatrix ? 'thu' : 'Thu, in 2 days'} tone={t[1]}
          days={mkDays(['miss','done','miss','done','miss','skip','done'])}/>
        <RecurringRow theme={theme}
          title="Deep clean bathroom"
          schedule={isMatrix ? 'bi-weekly' : 'Every 2 weeks'}
          scheduleColor={t[2]} streak="3mo" nextDue={isMatrix ? 'sat' : 'Sat, in 4 days'} tone={t[2]}
          days={mkDays(['skip','skip','skip','done','skip','skip','skip'])}/>
        <RecurringRow theme={theme}
          title="Water plants"
          schedule={isMatrix ? 'mon, fri' : 'Mondays & Fridays'}
          scheduleColor={t[3]} streak="21w" nextDue={isMatrix ? 'today' : 'Today, 6 PM'} tone={t[3]}
          days={mkDays(['skip','skip','done','skip','skip','done','skip'])}/>
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

window.RecurringScreen = RecurringScreen;

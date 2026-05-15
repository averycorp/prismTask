// SettingsScreen — one of the 6 bottom tabs.

function SettingsGroupHeader({ theme, label }) {
  const isVoid = theme.editorial;
  const isMatrix = theme.terminal;
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10,
      marginTop: isVoid ? 28 : 20, marginBottom: 10,
      fontSize: isVoid ? 10 : 11, fontWeight: 600,
      fontFamily: theme.fonts.body,
      letterSpacing: isVoid ? 2.4 : 1.6,
      textTransform: 'uppercase',
      color: theme.colors.onSurface,
    }}>
      {isVoid && <span style={{ width: 18, height: 1, background: theme.colors.onSurface, display: 'inline-block' }}/>}
      <span style={{ color: theme.colors.primary }}>
        {isMatrix ? `# ${label.toLowerCase()}` : label}
      </span>
      <span style={{ flex: 1, height: 1, background: theme.colors.border, marginLeft: 6 }}/>
    </div>
  );
}

function SettingsRow({ theme, icon, iconColor, label, value, control, first, last, danger }) {
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 14,
      padding: isVoid ? '16px 0' : '14px 16px',
      background: isVoid ? 'transparent' : theme.colors.surface,
      borderTop: isVoid ? (first ? `1px solid ${theme.colors.border}` : 'none') : 'none',
      borderBottom: `1px solid ${theme.colors.border}`,
      borderLeft: isVoid ? 'none' : `1px solid ${theme.colors.border}`,
      borderRight: isVoid ? 'none' : `1px solid ${theme.colors.border}`,
      borderRadius: isVoid ? 0 : 0,
      ...(!isVoid && first ? { borderTopLeftRadius: theme.cardRadius, borderTopRightRadius: theme.cardRadius, borderTop: `1px solid ${theme.colors.border}` } : {}),
      ...(!isVoid && last ? { borderBottomLeftRadius: theme.cardRadius, borderBottomRightRadius: theme.cardRadius } : {}),
      ...(isCyber && danger ? { borderLeft: `3px solid ${theme.colors.urgentAccent}` } : {}),
    }}>
      <div style={{
        width: 30, height: 30,
        borderRadius: theme.chipShape === 'sharp' ? 4 : 8,
        background: `${iconColor}1F`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        border: `1px solid ${iconColor}55`,
        flexShrink: 0,
      }}>
        <Icon name={icon} size={16} color={iconColor}/>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{
          fontSize: isVoid ? 15 : 15, fontWeight: isVoid ? 500 : 500,
          color: danger ? theme.colors.urgentAccent : theme.colors.onBackground,
          fontFamily: isVoid ? theme.fonts.display : theme.fonts.body,
          letterSpacing: isVoid ? -0.1 : 0.1,
        }}>{isMatrix ? label.toLowerCase() : label}</div>
        {value && (
          <div style={{
            marginTop: 2, fontSize: isVoid ? 11 : 12,
            color: theme.colors.muted,
            fontFamily: theme.fonts.body,
            letterSpacing: isVoid ? 1.4 : 0.2,
            textTransform: isVoid ? 'uppercase' : 'none',
            whiteSpace: 'nowrap',
          }}>{value}</div>
        )}
      </div>
      <div style={{ flexShrink: 0 }}>{control}</div>
    </div>
  );
}

function Toggle({ theme, on, tone }) {
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const c = tone || theme.colors.primary;
  if (isMatrix) {
    return (
      <span style={{
        fontFamily: theme.fonts.body, fontSize: 12, fontWeight: 700, letterSpacing: 1.4,
        color: on ? c : theme.colors.muted,
        border: `1px solid ${on ? c : theme.colors.border}`,
        background: on ? `${c}18` : 'transparent',
        padding: '3px 8px',
      }}>{on ? '[ON]' : '[OFF]'}</span>
    );
  }
  return (
    <div style={{
      width: 40, height: 22, borderRadius: 11,
      background: on ? c : theme.colors.surfaceVariant,
      border: `1px solid ${on ? c : theme.colors.border}`,
      position: 'relative',
      ...(on && theme.glow !== 'none' ? { boxShadow: `0 0 10px ${c}80` } : {}),
    }}>
      <div style={{
        position: 'absolute', top: 2, left: on ? 20 : 2,
        width: 16, height: 16, borderRadius: isVoid ? 8 : 8,
        background: on ? theme.colors.background : theme.colors.onSurface,
        transition: 'left 0.2s',
      }}/>
    </div>
  );
}

function Chevron({ theme }) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={theme.colors.muted} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M9 6l6 6-6 6"/>
    </svg>
  );
}

function SettingsScreen({ theme }) {
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;

  const accent = {
    a: theme.colors.primary,
    b: theme.colors.secondary,
    c: isSynth ? '#00E5A0' : (isMatrix ? '#AAFF00' : '#00E5A0'),
    d: theme.colors.urgentAccent,
  };

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', position: 'relative' }}>
      <div style={{ padding: '4px 20px 0' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: 6 }}>
          <div style={{ fontSize: 10, letterSpacing: 2, color: theme.colors.muted, fontFamily: theme.fonts.body, textTransform: up, whiteSpace: 'nowrap' }}>
            {isMatrix && '◉ settings --edit'}
            {isCyber && '// SYSTEM.CONFIG'}
            {isSynth && '◆ SETTINGS'}
            {isVoid && 'Preferences'}
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
          {isMatrix ? 'SETTINGS' : isVoid ? <span>Settings<span style={{ color: theme.colors.primary }}>.</span></span> : 'Settings'}
        </div>
      </div>

      <div className="no-scrollbar" style={{ flex: 1, overflow: 'auto', padding: '0 18px 90px' }}>
        {/* Profile card */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 14,
          padding: isVoid ? '18px 0' : '16px',
          background: isVoid ? 'transparent' : theme.colors.surface,
          border: isVoid ? 'none' : `1px solid ${theme.colors.border}`,
          borderTop: isVoid ? `1px solid ${theme.colors.border}` : undefined,
          borderBottom: isVoid ? `1px solid ${theme.colors.border}` : undefined,
          borderRadius: isVoid ? 0 : theme.cardRadius,
          marginTop: 12, marginBottom: 6,
          ...(isCyber ? { borderLeft: `3px solid ${accent.a}` } : {}),
        }}>
          <div style={{
            width: 52, height: 52,
            borderRadius: theme.chipShape === 'sharp' ? 8 : 26,
            background: `linear-gradient(135deg, ${accent.a} 0%, ${accent.b} 100%)`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            color: theme.colors.background, fontFamily: theme.fonts.display,
            fontSize: 22, fontWeight: 700, letterSpacing: 1,
            ...(theme.glow !== 'none' ? { boxShadow: `0 0 16px ${accent.a}60` } : {}),
          }}>AR</div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{
              fontSize: isVoid ? 18 : 17, fontWeight: 600,
              color: theme.colors.onBackground,
              fontFamily: isVoid ? theme.fonts.display : theme.fonts.body,
              letterSpacing: isVoid ? -0.2 : 0,
            }}>Alex Rivera</div>
            <div style={{
              fontSize: isVoid ? 10 : 12, color: theme.colors.muted,
              fontFamily: theme.fonts.body,
              letterSpacing: isVoid ? 1.5 : 0.2,
              textTransform: isVoid ? 'uppercase' : 'none', whiteSpace: 'nowrap',
            }}>{isMatrix ? '// pro plan \u00b7 sync ok' : 'Pro plan \u00b7 Sync active'}</div>
          </div>
          <Chevron theme={theme}/>
        </div>

        <SettingsGroupHeader theme={theme} label="Appearance"/>
        <SettingsRow theme={theme} first icon="sun" iconColor={accent.a}
          label="Theme" value={isMatrix ? theme.id.toLowerCase() : theme.id[0] + theme.id.slice(1).toLowerCase()}
          control={<Chevron theme={theme}/>}/>
        <SettingsRow theme={theme} icon="grad" iconColor={accent.b}
          label="Display density" value={theme.density || 'Comfortable'}
          control={<Chevron theme={theme}/>}/>
        <SettingsRow theme={theme} last icon="moon" iconColor={accent.c}
          label="Reduce motion"
          control={<Toggle theme={theme} on={false}/>}/>

        <SettingsGroupHeader theme={theme} label="Notifications"/>
        <SettingsRow theme={theme} first icon="pill" iconColor={accent.a}
          label="Daily habits reminder" value={isMatrix ? '08:00' : '8:00 AM'}
          control={<Toggle theme={theme} on={true}/>}/>
        <SettingsRow theme={theme} icon="music" iconColor={accent.b}
          label="Focus session end"
          control={<Toggle theme={theme} on={true}/>}/>
        <SettingsRow theme={theme} last icon="home" iconColor={accent.c}
          label="Weekly review" value={isMatrix ? 'sun 18:00' : 'Sundays \u00b7 6 PM'}
          control={<Toggle theme={theme} on={false}/>}/>

        <SettingsGroupHeader theme={theme} label="Data"/>
        <SettingsRow theme={theme} first icon="grad" iconColor={accent.b}
          label="iCloud sync" value={isMatrix ? 'synced 2m ago' : 'Synced 2 min ago'}
          control={<Toggle theme={theme} on={true}/>}/>
        <SettingsRow theme={theme} icon="tasks" iconColor={accent.a}
          label="Export data"
          control={<Chevron theme={theme}/>}/>
        <SettingsRow theme={theme} last danger icon="plus" iconColor={accent.d}
          label={isMatrix ? 'reset all habits' : 'Reset all habits'}
          control={<Chevron theme={theme}/>}/>

        <div style={{
          marginTop: 24, textAlign: 'center',
          fontSize: 10, letterSpacing: 1.8,
          color: theme.colors.muted, textTransform: 'uppercase',
          fontFamily: theme.fonts.body,
        }}>
          {isMatrix ? 'prismtask v2.4.0 \u2014 build 2026.04' : 'PrismTask v2.4.0'}
        </div>
      </div>
    </div>
  );
}

window.SettingsScreen = SettingsScreen;

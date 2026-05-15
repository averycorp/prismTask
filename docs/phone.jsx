// Themed Android-style phone shell. Applies the active PrismTheme to
// status bar, bottom nav, backgrounds. 412x892 viewport (matches starter).

const { useState } = React;

// —— Shared iconography (stroke icons tuned for dark themes) ——
function Icon({ name, size = 22, color = 'currentColor', strokeWidth = 1.75 }) {
  const p = {
    width: size, height: size, viewBox: '0 0 24 24', fill: 'none',
    stroke: color, strokeWidth, strokeLinecap: 'round', strokeLinejoin: 'round',
  };
  switch (name) {
    case 'settings': return (
      <svg {...p}><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3h0a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8h0a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>
    );
    case 'plus': return (<svg {...p}><path d="M12 5v14M5 12h14"/></svg>);
    case 'today': return (<svg {...p}><rect x="3" y="4" width="18" height="18" rx="2"/><path d="M16 2v4M8 2v4M3 10h18"/></svg>);
    case 'tasks': return (<svg {...p}><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/></svg>);
    case 'habits': return (<svg {...p}><path d="M6.5 6.5l11 11M4 10l10 10M10 4l10 10M14.5 6.5l3 3M6.5 14.5l3 3"/></svg>);
    case 'daily': return (<svg {...p}><circle cx="12" cy="12" r="9"/><path d="M9 12l2 2 4-4"/></svg>);
    case 'recurring': return (<svg {...p}><path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 4v5h-5"/></svg>);
    case 'timer': return (<svg {...p}><circle cx="12" cy="13" r="8"/><path d="M12 9v4l2 2M9 2h6"/></svg>);
    case 'sun': return (<svg {...p}><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/></svg>);
    case 'moon': return (<svg {...p}><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>);
    case 'pill': return (<svg {...p}><rect x="2" y="8" width="20" height="8" rx="4" transform="rotate(-45 12 12)"/><path d="M8.5 8.5l7 7"/></svg>);
    case 'home': return (<svg {...p}><path d="M3 10l9-7 9 7v10a2 2 0 0 1-2 2h-4v-6h-6v6H5a2 2 0 0 1-2-2z"/></svg>);
    case 'grad': return (<svg {...p}><path d="M2 9l10-5 10 5-10 5L2 9z"/><path d="M6 11v5a6 6 0 0 0 12 0v-5"/></svg>);
    case 'music': return (<svg {...p}><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>);
    case 'play': return (<svg {...p}><polygon points="6 4 20 12 6 20 6 4"/></svg>);
    case 'reset': return (<svg {...p}><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/></svg>);
    case 'check': return (<svg {...p}><path d="M20 6L9 17l-5-5"/></svg>);
    case 'trash': return (<svg {...p}><path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M6 6l1 14a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-14"/></svg>);
    case 'clock': return (<svg {...p}><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>);
    case 'flag': return (<svg {...p}><path d="M4 21V4h13l-2 4 2 4H4"/></svg>);
    case 'archive': return (<svg {...p}><rect x="3" y="4" width="18" height="5" rx="1"/><path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9M10 13h4"/></svg>);
    case 'move': return (<svg {...p}><path d="M12 2v20M2 12h20M5 5l3 3M16 5l3 3M5 19l3-3M16 19l3-3"/></svg>);
    case 'chart': return (<svg {...p}><path d="M3 21h18M6 17V9M10 17V5M14 17v-4M18 17v-8"/></svg>);
    case 'grid4': return (<svg {...p}><rect x="3" y="3" width="8" height="8" rx="1"/><rect x="13" y="3" width="8" height="8" rx="1"/><rect x="3" y="13" width="8" height="8" rx="1"/><rect x="13" y="13" width="8" height="8" rx="1"/></svg>);
    case 'zap': return (<svg {...p}><path d="M13 2L3 14h8l-1 8 10-12h-8z"/></svg>);
    default: return null;
  }
}

// —— Status bar (time + signal/battery, recolored per theme) ——
function StatusBar({ theme }) {
  const c = theme.colors.onBackground;
  return (
    <div style={{
      height: 36, display: 'flex', alignItems: 'center',
      justifyContent: 'space-between', padding: '0 18px',
      position: 'relative', fontFamily: theme.fonts.body,
      color: c, fontSize: 13, letterSpacing: 0.2,
    }}>
      <span style={{ fontWeight: 500, width: 128 }}>9:41</span>
      <div style={{
        position: 'absolute', left: '50%', top: 10, transform: 'translateX(-50%)',
        width: 22, height: 22, borderRadius: 100, background: '#000',
      }} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
        {/* cellular */}
        <svg width="14" height="12" viewBox="0 0 14 12"><rect x="0" y="8" width="2" height="4" rx="0.5" fill={c}/><rect x="4" y="5" width="2" height="7" rx="0.5" fill={c}/><rect x="8" y="2" width="2" height="10" rx="0.5" fill={c}/><rect x="12" y="0" width="2" height="12" rx="0.5" fill={c}/></svg>
        <svg width="14" height="12" viewBox="0 0 16 14"><path d="M8 11.5L1 6.5a10 10 0 0 1 14 0L8 11.5z" fill={c}/></svg>
        <svg width="22" height="12" viewBox="0 0 22 12"><rect x="0.5" y="1" width="18" height="10" rx="2" fill="none" stroke={c} strokeOpacity="0.5"/><rect x="2" y="2.5" width="14" height="7" rx="1" fill={c}/><rect x="19" y="4" width="2" height="4" rx="0.5" fill={c} fillOpacity="0.5"/></svg>
      </div>
    </div>
  );
}

// —— Theme-specific decorative background layer ——
function ThemeBackdrop({ theme }) {
  const layers = [];
  if (theme.scanlines) {
    const opacity = theme.id === 'MATRIX' ? 0.7 : 0.55;
    const stripe = theme.id === 'MATRIX' ? 2 : 3;
    layers.push(
      <div key="scan" style={{
        position: 'absolute', inset: 0, pointerEvents: 'none',
        backgroundImage: `repeating-linear-gradient(0deg, ${theme.colors.primary}10 0 1px, transparent 1px ${stripe}px)`,
        mixBlendMode: 'screen', opacity,
      }}/>
    );
  }
  if (theme.gridFloor) {
    layers.push(
      <div key="grid" style={{
        position: 'absolute', left: 0, right: 0, bottom: 0, height: 260,
        pointerEvents: 'none',
        backgroundImage: `linear-gradient(${theme.colors.primary}35 1px, transparent 1px), linear-gradient(90deg, ${theme.colors.primary}35 1px, transparent 1px)`,
        backgroundSize: '26px 20px',
        maskImage: 'linear-gradient(to top, rgba(0,0,0,0.9) 0%, transparent 100%)',
        WebkitMaskImage: 'linear-gradient(to top, rgba(0,0,0,0.9) 0%, transparent 100%)',
        transform: 'perspective(380px) rotateX(62deg)',
        transformOrigin: 'center bottom',
      }}/>
    );
    layers.push(
      <div key="sun" style={{
        position: 'absolute', left: '50%', top: '32%', transform: 'translate(-50%, -50%)',
        width: 380, height: 380, pointerEvents: 'none',
        background: `radial-gradient(circle, ${theme.colors.primary}25 0%, ${theme.colors.secondary}15 35%, transparent 65%)`,
        filter: 'blur(18px)',
      }}/>
    );
  }
  if (theme.terminal) {
    // faint vertical "rain" lines
    layers.push(
      <div key="rain" style={{
        position: 'absolute', inset: 0, pointerEvents: 'none',
        backgroundImage: `repeating-linear-gradient(90deg, ${theme.colors.primary}0A 0 1px, transparent 1px 14px)`,
        opacity: 0.6,
      }}/>
    );
  }
  return <>{layers}</>;
}

// —— Bottom nav ——
function BottomNav({ theme, active }) {
  const items = [
    { id: 'today', label: 'Today', icon: 'today' },
    { id: 'tasks', label: 'Tasks', icon: 'tasks' },
    { id: 'daily', label: 'Daily', icon: 'daily' },
    { id: 'recurring', label: 'Recurring', icon: 'recurring' },
    { id: 'timer', label: 'Timer', icon: 'timer' },
    { id: 'settings', label: 'Settings', icon: 'settings' },
  ];
  return (
    <div style={{
      display: 'flex', borderTop: `1px solid ${theme.colors.border}`,
      background: theme.colors.background,
      padding: '8px 4px 6px', fontFamily: theme.fonts.body,
    }}>
      {items.map(it => {
        const on = it.id === active;
        return (
          <div key={it.id} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3, minWidth: 0 }}>
            <div style={{
              padding: '3px 10px', borderRadius: 12,
              background: on ? theme.colors.surfaceVariant : 'transparent',
              color: on ? theme.colors.primary : theme.colors.muted,
            }}>
              <Icon name={it.icon} size={18} color={on ? theme.colors.primary : theme.colors.onSurface} />
            </div>
            <div style={{
              fontSize: 9.5, letterSpacing: 0.2,
              color: on ? theme.colors.primary : theme.colors.muted,
              fontWeight: on ? 600 : 400,
              fontFamily: theme.fonts.body,
              textTransform: theme.displayUpper ? 'uppercase' : 'none',
              whiteSpace: 'nowrap',
            }}>{it.label}</div>
          </div>
        );
      })}
    </div>
  );
}

// —— Full phone frame ——
function PhoneFrame({ theme, active, children, label, scale = 1 }) {
  const W = 412, H = 892;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 18 }}>
      <div style={{
        width: W * scale, height: H * scale,
        position: 'relative',
      }}>
        <div style={{
          width: W, height: H, transform: `scale(${scale})`, transformOrigin: 'top left',
          borderRadius: 44, overflow: 'hidden',
          background: theme.colors.background,
          border: `3px solid #2a2a30`,
          outline: `8px solid #0a0a0c`,
          boxShadow: `0 40px 100px rgba(0,0,0,0.5), 0 0 0 1px ${theme.colors.border}, inset 0 0 0 1px rgba(255,255,255,0.02)`,
          display: 'flex', flexDirection: 'column', position: 'relative',
        }}>
          <ThemeBackdrop theme={theme}/>
          <StatusBar theme={theme}/>
          <div className="no-scrollbar" style={{ flex: 1, overflow: 'hidden', position: 'relative', fontFamily: theme.fonts.body, color: theme.colors.onBackground }}>
            {children}
          </div>
          <BottomNav theme={theme} active={active}/>
          {/* home indicator */}
          <div style={{ height: 18, display: 'flex', alignItems: 'center', justifyContent: 'center', background: theme.colors.background }}>
            <div style={{ width: 120, height: 4, borderRadius: 2, background: theme.colors.onSurface, opacity: 0.3 }}/>
          </div>
        </div>
      </div>
      {label && (
        <div style={{ textAlign: 'center', fontFamily: 'ui-monospace, monospace', fontSize: 11, letterSpacing: 0.15, color: '#6b6b78' }}>
          {label}
        </div>
      )}
    </div>
  );
}

Object.assign(window, { PhoneFrame, Icon, StatusBar, BottomNav });

// —— Theme-specific helper components ——

// Corner brackets (Cyberpunk HUD look)
function CornerBrackets({ color, size = 12, thickness = 1.5 }) {
  const s = { position: 'absolute', width: size, height: size, borderColor: color, borderStyle: 'solid' };
  return (
    <>
      <div style={{ ...s, top: -1, left: -1, borderWidth: `${thickness}px 0 0 ${thickness}px` }}/>
      <div style={{ ...s, top: -1, right: -1, borderWidth: `${thickness}px ${thickness}px 0 0` }}/>
      <div style={{ ...s, bottom: -1, left: -1, borderWidth: `0 0 ${thickness}px ${thickness}px` }}/>
      <div style={{ ...s, bottom: -1, right: -1, borderWidth: `0 ${thickness}px ${thickness}px 0` }}/>
    </>
  );
}

// Theme-aware card: wraps children with corner brackets for Cyberpunk, sunset glow for Synthwave,
// ASCII-ish border for Matrix, hairline for Void.
function ThemedCard({ theme, children, padding, style = {}, accent, glowing = false }) {
  const t = theme;
  const r = t.cardRadius ?? 12;
  const baseStyle = {
    position: 'relative',
    background: t.colors.surface,
    border: `1px solid ${t.colors.border}`,
    borderRadius: r,
    padding: padding ?? 20,
    ...style,
  };
  // Cyberpunk: add a colored inner border and corner brackets
  if (t.brackets) {
    baseStyle.border = `1px solid ${t.colors.primary}30`;
    baseStyle.background = `linear-gradient(180deg, ${t.colors.surfaceVariant} 0%, ${t.colors.surface} 100%)`;
    if (glowing) baseStyle.boxShadow = `0 0 0 1px ${t.colors.primary}20, inset 0 0 40px ${t.colors.primary}10`;
    return (
      <div style={baseStyle}>
        <CornerBrackets color={t.colors.primary} size={10} thickness={1.5}/>
        {children}
      </div>
    );
  }
  // Synthwave: glow edge when glowing, gradient surface
  if (t.sunset) {
    baseStyle.background = `linear-gradient(155deg, ${t.colors.surface} 0%, ${t.colors.surfaceVariant} 100%)`;
    if (glowing) {
      baseStyle.boxShadow = `0 12px 40px ${t.colors.primary}30, 0 0 0 1px ${t.colors.primary}25, inset 0 1px 0 ${t.colors.onSurface}20`;
      baseStyle.border = `1px solid ${t.colors.primary}40`;
    }
    return <div style={baseStyle}>{children}</div>;
  }
  // Matrix: ascii-like thick outline, no rounding
  if (t.terminal) {
    baseStyle.border = `1px solid ${t.colors.primary}50`;
    baseStyle.background = t.colors.surface;
    baseStyle.borderRadius = 0;
    if (glowing) baseStyle.boxShadow = `inset 0 0 0 1px ${t.colors.primary}20`;
    return (
      <div style={baseStyle}>
        {/* tiny corner label e.g. [ CARD ] */}
        {children}
      </div>
    );
  }
  // Void: hairline border, soft elevation
  if (t.editorial) {
    baseStyle.background = t.colors.surface;
    baseStyle.border = `1px solid ${t.colors.border}`;
    return <div style={baseStyle}>{children}</div>;
  }
  return <div style={baseStyle}>{children}</div>;
}

// Theme-aware glow wrapper for primary accents
function glowShadow(theme, color, intensity = 1) {
  if (theme.glow === 'none') return 'none';
  const c = color || theme.colors.primary;
  if (theme.glow === 'heavy') return `0 0 ${20 * intensity}px ${c}80, 0 0 ${40 * intensity}px ${c}40`;
  if (theme.glow === 'strong') return `0 0 ${14 * intensity}px ${c}99, 0 0 ${28 * intensity}px ${c}33`;
  if (theme.glow === 'soft') return `0 0 ${10 * intensity}px ${c}60`;
  return 'none';
}

// Terminal prompt — used by Matrix
function Prompt({ theme, children, style = {} }) {
  return (
    <span style={{ color: theme.colors.primary, ...style }}>
      <span style={{ opacity: 0.6 }}>{'> '}</span>{children}
    </span>
  );
}

Object.assign(window, { ThemedCard, CornerBrackets, glowShadow, Prompt });

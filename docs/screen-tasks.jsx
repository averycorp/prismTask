// TasksScreen — Inbox / task list view. Themed with per-theme signature treatments.

function TaskFilterPill({ theme, label, active, count }) {
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  return (
    <div style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: isVoid ? '7px 0' : '7px 14px',
      borderRadius: theme.chipShape === 'sharp' ? theme.radius : 999,
      background: active
        ? (isMatrix ? `${theme.colors.primary}18` : theme.colors.surfaceVariant)
        : 'transparent',
      border: isVoid
        ? (active ? `1px solid ${theme.colors.onBackground}` : 'none')
        : `1px solid ${active ? theme.colors.primary + '60' : theme.colors.border}`,
      borderBottom: isVoid && active ? `2px solid ${theme.colors.primary}` : undefined,
      color: active ? theme.colors.primary : theme.colors.onSurface,
      fontSize: isVoid ? 11 : 12,
      fontWeight: 600,
      fontFamily: theme.fonts.body,
      letterSpacing: isVoid ? 2 : 0.5,
      textTransform: up,
      whiteSpace: 'nowrap',
    }}>
      {isMatrix && active && <span style={{ opacity: 0.6 }}>▸</span>}
      {isVoid ? <>{label}{count != null && <span style={{ marginLeft: 8, color: theme.colors.muted, fontWeight: 400 }}>{count}</span>}</> : (
        <>{label}{count != null && <span style={{ opacity: 0.6, fontWeight: 400 }}>{count}</span>}</>
      )}
    </div>
  );
}

function PriorityFlag({ theme, priority }) {
  const colors = {
    urgent: theme.colors.urgentAccent,
    high:   theme.colors.primary,
    med:    theme.colors.secondary,
    low:    theme.colors.muted,
    none:   'transparent',
  };
  const c = colors[priority] || colors.none;
  if (priority === 'none') return <div style={{ width: 3 }}/>;
  return (
    <div style={{
      width: 3, alignSelf: 'stretch',
      background: c,
      borderRadius: theme.chipShape === 'sharp' ? 0 : 2,
      ...(theme.glow !== 'none' && priority === 'urgent' ? { boxShadow: `0 0 8px ${c}90` } : {}),
    }}/>
  );
}

function TaskTag({ theme, label, color }) {
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  return (
    <span style={{
      display: 'inline-block',
      padding: isVoid ? '0' : '2px 8px',
      borderRadius: theme.chipShape === 'sharp' ? 3 : 999,
      background: isVoid ? 'transparent' : (color ? `${color}1F` : theme.colors.tagSurface),
      color: color || theme.colors.tagText,
      fontSize: isVoid ? 10 : 11,
      fontWeight: 500,
      fontFamily: theme.fonts.body,
      letterSpacing: isVoid ? 1.8 : 0.4,
      textTransform: theme.displayUpper || isVoid ? 'uppercase' : 'none',
      border: isMatrix ? `1px solid ${color || theme.colors.tagText}40` : 'none',
    }}>
      {isMatrix ? `#${label.toLowerCase()}` : isVoid ? label : label}
    </span>
  );
}

function TaskRow({ theme, task, first, last, swipeState }) {
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;
  const checkColor = task.priority === 'urgent' ? theme.colors.urgentAccent
    : task.priority === 'high' ? theme.colors.primary
    : theme.colors.muted;
  const textColor = task.done ? theme.colors.muted : theme.colors.onBackground;

  // swipeState: { side: 'left'|'right', color, label, icon, offset }
  // Renders a revealed gutter behind the row; row itself is translated.
  const swipe = swipeState || null;
  const rowOffset = swipe ? (swipe.side === 'left' ? swipe.offset : -swipe.offset) : 0;
  const up = theme.displayUpper ? 'uppercase' : 'none';

  return (
    <div style={{ position: 'relative', marginBottom: isVoid ? 14 : 8 }}>
      {/* Revealed swipe gutter */}
      {swipe && (
        <div style={{
          position: 'absolute', inset: 0,
          borderRadius: theme.cardRadius,
          background: swipe.color,
          display: 'flex',
          justifyContent: swipe.side === 'left' ? 'flex-start' : 'flex-end',
          alignItems: 'center',
          padding: '0 18px',
          gap: 8,
          color: swipe.fg || '#0b0b10',
          fontFamily: theme.fonts.body,
          fontSize: 12, fontWeight: 700,
          letterSpacing: 1.4, textTransform: up,
          ...(isMatrix ? { border: `1px solid ${swipe.color}`, color: swipe.fg || theme.colors.background } : {}),
        }}>
          <Icon name={swipe.icon} size={18} color={swipe.fg || '#0b0b10'} strokeWidth={2.4}/>
          <span>{swipe.label}</span>
        </div>
      )}
      <div style={{
        transform: `translateX(${rowOffset}px)`,
        display: 'flex', alignItems: 'stretch', gap: 0,
        background: theme.colors.surface,
        border: `1px solid ${theme.colors.border}`,
        borderRadius: theme.cardRadius,
        overflow: 'hidden',
        ...(isCyber && task.priority === 'urgent'
          ? { borderLeft: `3px solid ${theme.colors.urgentAccent}`, boxShadow: `0 0 10px ${theme.colors.urgentAccent}30` }
          : {}),
      }}>
      <PriorityFlag theme={theme} priority={task.priority}/>
      <div style={{
        display: 'flex', alignItems: 'flex-start', gap: 12,
        padding: isVoid ? '16px 18px' : '12px 14px',
        flex: 1,
      }}>
        {/* Checkbox */}
        {isMatrix ? (
          <div style={{
            width: 22, height: 22, flexShrink: 0, marginTop: 1,
            border: `1px solid ${checkColor}`,
            background: task.done ? theme.colors.primary : 'transparent',
            color: theme.colors.background,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 14, fontFamily: theme.fonts.body,
          }}>{task.done ? '✓' : ''}</div>
        ) : (
          <div style={{
            width: 22, height: 22, flexShrink: 0, marginTop: 1,
            border: `1.5px solid ${checkColor}`,
            borderRadius: theme.chipShape === 'sharp' ? 3 : '50%',
            background: task.done ? checkColor : 'transparent',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            {task.done && <Icon name="check" size={12} color={theme.colors.background} strokeWidth={3}/>}
          </div>
        )}

        {/* Body */}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{
            fontSize: isVoid ? 16 : 15,
            fontWeight: isVoid ? 500 : 600,
            color: textColor,
            fontFamily: isVoid ? theme.fonts.display : theme.fonts.body,
            letterSpacing: isVoid ? -0.1 : 0.1,
            textDecoration: task.done ? 'line-through' : 'none',
            lineHeight: 1.3,
          }}>{task.title}</div>

          {/* Meta row */}
          <div style={{
            marginTop: 6, display: 'flex', alignItems: 'center',
            gap: isVoid ? 14 : 10, flexWrap: 'wrap',
            fontSize: isVoid ? 11 : 12,
            color: theme.colors.muted, fontFamily: theme.fonts.body,
            letterSpacing: isVoid ? 1.4 : 0.2,
            textTransform: isVoid ? 'uppercase' : 'none',
          }}>
            {task.due && (
              <span style={{
                color: task.dueColor || theme.colors.onSurface,
                display: 'inline-flex', alignItems: 'center', gap: 4,
              }}>
                {isMatrix ? '@' : '◷'} {task.due}
              </span>
            )}
            {task.project && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <span style={{ width: 7, height: 7, borderRadius: isVoid || theme.chipShape === 'pill' ? '50%' : 0, background: task.projectColor || theme.colors.secondary, display: 'inline-block' }}/>
                {task.project}
              </span>
            )}
            {task.subtasks && (
              <span>{isMatrix ? `[${task.subtasks}]` : task.subtasks}</span>
            )}
          </div>

          {/* Tags */}
          {task.tags && task.tags.length > 0 && (
            <div style={{ marginTop: 8, display: 'flex', gap: isVoid ? 10 : 6, flexWrap: 'wrap' }}>
              {task.tags.map(tg => <TaskTag key={tg.label} theme={theme} {...tg}/>)}
            </div>
          )}
        </div>
      </div>
      </div>
    </div>
  );
}

function TaskGroupHeader({ theme, label, count }) {
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10,
      marginTop: isVoid ? 26 : 18, marginBottom: 10,
      fontSize: isVoid ? 10 : 11,
      fontWeight: 600, fontFamily: theme.fonts.body,
      letterSpacing: isVoid ? 2.4 : 1.4,
      textTransform: 'uppercase',
      color: theme.colors.onSurface,
    }}>
      {isVoid && <span style={{ width: 18, height: 1, background: theme.colors.onSurface, display: 'inline-block' }}/>}
      <span style={{ color: theme.colors.primary }}>
        {isMatrix ? `# ${label.toLowerCase()}` : label}
      </span>
      {count != null && <span style={{ color: theme.colors.muted, fontWeight: 400 }}>{isMatrix ? `[${count}]` : count}</span>}
      <span style={{ flex: 1, height: 1, background: theme.colors.border, marginLeft: 6 }}/>
    </div>
  );
}

function TasksScreen({ theme }) {
  const up = theme.displayUpper ? 'uppercase' : 'none';
  const isMatrix = theme.terminal;
  const isVoid = theme.editorial;
  const isCyber = theme.brackets;
  const isSynth = theme.sunset;

  const tasks = {
    overdue: [
      {
        title: 'Submit expense report',
        priority: 'urgent', due: 'Yesterday',
        dueColor: theme.colors.urgentAccent,
        project: 'Admin', projectColor: theme.colors.urgentAccent,
        tags: [{ label: 'Work', color: theme.colors.primary }, { label: 'Finance' }],
      },
    ],
    today: [
      {
        title: 'Review Q2 roadmap with team',
        priority: 'high', due: '2:00 PM', subtasks: '3 of 5',
        project: 'Planning', projectColor: theme.colors.primary,
        tags: [{ label: 'Work', color: theme.colors.primary }],
      },
      {
        title: 'Call dentist about appointment',
        priority: 'med', due: '4:30 PM',
        project: 'Health', projectColor: theme.colors.secondary,
        tags: [{ label: 'Personal', color: theme.colors.secondary }],
      },
      {
        title: 'Morning run — 3 miles',
        priority: 'low', done: true, due: '7:00 AM',
        project: 'Fitness', projectColor: '#00E5A0',
        tags: [{ label: 'Health', color: '#00E5A0' }],
      },
    ],
    upcoming: [
      {
        title: 'Prep slides for Thursday review',
        priority: 'high', due: 'Wed · 9 AM', subtasks: '0 of 4',
        project: 'Planning', projectColor: theme.colors.primary,
      },
      {
        title: 'Buy groceries',
        priority: 'none', due: 'Thu',
        project: 'Home', projectColor: theme.colors.secondary,
      },
    ],
  };

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', position: 'relative' }}>
      <div style={{ padding: '4px 20px 0' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: 6 }}>
          <div style={{ fontSize: 10, letterSpacing: 2, color: theme.colors.muted, fontFamily: theme.fonts.body, textTransform: up, whiteSpace: 'nowrap' }}>
            {isMatrix && '◉ tasks --all'}
            {isCyber && '// TASKS.QUEUE'}
            {isSynth && '◆ TASKS'}
            {isVoid && 'Inbox'}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            {/* search */}
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke={theme.colors.onSurface} strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>
            </svg>
            <Icon name="settings" size={22} color={theme.colors.onSurface}/>
          </div>
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
          {isMatrix ? 'TASKS' : isVoid ? <span>Tasks<span style={{ color: theme.colors.primary }}>.</span></span> : 'Tasks'}
        </div>
        <div style={{
          fontSize: isVoid ? 12 : 13, color: theme.colors.muted,
          fontFamily: theme.fonts.body, letterSpacing: isVoid ? 1.5 : 0.3,
          textTransform: isVoid ? 'uppercase' : 'none',
          marginBottom: 14,
        }}>
          {isMatrix ? '// 6 open · 1 done · 1 overdue' : '6 open · 1 done · 1 overdue'}
        </div>

        {/* Filter pills */}
        <div style={{ display: 'flex', gap: isVoid ? 20 : 8, overflowX: 'auto', paddingBottom: 12,
          borderBottom: isVoid ? `1px solid ${theme.colors.border}` : 'none',
        }}
          className="no-scrollbar"
        >
          <TaskFilterPill theme={theme} label="All" count={7} active/>
          <TaskFilterPill theme={theme} label="Today" count={3}/>
          <TaskFilterPill theme={theme} label="Upcoming" count={2}/>
          <TaskFilterPill theme={theme} label="Flagged" count={1}/>
          <TaskFilterPill theme={theme} label="Completed"/>
        </div>
      </div>

      <div className="no-scrollbar" style={{ flex: 1, overflow: 'auto', padding: '4px 18px 90px' }}>
        <TaskGroupHeader theme={theme} label="Overdue" count={1}/>
        {tasks.overdue.map((t, i) => <TaskRow key={i} theme={theme} task={t} swipeState={{ side: 'right', color: theme.colors.swipeDelete, label: 'Delete', icon: 'trash', offset: 92, fg: '#fff' }}/>)}

        <TaskGroupHeader theme={theme} label="Today" count={3}/>
        <TaskRow theme={theme} task={tasks.today[0]} swipeState={{ side: 'left', color: theme.colors.swipeComplete, label: 'Complete', icon: 'check', offset: 96, fg: '#0b0b10' }}/>
        <TaskRow theme={theme} task={tasks.today[1]} swipeState={{ side: 'right', color: theme.colors.swipeReschedule, label: 'Later', icon: 'clock', offset: 88, fg: '#0b0b10' }}/>
        <TaskRow theme={theme} task={tasks.today[2]}/>

        <TaskGroupHeader theme={theme} label="Upcoming" count={2}/>
        <TaskRow theme={theme} task={tasks.upcoming[0]} swipeState={{ side: 'left', color: theme.colors.swipeFlag, label: 'Flag', icon: 'flag', offset: 80, fg: '#0b0b10' }}/>
        <TaskRow theme={theme} task={tasks.upcoming[1]} swipeState={{ side: 'right', color: theme.colors.swipeArchive, label: 'Archive', icon: 'archive', offset: 84, fg: '#fff' }}/>
      </div>

      {/* FAB */}
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

window.TasksScreen = TasksScreen;

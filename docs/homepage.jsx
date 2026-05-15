// PrismTask homepage — marketing site components.
// Apple-style: light chrome (warm off-white), big phones, calm typography,
// restrained accent color until moments. All phone mockups render LIVE from
// the existing screen JSX files via window.PRISM_THEMES + the PhoneFrame.

const { useState, useEffect, useRef } = React;

// ── Theme + screen helpers ─────────────────────────────────────────────
const T = window.PRISM_THEMES;
const SCREENS = {
  today:      window.TodayScreen,
  tasks:      window.TasksScreen,
  daily:      window.DailyScreen,
  recurring:  window.RecurringScreen,
  timer:      window.TimerScreen,
  meds:       window.MedicationScreen,
  insights:   window.InsightsScreen,
  matrix:     window.EisenhowerScreen,
  settings:   window.SettingsScreen,
};

function Phone({ theme = T.VOID, screen = 'today', scale = 0.6, active = null, style }) {
  const Comp = SCREENS[screen];
  return (
    <div style={{ display: 'inline-block', ...style }}>
      <window.PhoneFrame theme={theme} scale={scale} active={active}>
        <Comp theme={theme}/>
      </window.PhoneFrame>
    </div>
  );
}

// Intersection-observer scroll-reveal — used everywhere.
function Reveal({ children, delay = 0, as: As = 'div', style, className }) {
  const ref = useRef(null);
  const [shown, setShown] = useState(false);
  useEffect(() => {
    if (!ref.current) return;
    const io = new IntersectionObserver(([e]) => {
      if (e.isIntersecting) { setShown(true); io.disconnect(); }
    }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
    io.observe(ref.current);
    return () => io.disconnect();
  }, []);
  return (
    <As ref={ref} className={className} style={{
      opacity: shown ? 1 : 0,
      transform: shown ? 'translateY(0)' : 'translateY(24px)',
      transition: `opacity 800ms cubic-bezier(.2,.65,.3,1) ${delay}ms, transform 900ms cubic-bezier(.2,.65,.3,1) ${delay}ms`,
      ...style,
    }}>{children}</As>
  );
}

// ── Nav bar ────────────────────────────────────────────────────────────
function Nav() {
  return (
    <nav style={{
      position: 'sticky', top: 0, zIndex: 50,
      background: 'rgba(250, 250, 247, 0.85)',
      backdropFilter: 'blur(20px)',
      WebkitBackdropFilter: 'blur(20px)',
      borderBottom: '1px solid rgba(0,0,0,0.06)',
    }}>
      <div style={{
        maxWidth: 1240, margin: '0 auto',
        padding: '14px 32px',
        display: 'flex', alignItems: 'center', gap: 32,
      }}>
        <a href="#top" style={{
          fontFamily: 'Fraunces, serif', fontSize: 19, fontWeight: 600,
          color: '#0F0F12', letterSpacing: -0.4,
          textDecoration: 'none',
          display: 'inline-flex', alignItems: 'center', gap: 8,
        }}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden>
            <path d="M12 3 L22 21 L2 21 Z" fill="#5B47E0" opacity="0.9"/>
            <path d="M12 8 L17.5 18 L6.5 18 Z" fill="#fff" opacity="0.4"/>
          </svg>
          PrismTask
        </a>
        <div style={{ display: 'flex', gap: 24, flex: 1, justifyContent: 'center' }}>
          {['Features', 'Themes', 'Philosophy', 'Wellness', 'Pricing', 'FAQ'].map(item => (
            <a key={item} href={`#${item.toLowerCase()}`} style={{
              fontFamily: 'Space Grotesk, sans-serif', fontSize: 13.5, fontWeight: 500,
              color: '#4A4A52', textDecoration: 'none', letterSpacing: 0.1,
            }}>{item}</a>
          ))}
        </div>
        <a href="#waitlist" style={{
          padding: '8px 16px', borderRadius: 999,
          background: '#0F0F12', color: '#fff',
          fontFamily: 'Space Grotesk, sans-serif', fontSize: 13, fontWeight: 500,
          textDecoration: 'none',
        }}>Join waitlist</a>
      </div>
    </nav>
  );
}

// ── Hero ───────────────────────────────────────────────────────────────
// Waitlist sink: each signup is written to a `waitlist` collection in the
// shared averytask-50dc5 Firestore project. The SDK is initialized in
// index.html and exposed on window.__prismWaitlist (the page has no
// bundler, so babel-standalone'd JSX cannot use ESM imports directly).

function Hero() {
  const [email, setEmail] = useState('');
  const [status, setStatus] = useState('idle'); // idle | sending | submitted | error
  const submitted = status === 'submitted';

  async function handleWaitlistSubmit(e) {
    e.preventDefault();
    if (!email || status === 'sending' || submitted) return;
    setStatus('sending');

    // Local backup so the entry survives a network failure and the user can
    // see their own signup if they come back.
    try {
      const key = 'prismtask_waitlist_local';
      const prior = JSON.parse(localStorage.getItem(key) || '[]');
      if (!prior.includes(email)) {
        prior.push(email);
        localStorage.setItem(key, JSON.stringify(prior));
      }
    } catch (_) { /* private mode / quota — non-fatal */ }

    try {
      const sink = window.__prismWaitlist;
      if (!sink) throw new Error('waitlist-sdk-not-ready');
      const { db, collection, addDoc, serverTimestamp } = sink;
      await addDoc(collection(db, 'waitlist'), {
        email,
        source: 'docs.homepage',
        userAgent: (navigator.userAgent || '').slice(0, 512),
        createdAt: serverTimestamp(),
      });
      setStatus('submitted');
    } catch (_) {
      // Don't show a hard error — the email is already in localStorage so we
      // honor the user's intent, but flag the error state so the button copy
      // can soften and they can retry.
      setStatus('error');
    }
  }

  return (
    <section id="top" style={{ paddingTop: 96, paddingBottom: 80, overflow: 'hidden', position: 'relative' }}>
      {/* Soft radial backdrop */}
      <div aria-hidden style={{
        position: 'absolute', inset: 0,
        background: 'radial-gradient(80% 60% at 50% -10%, rgba(91,71,224,0.10) 0%, transparent 60%)',
        pointerEvents: 'none',
      }}/>
      <div style={{ maxWidth: 1240, margin: '0 auto', padding: '0 32px', position: 'relative' }}>
        <Reveal delay={80}>
          <h1 style={{
            fontFamily: 'Fraunces, serif',
            fontSize: 'clamp(44px, 6.5vw, 88px)',
            fontWeight: 500,
            letterSpacing: -2.2,
            lineHeight: 0.98,
            color: '#0F0F12',
            margin: 0,
            maxWidth: 1000,
          }}>
            Plan your day. Honor your <em style={{ fontStyle: 'italic', color: '#5B47E0', fontWeight: 500 }}>brain.</em>
          </h1>
        </Reveal>

        <Reveal delay={160}>
          <p style={{
            fontFamily: 'Space Grotesk, sans-serif',
            fontSize: 'clamp(18px, 1.6vw, 22px)',
            fontWeight: 400,
            lineHeight: 1.45,
            color: '#3D3D45',
            margin: '28px 0 0',
            maxWidth: 640,
            letterSpacing: -0.2,
          }}>
            A mental-health-first task manager. Tasks, habits, focus timer, medication
            tracking, and mood check-ins — designed so productivity supports your
            wellbeing instead of grinding against it. Built for the days that go to
            plan and the days that don't.
          </p>
        </Reveal>

        <Reveal delay={240}>
          <form
            id="waitlist"
            onSubmit={handleWaitlistSubmit}
            style={{
              marginTop: 36, display: 'flex', gap: 8, flexWrap: 'wrap',
              maxWidth: 460,
            }}
          >
            <input
              type="email" required
              value={email} onChange={e => setEmail(e.target.value)}
              placeholder="you@example.com"
              disabled={submitted || status === 'sending'}
              autoComplete="email"
              style={{
                flex: 1, minWidth: 240,
                padding: '14px 18px', borderRadius: 999,
                border: '1px solid rgba(15,15,18,0.15)',
                background: '#fff',
                fontFamily: 'Space Grotesk, sans-serif', fontSize: 15,
                color: '#0F0F12',
                outline: 'none',
                transition: 'border-color 0.2s',
              }}
            />
            <button type="submit" disabled={submitted || status === 'sending'} style={{
              padding: '14px 24px', borderRadius: 999,
              background: submitted ? '#0AA371' : status === 'error' ? '#B45309' : '#0F0F12',
              color: '#fff', border: 'none',
              fontFamily: 'Space Grotesk, sans-serif', fontSize: 15, fontWeight: 500,
              cursor: submitted ? 'default' : status === 'sending' ? 'progress' : 'pointer',
              transition: 'all 0.2s',
            }}>{
              status === 'submitted' ? '✓ You\u2019re on the list'
              : status === 'sending'  ? 'Adding you\u2026'
              : status === 'error'    ? 'Saved \u2014 try again to confirm'
              : 'Join the waitlist'
            }</button>
          </form>
          <p style={{
            marginTop: 12, fontFamily: 'Space Grotesk, sans-serif',
            fontSize: 13, color: '#7A7A83', letterSpacing: 0.1,
          }}>
            Android beta this summer · iOS shortly after · No spam, just launch updates.
          </p>
        </Reveal>

        {/* Hero phones — 3 themes, fanned */}
        <Reveal delay={320}>
          <div style={{
            marginTop: 80, position: 'relative',
            display: 'flex', justifyContent: 'center', alignItems: 'flex-end',
            gap: 24, flexWrap: 'wrap',
          }}>
            <div style={{ transform: 'translateY(20px) rotate(-2deg)', filter: 'drop-shadow(0 20px 40px rgba(0,0,0,0.18))' }}>
              <Phone theme={T.VOID} screen="today" scale={0.58} active="today"/>
            </div>
            <div style={{ filter: 'drop-shadow(0 30px 60px rgba(0,0,0,0.25))' }}>
              <Phone theme={T.SYNTHWAVE} screen="timer" scale={0.62} active="timer"/>
            </div>
            <div style={{ transform: 'translateY(20px) rotate(2deg)', filter: 'drop-shadow(0 20px 40px rgba(0,0,0,0.18))' }}>
              <Phone theme={T.MATRIX} screen="daily" scale={0.58} active="daily"/>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

// ── Feature grid ───────────────────────────────────────────────────────
function Features() {
  const items = [
    { icon: 'tasks', title: 'Quick task capture', body: 'Natural-language input parses dates, projects, tags, and recurrence as you type. Voice works too.' },
    { icon: 'daily', title: 'Habit tracking', body: 'Daily and recurring habits with forgiveness-first streaks. Skipped days don\u2019t punish you.' },
    { icon: 'timer', title: 'Focus timer', body: 'Pomodoro that adapts to your energy. Pause, log session notes, take a break when you need one.' },
    { icon: 'pill', title: 'Medication tracker', body: 'Slot-based dosing — Essential, Prescription, Complete tiers — with backlogging for late doses.' },
    { icon: 'recurring', title: 'Smart recurrence', body: 'Weekly, monthly, weekday-only, after-completion, or custom days. Reschedule with a swipe.' },
    { icon: 'grid4', title: 'Eisenhower matrix', body: 'Sort by urgency × importance in a glanceable 2×2 grid. See what to drop, not just what to do.' },
    { icon: 'chart', title: 'Insights', body: 'Where your time actually went. Daily score, weekly trend, category breakdown.' },
    { icon: 'home', title: 'Home-screen widgets', body: '14 widgets covering tasks, habits, calendar, focus, projects, meds, and more.' },
  ];
  return (
    <section id="features" style={{ padding: '120px 0', background: '#fff' }}>
      <div style={{ maxWidth: 1240, margin: '0 auto', padding: '0 32px' }}>
        <Reveal>
          <SectionHeader eyebrow="Everything you'd expect" title={<>One app, <em style={{ fontStyle: 'italic', color: '#5B47E0' }}>thoughtfully</em> built.</>}/>
        </Reveal>
        <div style={{
          marginTop: 60,
          display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
          gap: 28,
        }}>
          {items.map((it, i) => (
            <Reveal key={it.title} delay={i * 60}>
              <FeatureCard icon={it.icon} title={it.title} body={it.body}/>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}

function FeatureCard({ icon, title, body }) {
  return (
    <div className="feature-card" style={{
      padding: 28, borderRadius: 18,
      background: '#FAFAF7',
      border: '1px solid rgba(0,0,0,0.05)',
      transition: 'transform 0.3s cubic-bezier(.2,.65,.3,1), box-shadow 0.3s, border-color 0.3s',
    }}>
      <div style={{
        width: 44, height: 44, borderRadius: 12,
        background: 'rgba(91,71,224,0.1)', color: '#5B47E0',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        marginBottom: 18,
      }}>
        <window.Icon name={icon} size={22} color="#5B47E0"/>
      </div>
      <h3 style={{
        fontFamily: 'Fraunces, serif', fontSize: 22, fontWeight: 500,
        color: '#0F0F12', letterSpacing: -0.4, margin: '0 0 8px',
      }}>{title}</h3>
      <p style={{
        fontFamily: 'Space Grotesk, sans-serif', fontSize: 14.5, lineHeight: 1.55,
        color: '#54545E', margin: 0,
      }}>{body}</p>
    </div>
  );
}

// ── Theme showcase ─────────────────────────────────────────────────────
function ThemeShowcase() {
  const themes = ['VOID', 'CYBERPUNK', 'SYNTHWAVE', 'MATRIX'];
  return (
    <section id="themes" style={{
      padding: '140px 0',
      background: '#0B0B0F',
      color: '#fff',
      overflow: 'hidden',
      position: 'relative',
    }}>
      {/* Subtle gradient blooms */}
      <div aria-hidden style={{
        position: 'absolute', inset: 0,
        background: 'radial-gradient(60% 40% at 20% 30%, rgba(91,71,224,0.18) 0%, transparent 60%), radial-gradient(60% 40% at 80% 70%, rgba(255,45,135,0.10) 0%, transparent 60%)',
        pointerEvents: 'none',
      }}/>
      <div style={{ maxWidth: 1240, margin: '0 auto', padding: '0 32px', position: 'relative' }}>
        <Reveal>
          <SectionHeader
            light
            eyebrow="Four themes"
            title={<>Pick your <em style={{ fontStyle: 'italic', color: '#C8B8FF' }}>mood.</em></>}
            subtitle="Each theme is a complete visual system — fonts, color, motion, and shape — not just a palette swap. Designed by hand, switchable in a tap."
          />
        </Reveal>
        <Reveal delay={120}>
          <div style={{
            marginTop: 80,
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
            gap: 40,
            justifyItems: 'center',
          }}>
            {themes.map(id => {
              const t = T[id];
              return (
                <div key={id} style={{ textAlign: 'center' }}>
                  <div style={{ filter: `drop-shadow(0 30px 60px ${rgba(t.colors.primary, 0.20)})` }}>
                    <Phone theme={t} screen="today" scale={0.55} active="today"/>
                  </div>
                  <div style={{ marginTop: 18 }}>
                    <div style={{
                      fontFamily: 'Fraunces, serif', fontSize: 22, fontWeight: 500,
                      color: t.colors.primary, letterSpacing: -0.3,
                    }}>{t.label}</div>
                    <div style={{
                      fontFamily: 'Space Grotesk, sans-serif', fontSize: 12,
                      color: 'rgba(255,255,255,0.55)', marginTop: 4, letterSpacing: 0.2,
                    }}>{t.tagline}</div>
                  </div>
                </div>
              );
            })}
          </div>
        </Reveal>
      </div>
    </section>
  );
}

// ── Philosophy — the 7 principles (see docs/PHILOSOPHY.md) ────────────
function Philosophy() {
  const principles = [
    {
      n: '01',
      title: 'Forgiveness over punishment',
      rule: 'No feature punishes the user for a missed day, broken streak, or skipped task. Every fail state has a graceful recovery path.',
    },
    {
      n: '02',
      title: 'User reality over user aspiration',
      rule: 'The app reflects the day the user actually had, not the day they should have had. Plans bend to reality; reality is never wrong.',
    },
    {
      n: '03',
      title: 'Multiple legitimate modes',
      rule: 'Rest, play, and low-output days are first-class states, not "off" states. The app values restoration as much as production.',
    },
    {
      n: '04',
      title: 'Friction calibrated to the brain',
      rule: 'Task difficulty is measured by start-friction — how hard it is to begin — not by importance or time. The app surfaces easy starts when the user is depleted.',
    },
    {
      n: '05',
      title: 'Honest disclosure, no dark patterns',
      rule: 'Every AI feature, every data egress, every paid tier limit is disclosed in plain language before the user encounters it. No retention traps, no manufactured urgency.',
    },
    {
      n: '06',
      title: 'The user is the expert on their own brain',
      rule: 'Defaults are gentle, but every behavior is configurable. The app proposes; it does not prescribe. Any feature can be turned off — including the ones we think are essential.',
    },
    {
      n: '07',
      title: 'Quiet by default',
      rule: 'Notifications, badges, sounds, and alerts are off-by-default or minimal-by-default. The app asks permission to interrupt; it does not assume it.',
    },
  ];

  return (
    <section id="philosophy" style={{ padding: '140px 0', background: '#fff' }}>
      <div style={{ maxWidth: 1240, margin: '0 auto', padding: '0 32px' }}>
        <Reveal>
          <SectionHeader
            eyebrow="Our philosophy"
            title={<>Seven principles. <em style={{ fontStyle: 'italic', color: '#5B47E0' }}>No exceptions.</em></>}
            subtitle="PrismTask is built mental-health-first. These principles are the spec — every feature must pass them before it ships. They exist in writing so we can be held to them."
          />
        </Reveal>
        <Reveal delay={120}>
          <div style={{
            marginTop: 80,
            display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))',
            gap: 1, background: 'rgba(0,0,0,0.06)',
            borderRadius: 18, overflow: 'hidden',
            border: '1px solid rgba(0,0,0,0.06)',
          }}>
            {principles.map(p => (
              <div key={p.n} style={{ background: '#fff', padding: '36px 32px' }}>
                <div style={{
                  fontFamily: 'Fraunces, serif', fontSize: 14, fontWeight: 500,
                  color: '#5B47E0', letterSpacing: 1.2, marginBottom: 14,
                }}>{p.n}</div>
                <h4 style={{
                  fontFamily: 'Fraunces, serif', fontSize: 22, fontWeight: 500,
                  letterSpacing: -0.4, color: '#0F0F12', margin: '0 0 12px',
                  lineHeight: 1.2,
                }}>{p.title}</h4>
                <p style={{
                  fontFamily: 'Space Grotesk, sans-serif', fontSize: 14.5,
                  lineHeight: 1.55, color: '#54545E', margin: 0,
                }}>{p.rule}</p>
              </div>
            ))}
          </div>
        </Reveal>
      </div>
    </section>
  );
}

// ── Screenshots gallery — alternating phone + copy ────────────────────
function Screenshots() {
  const features = [
    {
      eyebrow: 'Today screen',
      title: <>Your day at a <em style={{ fontStyle: 'italic', color: '#5B47E0' }}>glance.</em></>,
      body: 'Greeting, progress ring, daily habits, and a single tap to quick-add. The Today screen is what opens when you launch the app — and most days, the only screen you need.',
      theme: T.VOID, screen: 'today',
    },
    {
      eyebrow: 'Focus timer',
      title: <>Pomodoro, <em style={{ fontStyle: 'italic', color: '#FF2D87' }}>refined.</em></>,
      body: 'Adjustable work and break intervals, energy-aware suggestions, session notes, and a giant clock that\u2019s easy to read mid-flow. Live timer state syncs to your home-screen widget.',
      theme: T.SYNTHWAVE, screen: 'timer',
      reverse: true,
    },
    {
      eyebrow: 'Medication tracker',
      title: <>Doses, <em style={{ fontStyle: 'italic', color: '#5B47E0' }}>not</em> guilt-trips.</>,
      body: 'Slot-based dose tracking with four achievement tiers — Skipped, Essential, Prescription, Complete — and clean backlogging when you take something late. Built with chronic-illness flows in mind.',
      theme: T.VOID, screen: 'meds',
    },
    {
      eyebrow: 'Insights',
      title: <>Where the <em style={{ fontStyle: 'italic', color: '#00FF41' }}>time</em> went.</>,
      body: 'Weekly summary with category breakdown, time-tracked totals, and a streak heatmap. Honest data, not gamified guilt. (Pro only.)',
      theme: T.MATRIX, screen: 'insights',
      reverse: true,
    },
  ];

  return (
    <section style={{ padding: '120px 0', background: '#FAFAF7' }}>
      <div style={{ maxWidth: 1240, margin: '0 auto', padding: '0 32px' }}>
        {features.map((f, i) => (
          <Reveal key={i} delay={0}>
            <div style={{
              display: 'flex', alignItems: 'center', gap: 80,
              flexDirection: f.reverse ? 'row-reverse' : 'row',
              flexWrap: 'wrap', justifyContent: 'center',
              marginBottom: i === features.length - 1 ? 0 : 120,
            }}>
              <div style={{
                flex: '0 0 auto',
                filter: `drop-shadow(0 30px 50px ${rgba(f.theme.colors.primary, 0.18)})`,
              }}>
                <Phone theme={f.theme} screen={f.screen} scale={0.62} active={f.screen}/>
              </div>
              <div style={{ flex: '1 1 360px', maxWidth: 480 }}>
                <div style={{
                  fontFamily: 'Space Grotesk, sans-serif', fontSize: 12, fontWeight: 600,
                  color: '#5B47E0', letterSpacing: 1.4, textTransform: 'uppercase',
                  marginBottom: 16,
                }}>{f.eyebrow}</div>
                <h3 style={{
                  fontFamily: 'Fraunces, serif',
                  fontSize: 'clamp(32px, 4vw, 48px)',
                  fontWeight: 500, letterSpacing: -1.2, lineHeight: 1.05,
                  color: '#0F0F12', margin: 0,
                }}>{f.title}</h3>
                <p style={{
                  marginTop: 20, fontFamily: 'Space Grotesk, sans-serif',
                  fontSize: 17, lineHeight: 1.5, color: '#3D3D45',
                  letterSpacing: -0.1,
                }}>{f.body}</p>
              </div>
            </div>
          </Reveal>
        ))}
      </div>
    </section>
  );
}

// ── Wellness section (ADHD / ND angle) ────────────────────────────────
function Wellness() {
  const pillars = [
    {
      title: 'Forgiveness-first streaks',
      body: 'Miss a day, keep your streak. Configurable grace periods mean a bad week doesn\u2019t erase a great month.',
    },
    {
      title: 'Work-life balance engine',
      body: 'Every task auto-classified into Work, Personal, Self-Care, or Health. See the ratio. Get alerted when one drowns out the rest.',
    },
    {
      title: 'Mood + energy tracking',
      body: 'Quick daily check-in. The app learns your patterns and surfaces what kinds of tasks you actually finish when energy is low.',
    },
    {
      title: 'ND-friendly modes',
      body: 'Brain Mode (low-friction capture), good-enough timer, ship-it celebrations, shake-to-report. Built with neurodivergent users, not for them.',
    },
    {
      title: 'Boundaries & overload protection',
      body: 'Declare quiet hours, work-only windows, or hard category limits. The app enforces them — adjusts notifications, hides triggers.',
    },
    {
      title: 'Weekly review',
      body: 'A guided check-in every Sunday. Reflect, replan, reset. Or skip it. (Forgiveness-first, remember?)',
    },
  ];

  return (
    <section id="wellness" style={{ padding: '140px 0', background: '#fff' }}>
      <div style={{ maxWidth: 1240, margin: '0 auto', padding: '0 32px' }}>
        <Reveal>
          <SectionHeader
            eyebrow="Built for real brains"
            title={<>Productivity that doesn't <em style={{ fontStyle: 'italic', color: '#5B47E0' }}>punish.</em></>}
            subtitle="We don't think missing a day should erase a streak. We don't think your todo list should make you feel worse. Every feature in PrismTask was built to support the days that don't go to plan — not just the ones that do."
          />
        </Reveal>
        <Reveal delay={120}>
          <div style={{
            marginTop: 80,
            display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
            gap: 1, background: 'rgba(0,0,0,0.06)',
            borderRadius: 18, overflow: 'hidden',
            border: '1px solid rgba(0,0,0,0.06)',
          }}>
            {pillars.map((p, i) => (
              <div key={i} style={{ background: '#fff', padding: '36px 32px' }}>
                <h4 style={{
                  fontFamily: 'Fraunces, serif', fontSize: 22, fontWeight: 500,
                  letterSpacing: -0.4, color: '#0F0F12', margin: '0 0 10px',
                }}>{p.title}</h4>
                <p style={{
                  fontFamily: 'Space Grotesk, sans-serif', fontSize: 14.5,
                  lineHeight: 1.55, color: '#54545E', margin: 0,
                }}>{p.body}</p>
              </div>
            ))}
          </div>
        </Reveal>
      </div>
    </section>
  );
}

// ── Pricing ────────────────────────────────────────────────────────────
function Pricing() {
  const free = [
    'All task & project management',
    'Habit tracking with streaks',
    'Focus timer',
    'All 4 themes',
    'Home-screen widgets',
    'Work-life balance engine',
    'Local-only storage',
  ];
  const pro = [
    'Everything in Free',
    'Cloud sync across devices',
    'AI quick-add & weekly planner',
    'Time tracking & analytics',
    'Insights dashboard',
    'Google Drive backup',
    'Gmail / Slack / Calendar integrations',
    'Priority support',
  ];

  return (
    <section id="pricing" style={{ padding: '120px 0', background: '#FAFAF7' }}>
      <div style={{ maxWidth: 1240, margin: '0 auto', padding: '0 32px' }}>
        <Reveal>
          <SectionHeader
            eyebrow="Pricing"
            title={<>Fair. <em style={{ fontStyle: 'italic', color: '#5B47E0' }}>Honest.</em> Mostly free.</>}
            subtitle="The core experience is free forever — because mental-health tools shouldn't sit behind a paywall. Pro unlocks sync, AI, and analytics for the people who want more. No dark patterns, cancel any time."
          />
        </Reveal>
        <div style={{
          marginTop: 60,
          display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
          gap: 24, maxWidth: 920, margin: '60px auto 0',
        }}>
          <Reveal>
            <PriceCard
              name="Free"
              price="$0"
              tagline="Forever. No card needed."
              features={free}
              cta="Get started"
              ctaStyle="outline"
            />
          </Reveal>
          <Reveal delay={120}>
            <PriceCard
              name="Pro"
              price="$7.99"
              priceSuffix="/mo"
              tagline="Or $59.99 / year — save 37% · 7-day free trial"
              features={pro}
              cta="Start 7-day trial"
              ctaStyle="solid"
              featured
            />
          </Reveal>
        </div>
      </div>
    </section>
  );
}

function PriceCard({ name, price, priceSuffix, tagline, features, cta, ctaStyle, featured }) {
  return (
    <div style={{
      padding: '36px 32px', borderRadius: 22,
      background: featured ? '#0F0F12' : '#fff',
      color: featured ? '#fff' : '#0F0F12',
      border: featured ? 'none' : '1px solid rgba(0,0,0,0.08)',
      boxShadow: featured ? '0 20px 50px rgba(91,71,224,0.25)' : 'none',
      position: 'relative',
    }}>
      {featured && (
        <div style={{
          position: 'absolute', top: -12, right: 24,
          padding: '4px 10px', borderRadius: 999,
          background: '#5B47E0', color: '#fff',
          fontFamily: 'Space Grotesk, sans-serif', fontSize: 11, fontWeight: 600,
          letterSpacing: 0.6, textTransform: 'uppercase',
        }}>Most popular</div>
      )}
      <div style={{
        fontFamily: 'Space Grotesk, sans-serif', fontSize: 13, fontWeight: 600,
        color: featured ? 'rgba(255,255,255,0.65)' : '#5B47E0',
        letterSpacing: 1, textTransform: 'uppercase',
      }}>{name}</div>
      <div style={{ marginTop: 14, display: 'flex', alignItems: 'baseline', gap: 4 }}>
        <span style={{
          fontFamily: 'Fraunces, serif', fontSize: 56, fontWeight: 500,
          letterSpacing: -2, lineHeight: 1,
        }}>{price}</span>
        {priceSuffix && (
          <span style={{
            fontFamily: 'Space Grotesk, sans-serif', fontSize: 16,
            color: featured ? 'rgba(255,255,255,0.6)' : '#54545E',
          }}>{priceSuffix}</span>
        )}
      </div>
      <div style={{
        marginTop: 8, fontFamily: 'Space Grotesk, sans-serif',
        fontSize: 14, color: featured ? 'rgba(255,255,255,0.6)' : '#7A7A83',
      }}>{tagline}</div>
      <ul style={{
        listStyle: 'none', padding: 0, margin: '28px 0 0',
        display: 'flex', flexDirection: 'column', gap: 12,
      }}>
        {features.map(f => (
          <li key={f} style={{
            display: 'flex', alignItems: 'flex-start', gap: 10,
            fontFamily: 'Space Grotesk, sans-serif', fontSize: 14.5,
            color: featured ? 'rgba(255,255,255,0.85)' : '#3D3D45',
            lineHeight: 1.4,
          }}>
            <span style={{
              flexShrink: 0, marginTop: 2,
              width: 18, height: 18, borderRadius: 9,
              background: featured ? 'rgba(91,71,224,0.4)' : 'rgba(91,71,224,0.12)',
              color: featured ? '#fff' : '#5B47E0',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: 11, fontWeight: 700,
            }}>✓</span>
            {f}
          </li>
        ))}
      </ul>
      <a href="#waitlist" style={{
        display: 'block', marginTop: 32,
        padding: '14px 0', textAlign: 'center', borderRadius: 999,
        background: ctaStyle === 'solid' ? '#5B47E0' : 'transparent',
        color: ctaStyle === 'solid' ? '#fff' : (featured ? '#fff' : '#0F0F12'),
        border: ctaStyle === 'outline' ? '1px solid rgba(0,0,0,0.15)' : 'none',
        fontFamily: 'Space Grotesk, sans-serif', fontSize: 15, fontWeight: 500,
        textDecoration: 'none',
      }}>{cta}</a>
    </div>
  );
}

// ── Privacy ───────────────────────────────────────────────────────────
function Privacy() {
  return (
    <section style={{ padding: '120px 0', background: '#fff' }}>
      <div style={{ maxWidth: 1100, margin: '0 auto', padding: '0 32px' }}>
        <Reveal>
          <div style={{
            display: 'flex', gap: 60, flexWrap: 'wrap', alignItems: 'center',
          }}>
            <div style={{ flex: '1 1 360px' }}>
              <div style={{
                fontFamily: 'Space Grotesk, sans-serif', fontSize: 12, fontWeight: 600,
                color: '#5B47E0', letterSpacing: 1.4, textTransform: 'uppercase',
                marginBottom: 18,
              }}>Privacy & dignity</div>
              <h3 style={{
                fontFamily: 'Fraunces, serif',
                fontSize: 'clamp(32px, 4vw, 44px)', fontWeight: 500,
                letterSpacing: -1.2, lineHeight: 1.05, color: '#0F0F12', margin: 0,
              }}>
                Your data. <em style={{ fontStyle: 'italic', color: '#5B47E0' }}>Your terms.</em>
              </h3>
              <p style={{
                marginTop: 18, fontFamily: 'Space Grotesk, sans-serif',
                fontSize: 16, lineHeight: 1.55, color: '#3D3D45',
              }}>
                Mood, medication, and check-in data is the most sensitive thing on
                your phone. Everything runs locally first. Cloud sync is optional and
                encrypted in transit. No tracking, no ads, no behavioral profiling — and
                a one-tap export so your history is always yours to take with you.
              </p>
            </div>
            <div style={{
              flex: '1 1 320px', display: 'grid', gridTemplateColumns: '1fr 1fr',
              gap: 16,
            }}>
              <PrivacyCard k="Tracking" v="None"/>
              <PrivacyCard k="Ads" v="None"/>
              <PrivacyCard k="Default storage" v="Local"/>
              <PrivacyCard k="Sensitive data" v="Encrypted"/>
              <PrivacyCard k="Data export" v="JSON / CSV"/>
              <PrivacyCard k="Account delete" v="One tap"/>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

function PrivacyCard({ k, v }) {
  return (
    <div style={{
      padding: 18, borderRadius: 12,
      background: '#FAFAF7', border: '1px solid rgba(0,0,0,0.05)',
    }}>
      <div style={{
        fontFamily: 'Space Grotesk, sans-serif', fontSize: 11, fontWeight: 600,
        color: '#7A7A83', letterSpacing: 1, textTransform: 'uppercase',
      }}>{k}</div>
      <div style={{
        marginTop: 6, fontFamily: 'Fraunces, serif',
        fontSize: 20, fontWeight: 500, color: '#0F0F12', letterSpacing: -0.3,
      }}>{v}</div>
    </div>
  );
}

// ── FAQ ────────────────────────────────────────────────────────────────
function FAQ() {
  const items = [
    { q: 'Is it really free?', a: 'Yes. The full task manager, habit tracker, focus timer, all 4 themes, mood check-ins, and 14 widgets are free forever — because mental-health tools shouldn\u2019t sit behind a paywall. Pro adds sync, AI, and analytics for $7.99/month or $59.99/year (a 37% saving), with a 7-day free trial.' },
    { q: 'What platforms are supported?', a: 'Android (Android 8.0+) and a web app available now. iOS is in development.' },
    { q: 'Does it work offline?', a: 'Yes — everything works offline by default. Sync is opt-in and runs in the background when you have a connection.' },
    { q: 'How does sync work?', a: 'End-to-end Firebase sync between your devices, encrypted in transit. Sync is opt-in — you can stay fully local if you prefer.' },
    { q: 'Is my data private?', a: 'PrismTask doesn\u2019t track you, doesn\u2019t show ads, and doesn\u2019t profile your behavior. Local-first storage by default; cloud sync is opt-in and encrypted in transit. Mood, medication, and check-in data is treated as the most sensitive thing on your phone — because it is.' },
    { q: 'What makes it ADHD-friendly?', a: 'Forgiveness-first streaks, energy-aware Pomodoro, Brain Mode for low-friction capture, shake-to-report, configurable Boundaries to prevent overload, and a Weekly Review that you can skip without consequence.' },
    { q: 'How do themes work?', a: 'Pick one of four — Void, Cyberpunk, Synthwave, or Matrix — in Settings. Each is a complete visual system: fonts, color, motion, shape, and atmosphere. Switch any time.' },
    { q: 'Is this a real mental-health tool?', a: 'PrismTask is built mental-health-first — but it isn\u2019t a replacement for therapy or medical care. It\u2019s a daily-living tool: mood and energy check-ins, medication tracking, gentle streaks, and overload protection, designed alongside neurodivergent and chronically-ill users. If you\u2019re in crisis, please reach out to a clinician or a local crisis line.' },
  ];
  const [open, setOpen] = useState(null);
  return (
    <section id="faq" style={{ padding: '120px 0', background: '#FAFAF7' }}>
      <div style={{ maxWidth: 820, margin: '0 auto', padding: '0 32px' }}>
        <Reveal>
          <SectionHeader
            eyebrow="Common questions"
            title={<>Anything <em style={{ fontStyle: 'italic', color: '#5B47E0' }}>else?</em></>}
            center={false}
          />
        </Reveal>
        <div style={{ marginTop: 50 }}>
          {items.map((it, i) => (
            <Reveal key={i} delay={i * 40}>
              <FAQItem
                q={it.q} a={it.a}
                isOpen={open === i}
                onToggle={() => setOpen(open === i ? null : i)}
              />
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}

function FAQItem({ q, a, isOpen, onToggle }) {
  return (
    <div style={{ borderTop: '1px solid rgba(0,0,0,0.08)' }}>
      <button onClick={onToggle} style={{
        width: '100%', padding: '24px 0',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        background: 'transparent', border: 'none', cursor: 'pointer',
        textAlign: 'left',
        fontFamily: 'Fraunces, serif', fontSize: 20, fontWeight: 500,
        letterSpacing: -0.3, color: '#0F0F12',
      }}>
        <span>{q}</span>
        <span style={{
          flexShrink: 0, marginLeft: 24,
          width: 28, height: 28, borderRadius: 14,
          background: 'rgba(91,71,224,0.1)', color: '#5B47E0',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontFamily: 'Space Grotesk, sans-serif', fontSize: 20,
          fontWeight: 400,
          transform: isOpen ? 'rotate(45deg)' : 'rotate(0deg)',
          transition: 'transform 0.3s cubic-bezier(.2,.65,.3,1)',
        }}>+</span>
      </button>
      <div style={{
        overflow: 'hidden',
        maxHeight: isOpen ? 240 : 0,
        opacity: isOpen ? 1 : 0,
        transition: 'max-height 0.4s cubic-bezier(.2,.65,.3,1), opacity 0.3s',
      }}>
        <p style={{
          padding: '0 0 24px', margin: 0,
          fontFamily: 'Space Grotesk, sans-serif', fontSize: 16,
          lineHeight: 1.55, color: '#3D3D45',
          maxWidth: 660,
        }}>{a}</p>
      </div>
    </div>
  );
}

// ── Final CTA ──────────────────────────────────────────────────────────
function FinalCTA() {
  return (
    <section style={{ padding: '120px 32px', background: '#0F0F12', color: '#fff', textAlign: 'center' }}>
      <Reveal>
        <h2 style={{
          fontFamily: 'Fraunces, serif',
          fontSize: 'clamp(40px, 5.5vw, 72px)', fontWeight: 500,
          letterSpacing: -1.8, lineHeight: 1.02,
          margin: 0, maxWidth: 900, marginLeft: 'auto', marginRight: 'auto',
        }}>
          Get the app that finally <em style={{ fontStyle: 'italic', color: '#C8B8FF' }}>gets it.</em>
        </h2>
        <p style={{
          marginTop: 24, maxWidth: 560, marginLeft: 'auto', marginRight: 'auto',
          fontFamily: 'Space Grotesk, sans-serif', fontSize: 18, lineHeight: 1.5,
          color: 'rgba(255,255,255,0.65)',
        }}>
          Join the waitlist. We're building PrismTask carefully because mental health deserves better than another productivity app. One quiet note when it ships — no spam, no growth hacks, no follow-ups.
        </p>
        <a href="#waitlist" style={{
          display: 'inline-block', marginTop: 36,
          padding: '16px 32px', borderRadius: 999,
          background: '#5B47E0', color: '#fff',
          fontFamily: 'Space Grotesk, sans-serif', fontSize: 15, fontWeight: 500,
          textDecoration: 'none',
          boxShadow: '0 12px 32px rgba(91,71,224,0.4)',
          transition: 'transform 0.2s, box-shadow 0.2s',
        }}>Join the waitlist →</a>
      </Reveal>
    </section>
  );
}

// ── Footer ─────────────────────────────────────────────────────────────
function Footer() {
  return (
    <footer style={{
      padding: '60px 32px 40px',
      background: '#08080B', color: 'rgba(255,255,255,0.6)',
      fontFamily: 'Space Grotesk, sans-serif',
    }}>
      <div style={{ maxWidth: 1240, margin: '0 auto' }}>
        <div style={{
          display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
          gap: 32, paddingBottom: 40,
        }}>
          <div>
            <div style={{
              fontFamily: 'Fraunces, serif', fontSize: 18, fontWeight: 600,
              color: '#fff', letterSpacing: -0.4, marginBottom: 12,
            }}>PrismTask</div>
            <p style={{ fontSize: 13, lineHeight: 1.55, margin: 0 }}>
              A mental-health-first task manager for real brains.
            </p>
          </div>
          {[
            { title: 'Product', links: [
              { label: 'Features',  href: '#features' },
              { label: 'Themes',    href: '#themes' },
              { label: 'Pricing',   href: '#pricing' },
              { label: 'Widgets',   href: '#features' },
            ]},
            { title: 'Company', links: [
              { label: 'Contact', href: 'mailto:support@prismtask.app' },
              { label: 'Press',   href: 'mailto:support@prismtask.app?subject=PrismTask%20press%20inquiry' },
            ]},
            { title: 'Resources', links: [
              { label: 'Privacy policy', href: 'privacy-policy.html' },
              { label: 'Terms',          href: 'terms-of-service.html' },
              { label: 'Accessibility',  href: '#wellness' },
              { label: 'Support',        href: 'mailto:support@prismtask.app?subject=PrismTask%20support' },
            ]},
          ].map(col => (
            <div key={col.title}>
              <div style={{
                fontSize: 11, fontWeight: 600, color: '#fff',
                letterSpacing: 1, textTransform: 'uppercase', marginBottom: 14,
              }}>{col.title}</div>
              <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
                {col.links.map(({ label, href, external }) => (
                  <li key={label}>
                    <a
                      href={href}
                      {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
                      style={{ color: 'rgba(255,255,255,0.55)', textDecoration: 'none', fontSize: 13 }}
                    >{label}</a>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
        <div style={{
          borderTop: '1px solid rgba(255,255,255,0.08)',
          paddingTop: 24,
          display: 'flex', justifyContent: 'space-between',
          flexWrap: 'wrap', gap: 12,
          fontSize: 12, color: 'rgba(255,255,255,0.4)',
        }}>
          <span>© 2026 Avery Corp · All rights reserved</span>
          <span>Made with care for the brains that need it.</span>
        </div>
      </div>
    </footer>
  );
}

// ── Helpers ────────────────────────────────────────────────────────────
function SectionHeader({ eyebrow, title, subtitle, light, center = true }) {
  return (
    <div style={{ textAlign: center ? 'center' : 'left', maxWidth: 760, margin: center ? '0 auto' : '0' }}>
      <div style={{
        fontFamily: 'Space Grotesk, sans-serif', fontSize: 12, fontWeight: 600,
        color: light ? 'rgba(200,184,255,0.85)' : '#5B47E0',
        letterSpacing: 1.6, textTransform: 'uppercase',
        marginBottom: 18,
      }}>{eyebrow}</div>
      <h2 style={{
        fontFamily: 'Fraunces, serif',
        fontSize: 'clamp(36px, 5vw, 64px)', fontWeight: 500,
        letterSpacing: -1.6, lineHeight: 1.02,
        color: light ? '#fff' : '#0F0F12', margin: 0,
      }}>{title}</h2>
      {subtitle && (
        <p style={{
          marginTop: 20, fontFamily: 'Space Grotesk, sans-serif',
          fontSize: 17, lineHeight: 1.5,
          color: light ? 'rgba(255,255,255,0.7)' : '#54545E',
          maxWidth: center ? 600 : 'none',
          margin: center ? '20px auto 0' : '20px 0 0',
        }}>{subtitle}</p>
      )}
    </div>
  );
}

function rgba(hex, a) {
  if (!hex || hex.startsWith('rgba')) return hex || `rgba(0,0,0,${a})`;
  let h = hex.replace('#', '');
  if (h.length === 3) h = h.split('').map(c => c + c).join('');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${a})`;
}

// ── App ────────────────────────────────────────────────────────────────
function App() {
  return (
    <>
      <Nav/>
      <Hero/>
      <Features/>
      <ThemeShowcase/>
      <Philosophy/>
      <Screenshots/>
      <Wellness/>
      <Pricing/>
      <Privacy/>
      <FAQ/>
      <FinalCTA/>
      <Footer/>
    </>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);

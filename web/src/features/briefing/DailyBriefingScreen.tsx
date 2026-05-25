import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertTriangle,
  Calendar,
  CheckCircle2,
  Clock,
  Loader2,
  Sparkles,
  Sun,
  TriangleAlert,
} from 'lucide-react';
import { format, parseISO } from 'date-fns';
import { toast } from 'sonner';
import { aiApi } from '@/api/ai';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { ProUpgradeModal } from '@/components/shared/ProUpgradeModal';
import { useProFeature } from '@/hooks/useProFeature';
import type {
  BriefingDayType,
  DailyBriefingResponse,
} from '@/types/briefingPlanner';

const DAY_TYPE_STYLES: Record<
  BriefingDayType,
  { label: string; className: string; Icon: typeof Sun }
> = {
  light: {
    label: 'Light day',
    className:
      'border-emerald-500/40 bg-emerald-500/5 text-emerald-600 dark:text-emerald-400',
    Icon: Sun,
  },
  moderate: {
    label: 'Moderate day',
    className:
      'border-blue-500/40 bg-blue-500/5 text-blue-600 dark:text-blue-400',
    Icon: Calendar,
  },
  heavy: {
    label: 'Heavy day',
    className:
      'border-amber-500/40 bg-amber-500/5 text-amber-600 dark:text-amber-400',
    Icon: TriangleAlert,
  },
};

export function DailyBriefingScreen() {
  const { isPro, showUpgrade, setShowUpgrade } = useProFeature();
  const [briefing, setBriefing] = useState<DailyBriefingResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [date] = useState(() => format(new Date(), 'yyyy-MM-dd'));

  const generate = async (opts: { silent?: boolean } = {}) => {
    if (!isPro) {
      setShowUpgrade(true);
      return;
    }
    setLoading(true);
    try {
      const response = await aiApi.dailyBriefing(
        { date },
        { suppressErrorToast: opts.silent },
      );
      setBriefing(response);
    } catch (e) {
      // The on-mount auto-generate is best-effort: if it fails (offline,
      // AI temporarily unavailable, etc.) fall back silently to the
      // empty state instead of flashing a scary error toast (bug B-06).
      // An explicit user-triggered generate still surfaces the error.
      if (!opts.silent) {
        toast.error((e as Error).message || 'Failed to generate briefing');
      }
    } finally {
      setLoading(false);
    }
  };

  // Auto-generate on mount for Pro users — matches Android's behavior
  // where the briefing card populates in the background on Today.
  useEffect(() => {
    if (isPro && !briefing && !loading) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: auto-generate briefing once on mount for Pro users
      generate({ silent: true });
    }
    // Intentionally omitting deps: we only want the initial auto-generate,
    // not a re-trigger each time these identities change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const dayTypeBadge = briefing ? DAY_TYPE_STYLES[briefing.day_type] : null;

  return (
    <div className="mx-auto max-w-3xl pb-16">
      <header className="mb-6 flex items-start justify-between gap-4">
        <div>
          <h1 className="flex items-center gap-2 text-xl font-semibold text-[var(--color-text-primary)]">
            <Sparkles
              className="h-5 w-5 text-[var(--color-accent)]"
              aria-hidden="true"
            />
            Daily Briefing
          </h1>
          <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
            {format(parseISO(date), 'EEEE, MMMM d')}
          </p>
        </div>
        <Button
          onClick={() => generate()}
          disabled={loading}
          variant={briefing ? 'secondary' : 'primary'}
        >
          {loading ? (
            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
          ) : null}
          {briefing ? 'Regenerate' : 'Generate briefing'}
        </Button>
      </header>

      {loading && !briefing && (
        <div className="flex items-center gap-3 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-6">
          <Loader2 className="h-5 w-5 animate-spin text-[var(--color-accent)]" />
          <span className="text-sm text-[var(--color-text-primary)]">
            Reading today's tasks and habits…
          </span>
        </div>
      )}

      {!loading && !briefing && (
        <EmptyState
          title={isPro ? 'No briefing yet' : 'Pro feature'}
          description={
            isPro
              ? 'Tap "Generate briefing" to get an AI summary of the day.'
              : 'Daily AI briefings are part of Pro. Upgrade to unlock them.'
          }
        />
      )}

      {briefing && (
        <div className="flex flex-col gap-4">
          {dayTypeBadge && (
            <div
              className={`inline-flex items-center gap-2 self-start rounded-full border px-3 py-1 text-xs font-medium ${dayTypeBadge.className}`}
              role="status"
            >
              <dayTypeBadge.Icon className="h-3.5 w-3.5" aria-hidden="true" />
              {dayTypeBadge.label}
            </div>
          )}

          <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-5">
            <p className="text-base text-[var(--color-text-primary)]">
              {briefing.greeting}
            </p>
          </section>

          {briefing.top_priorities.length > 0 && (
            <Section title="Top Priorities" icon={CheckCircle2}>
              <ul className="space-y-2">
                {briefing.top_priorities.map((p) => (
                  <li
                    key={p.task_id}
                    className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3"
                  >
                    <Link
                      to={`/tasks/${p.task_id}`}
                      className="text-sm font-medium text-[var(--color-text-primary)] hover:underline"
                    >
                      {p.title}
                    </Link>
                    <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
                      {p.reason}
                    </p>
                  </li>
                ))}
              </ul>
            </Section>
          )}

          {briefing.heads_up.length > 0 && (
            <Section title="Heads Up" icon={AlertTriangle}>
              <ul className="space-y-1.5 text-sm text-[var(--color-text-primary)]">
                {briefing.heads_up.map((h, i) => (
                  <li
                    key={i}
                    className="rounded-md bg-amber-500/5 px-3 py-2 text-[var(--color-text-primary)]"
                  >
                    {h}
                  </li>
                ))}
              </ul>
            </Section>
          )}

          {briefing.suggested_order.length > 0 && (
            <Section title="Suggested Order" icon={Clock}>
              <ul className="space-y-2">
                {briefing.suggested_order.map((s, i) => (
                  <li
                    key={`${s.task_id}-${i}`}
                    className="flex items-start gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3"
                  >
                    <span className="mt-0.5 text-xs font-semibold text-[var(--color-accent)]">
                      {s.suggested_time}
                    </span>
                    <div className="min-w-0 flex-1">
                      <Link
                        to={`/tasks/${s.task_id}`}
                        className="text-sm font-medium text-[var(--color-text-primary)] hover:underline"
                      >
                        {s.title}
                      </Link>
                      <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
                        {s.reason}
                      </p>
                    </div>
                  </li>
                ))}
              </ul>
            </Section>
          )}

          {briefing.habit_reminders.length > 0 && (
            <Section title="Habit Reminders" icon={Sun}>
              <ul className="flex flex-wrap gap-1.5">
                {briefing.habit_reminders.map((h, i) => (
                  <li
                    key={i}
                    className="rounded-full border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2.5 py-1 text-xs text-[var(--color-text-primary)]"
                  >
                    {h}
                  </li>
                ))}
              </ul>
            </Section>
          )}
        </div>
      )}

      <ProUpgradeModal
        isOpen={showUpgrade}
        onClose={() => setShowUpgrade(false)}
        featureName="Daily Briefing"
        featureDescription="AI summaries of your day, tailored to your tasks, habits, and calendar."
      />
    </div>
  );
}

function Section({
  title,
  icon: Icon,
  children,
}: {
  title: string;
  icon: typeof Sun;
  children: React.ReactNode;
}) {
  return (
    <section>
      <h2 className="mb-2 flex items-center gap-2 text-sm font-semibold text-[var(--color-text-primary)]">
        <Icon
          className="h-4 w-4 text-[var(--color-text-secondary)]"
          aria-hidden="true"
        />
        {title}
      </h2>
      {children}
    </section>
  );
}

import { Link } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

/**
 * Shared scaffold for the Notifications-Hub sub-screens. Provides the
 * back link to `/settings/notifications` and a title + subtitle slot so
 * every sub-screen stays visually consistent (mirrors Android's
 * `NotificationSubScreenScaffold`).
 */
export function NotificationsSubScreenLayout({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="mx-auto max-w-2xl">
      <Link
        to="/settings/notifications"
        className="mb-4 inline-flex items-center gap-1 text-sm text-[var(--color-accent)] hover:underline"
      >
        <ArrowLeft className="h-4 w-4" />
        Notifications
      </Link>
      <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
        {title}
      </h1>
      {subtitle && (
        <p className="mt-1 mb-5 text-sm text-[var(--color-text-secondary)]">
          {subtitle}
        </p>
      )}
      {!subtitle && <div className="mb-5" />}
      {children}
    </div>
  );
}

export function SubSectionHeading({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="mt-5 mb-2 text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">
      {children}
    </h2>
  );
}

export function CardSurface({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      {children}
    </div>
  );
}

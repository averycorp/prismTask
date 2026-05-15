import { ExternalLink } from 'lucide-react';

/**
 * About — static version info + policy links. Matches the
 * Android `AboutSection` shape. Version is hard-coded on web; when
 * we start auto-versioning the web build a build-time replacement
 * would swap this for the real value.
 */
export function AboutSection() {
  return (
    <div className="flex flex-col gap-3 text-sm text-[var(--color-text-primary)]">
      <Row label="Web client" value="prismtask-web" />
      <Row label="Backend" value="averytask-production.up.railway.app" />
      <LinkRow
        label="Privacy policy"
        href="https://github.com/akarlin3/prismTask/blob/main/docs/PRIVACY_POLICY.md"
      />
      <LinkRow
        label="Terms of service"
        href="https://github.com/akarlin3/prismTask/blob/main/docs/TERMS_OF_SERVICE.md"
      />
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-[var(--color-text-secondary)]">{label}</span>
      <span className="font-mono text-xs">{value}</span>
    </div>
  );
}

function LinkRow({ label, href }: { label: string; href: string }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noreferrer noopener"
      className="flex items-center justify-between rounded-md hover:bg-[var(--color-bg-secondary)]"
    >
      <span className="text-[var(--color-text-primary)]">{label}</span>
      <ExternalLink
        className="h-4 w-4 text-[var(--color-text-secondary)]"
        aria-hidden="true"
      />
    </a>
  );
}

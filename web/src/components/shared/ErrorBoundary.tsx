import { AlertTriangle, RefreshCw } from 'lucide-react';
import type { FallbackProps } from 'react-error-boundary';

export function ErrorBoundaryFallback({ error, resetErrorBoundary }: FallbackProps) {
  const handleReload = () => {
    window.location.reload();
  };

  const errorMessage = error instanceof Error ? error.message : String(error);
  const errorStack = error instanceof Error ? error.stack : '';

  return (
    <div className="flex min-h-screen items-center justify-center bg-[var(--color-bg-primary)] p-4">
      <div className="max-w-md text-center">
        <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-full bg-red-500/10">
          <AlertTriangle className="h-8 w-8 text-red-500" />
        </div>
        <h1 className="mb-2 text-xl font-semibold text-[var(--color-text-primary)]">
          Something Went Wrong
        </h1>
        <p className="mb-6 text-sm text-[var(--color-text-secondary)]">
          An unexpected error occurred. Please try reloading the page.
        </p>
        {Boolean(import.meta.env.DEV) && Boolean(error) && (
          <pre className="mb-6 max-h-40 overflow-auto rounded-lg bg-[var(--color-bg-secondary)] p-3 text-left text-xs text-red-400">
            {String(errorMessage)}
            {'\n'}
            {String(errorStack)}
          </pre>
        )}
        <div className="flex justify-center gap-3">
          <button
            onClick={resetErrorBoundary}
            className="rounded-lg border border-[var(--color-border)] px-4 py-2 text-sm font-medium text-[var(--color-text-primary)] transition-colors hover:bg-[var(--color-bg-secondary)]"
          >
            Try Again
          </button>
          <button
            onClick={handleReload}
            className="flex items-center gap-2 rounded-lg bg-[var(--color-accent)] px-4 py-2 text-sm font-medium text-white transition-colors hover:opacity-90"
          >
            <RefreshCw className="h-4 w-4" />
            Reload Page
          </button>
        </div>
        <p className="mt-6 text-xs text-[var(--color-text-secondary)]">
          If this problem persists, please{' '}
          <a
            href="https://github.com/akarlin3/prismtask/issues"
            target="_blank"
            rel="noopener noreferrer"
            className="text-[var(--color-accent)] hover:underline"
          >
            report an issue
          </a>
          .
        </p>
      </div>
    </div>
  );
}

export { ErrorBoundary } from 'react-error-boundary';

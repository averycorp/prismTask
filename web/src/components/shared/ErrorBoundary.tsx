import { type ReactNode } from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import { ErrorBoundary as ReactErrorBoundary } from 'react-error-boundary';

interface ErrorFallbackProps {
  error: Error;
  resetErrorBoundary: () => void;
}

function ErrorFallback({ error, resetErrorBoundary }: ErrorFallbackProps) {
  const handleReload = () => {
    window.location.reload();
  };

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
        {import.meta.env.DEV && error && (
          <pre className="mb-6 max-h-40 overflow-auto rounded-lg bg-[var(--color-bg-secondary)] p-3 text-left text-xs text-red-400">
            {error.message}
            {'\n'}
            {error.stack}
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

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
}

export function ErrorBoundary({ children, fallback }: Props) {
  return (
    <ReactErrorBoundary
      fallbackRender={({ error, resetErrorBoundary }) => {
        if (fallback) {
          return fallback;
        }
        return (
          <ErrorFallback
            error={error instanceof Error ? error : new Error(String(error))}
            resetErrorBoundary={resetErrorBoundary}
          />
        );
      }}
      onError={(error, info) => {
        console.error('ErrorBoundary caught:', error, info);
      }}
    >
      {children}
    </ReactErrorBoundary>
  );
}

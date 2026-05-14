import '@testing-library/jest-dom/vitest';
// jsdom doesn't ship IndexedDB. fake-indexeddb provides an in-memory
// implementation so tests can exercise the IDB framework + consumers
// (`web/src/lib/idb/`) without spinning up a real browser.
import 'fake-indexeddb/auto';

// Vitest 4 + jsdom 29 ship `localStorage` as a non-functional stub (the
// `--localstorage-file` regression — see the warning printed at startup).
// Install a minimal in-memory shim so any production code that calls
// `localStorage.removeItem` / `clear` / etc. during a test doesn't blow
// up. Idempotent: only replaces it when the stub is missing `clear`.
(function installLocalStorageShim() {
  const existing = (globalThis as { localStorage?: Storage }).localStorage;
  if (existing && typeof existing.clear === 'function') return;
  const backing = new Map<string, string>();
  const shim: Storage = {
    get length() {
      return backing.size;
    },
    clear: () => backing.clear(),
    getItem: (key: string) => (backing.has(key) ? backing.get(key)! : null),
    key: (index: number) => Array.from(backing.keys())[index] ?? null,
    removeItem: (key: string) => {
      backing.delete(key);
    },
    setItem: (key: string, value: string) => {
      backing.set(key, String(value));
    },
  };
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: shim,
  });
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: shim,
    });
  }
})();

// jsdom doesn't implement window.matchMedia. Stub a no-match version so
// hooks like useMediaQuery (and any component that goes through Modal /
// ResponsiveDialog / similar) work in tests. Tests can override per-call
// via `vi.spyOn(window, 'matchMedia')` if they need a specific result.
if (typeof window !== 'undefined' && typeof window.matchMedia !== 'function') {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}

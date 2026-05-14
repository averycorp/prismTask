# PrismTask Web

[![Web CI](https://github.com/averycorp/prismTask/actions/workflows/web-ci.yml/badge.svg)](https://github.com/averycorp/prismTask/actions/workflows/web-ci.yml)

The web client for [PrismTask](../README.md) — a full-featured task manager with AI-powered NLP, habit tracking, calendar views, and productivity analytics. Built with React, TypeScript, and Vite, connecting to the shared FastAPI backend.

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Framework | React 19 | Component-based UI |
| Language | TypeScript 6 | Type safety |
| Build | Vite 8 | Dev server, HMR, production bundling |
| Styling | TailwindCSS 4 | Utility-first CSS |
| Routing | React Router DOM 7 | Client-side navigation with lazy loading |
| State | Zustand 5 | Lightweight global state management |
| Forms | React Hook Form + Zod | Validation and form state |
| HTTP | Axios | API client with auth interceptors |
| Charts | Recharts | Dashboard and analytics visualizations |
| Drag & Drop | @dnd-kit | Sortable task lists |
| Icons | Lucide React | Consistent icon set |
| Toasts | Sonner | Notification toasts |
| Unit Tests | Vitest + Testing Library | Component and utility tests |
| E2E Tests | Playwright | Browser automation tests |

## Getting Started

### Prerequisites

- Node.js 22+
- npm

### Setup

```bash
cd web
npm install
```

Copy the environment file and configure the API URL:

```bash
cp .env.example .env.local
```

Edit `.env.local` if you need to point to a local backend:

```env
VITE_API_BASE_URL=http://localhost:8000/api/v1
```

### Development

```bash
npm run dev
```

Opens the app at [http://localhost:5173](http://localhost:5173). The dev server proxies `/api` requests to the backend configured in `vite.config.ts`.

### Build

```bash
npm run build
```

Outputs production assets to `dist/`. The build runs TypeScript type checking before bundling.

## Project Structure

```
web/src/
├── api/                    # Backend API client modules
│   ├── client.ts           # Axios instance with auth interceptors
│   ├── auth.ts             # Login, register, token refresh
│   ├── tasks.ts            # Task CRUD
│   ├── projects.ts         # Project CRUD
│   ├── goals.ts            # Goal CRUD
│   ├── habits.ts           # Habit CRUD and completions
│   ├── tags.ts             # Tag management
│   ├── templates.ts        # Task template operations
│   ├── parse.ts            # NLP task parsing
│   ├── search.ts           # Full-text search
│   ├── dashboard.ts        # Dashboard summary and stats
│   ├── ai.ts               # AI features (Eisenhower, Pomodoro)
│   ├── sync.ts             # Cloud sync triggers
│   └── export.ts           # Data export/import
├── components/
│   ├── layout/             # App shell, header, sidebar, mobile nav
│   ├── shared/             # Reusable components (TaskRow, NLPInput,
│   │                       #   PriorityBadge, SearchModal, TagChip, etc.)
│   └── ui/                 # Base UI primitives (Button, Modal, Input,
│                           #   Tabs, Checkbox, Spinner, etc.)
├── features/               # Feature screens (one folder per top-level route)
│   ├── auth/               # Login and registration (Firebase Auth)
│   ├── onboarding/         # 9-page onboarding wizard + theme picker
│   ├── today/              # Today focus screen + briefing teaser
│   ├── tasks/              # Task list, detail, and editor
│   ├── projects/           # Project list and detail
│   ├── habits/             # Habit list and analytics
│   ├── calendar/           # Week, month, and timeline views
│   ├── eisenhower/         # Eisenhower matrix + classify_text modal
│   ├── pomodoro/           # Pomodoro timer + AI coaching panel
│   ├── briefing/           # AI daily briefing (Pro)
│   ├── planner/            # AI weekly planner (Pro)
│   ├── timeblock/          # AI time blocking with preview (Pro)
│   ├── extract/            # AI conversation-to-task extraction (Pro)
│   ├── batch/              # NLP batch-ops preview + undo
│   ├── checkin/            # Morning check-in
│   ├── weeklyreview/       # Guided weekly review
│   ├── mood/               # Mood + energy logging and analytics
│   ├── boundaries/         # Boundary rule editor
│   ├── focus/              # Focus release / good-enough timer
│   ├── medication/         # Medication slot editor + per-day tier picker
│   ├── schoolwork/         # Schoolwork mode (per-class rows)
│   ├── daily-essentials/   # Daily essentials configuration
│   ├── analytics/          # Productivity + time-tracking dashboards (Pro)
│   ├── templates/          # Task / habit / project template list + authoring
│   ├── archive/            # Archived tasks
│   ├── admin/              # Admin-only diagnostics
│   └── settings/           # App settings
├── hooks/                  # Custom React hooks
│   ├── useAuth.ts          # Firebase Auth state + sign-in actions
│   ├── useCalendarTasks.ts # Calendar-filtered task queries
│   ├── useDateNavigation.ts# Week/month navigation helpers
│   ├── useFirestoreSync.ts # Mounts cross-device Firestore listeners at sign-in
│   ├── useKeyboardShortcuts.ts # Global keyboard shortcut handler
│   ├── useMediaQuery.ts    # Responsive breakpoint detection
│   ├── useProFeature.ts    # Pro feature gating (two-tier: Free / Pro)
│   └── useTheme.ts         # Theme preference management
├── routes/
│   ├── index.tsx           # Route definitions with lazy loading
│   └── ProtectedRoute.tsx  # Auth-gated route wrapper
├── stores/                 # Zustand state stores
│   ├── authStore.ts        # Auth tokens and user state
│   ├── taskStore.ts        # Task data and operations
│   ├── projectStore.ts     # Project data and operations
│   ├── habitStore.ts       # Habit data and operations
│   ├── tagStore.ts         # Tag data
│   ├── templateStore.ts    # Template data and operations
│   ├── settingsStore.ts    # User preferences
│   ├── themeStore.ts       # Light/dark theme state
│   └── uiStore.ts          # UI state (modals, sidebar, etc.)
├── types/                  # TypeScript type definitions
│   ├── task.ts, project.ts, goal.ts, habit.ts, tag.ts, template.ts
│   ├── auth.ts             # Auth request/response types
│   └── api.ts              # Shared API types
├── utils/                  # Utility functions
│   ├── dates.ts            # Date formatting and helpers
│   ├── nlp.ts              # Client-side NLP parsing
│   ├── priority.ts         # Priority labels and colors
│   ├── urgency.ts          # Urgency score calculation
│   ├── recurrence.ts       # Recurrence rule helpers
│   ├── streaks.ts          # Habit streak calculation
│   ├── export.ts           # JSON/CSV export helpers
│   ├── import.ts           # JSON import helpers
│   ├── notifications.ts    # Browser notification helpers
│   └── __tests__/          # Utility unit tests
├── test/
│   └── setup.ts            # Vitest global setup
├── App.tsx                 # Root component
├── main.tsx                # Entry point
└── index.css               # Global styles and Tailwind imports
```

## Routes

| Path | Screen | Loading |
|------|--------|---------|
| `/login` | Login | Eager |
| `/register` | Register | Eager |
| `/onboarding` | Onboarding wizard (gated by `users/{uid}.onboardingCompletedAt`) | Eager |
| `/` | Today | Eager |
| `/tasks` | Task List | Eager |
| `/tasks/:id` | Task Detail | Lazy |
| `/projects` | Project List | Eager |
| `/projects/:id` | Project Detail | Lazy |
| `/projects/:id/roadmap` | Project Roadmap | Lazy |
| `/habits` | Habit List | Lazy |
| `/habits/:id/analytics` | Habit Analytics | Lazy |
| `/calendar` | Calendar Redirect | Lazy |
| `/calendar/week` | Week View | Lazy |
| `/calendar/month` | Month View | Lazy |
| `/calendar/timeline` | Timeline View | Lazy |
| `/eisenhower` | Eisenhower Matrix (+ classify_text modal) | Lazy |
| `/pomodoro` | Pomodoro Timer (+ AI coaching panel) | Lazy |
| `/timeblock` | AI Time Blocking (Pro) | Lazy |
| `/weekly-review` | Weekly Review (synthesizer) | Lazy |
| `/templates` | Template List + authoring | Lazy |
| `/schoolwork` | Schoolwork mode | Lazy |
| `/archive` | Archive | Lazy |
| `/settings` | Settings | Lazy |
| `/admin/logs` | Admin diagnostics (gated) | Lazy |
| `/batch/preview` | NLP batch-ops preview + undo | Lazy |
| `/briefing` | AI Daily Briefing (Pro) | Lazy |
| `/planner` | AI Weekly Planner (Pro) | Lazy |
| `/analytics` | Productivity + time-tracking dashboards (Pro) | Lazy |
| `/extract` | AI conversation-to-task extraction (Pro) | Lazy |
| `/medication` | Medication slot editor + per-day tier picker | Lazy |
| `/mood` | Mood + energy logging and analytics | Lazy |
| `/focus` | Focus Release / good-enough timer | Lazy |

Auth + onboarding screens are public/standalone. All other routes are protected and render inside the `AppShell` layout (header + sidebar + mobile nav). Any unmatched path redirects to `/`.

## Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start Vite dev server with HMR |
| `npm run build` | Type-check and build for production |
| `npm run preview` | Preview production build locally |
| `npm run lint` | Run ESLint |
| `npm run test` | Run Vitest in watch mode |
| `npm run test:run` | Run Vitest once (CI) |
| `npm run test:coverage` | Run Vitest with coverage report |
| `npm run test:e2e` | Run Playwright E2E tests |
| `npm run test:e2e:ui` | Run Playwright with interactive UI |

## Architecture

- **State management**: Zustand stores per domain (tasks, projects, habits, etc.) with Axios API calls. Stores expose actions and selectors consumed by components.
- **Routing**: React Router v7 with `createBrowserRouter`. Core screens are eagerly loaded; secondary screens use `React.lazy` with skeleton fallbacks.
- **Auth**: Firebase Auth signs the user in client-side (Google / email-link / etc.); the resulting ID token is exchanged for a backend JWT via `/auth/firebase` and stored under `prismtask_access_token` for Axios `Authorization: Bearer` calls. The Axios client intercepts 401 responses and attempts token refresh. Auth state lives in `authStore`.
- **API proxy**: In development, Vite proxies `/api` to the backend so the frontend avoids CORS issues.
- **Build optimization**: Vite splits vendor chunks (React, UI libs, dnd-kit, date-fns, form libs) for better caching.
- **Responsive**: `useMediaQuery` hook drives layout changes. `AppShell` renders a sidebar on desktop and a bottom nav on mobile.

## CI

The [Web CI workflow](../.github/workflows/web-ci.yml) runs on pushes to `main` and PRs that touch `web/**`:

1. **Lint & Test** — `npm ci` → `lint` → `test:run` → `build`
2. **E2E** (after lint passes) — Playwright tests against a Chromium build; report uploaded on failure

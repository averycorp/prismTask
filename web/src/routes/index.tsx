import { lazy, Suspense, type ComponentType } from 'react';
import {
  createBrowserRouter,
  Navigate,
  type RouteObject,
} from 'react-router-dom';
import { AppShell } from '@/components/layout/AppShell';
import { ProtectedRoute } from './ProtectedRoute';
import { OnboardingGate } from './OnboardingGate';
import { RestorePendingGate } from './RestorePendingGate';
import { TaskListSkeleton, ProjectListSkeleton, HabitListSkeleton, SettingsSkeleton } from '@/components/shared/SkeletonLoader';

// Auth screens (eagerly loaded — first screens users see)
import { LoginScreen } from '@/features/auth/LoginScreen';
import { RegisterScreen } from '@/features/auth/RegisterScreen';

// Core screens (eagerly loaded — most common)
import { TodayScreen } from '@/features/today/TodayScreen';
import { TaskListScreen } from '@/features/tasks/TaskListScreen';
import { ProjectListScreen } from '@/features/projects/ProjectListScreen';

// Lazy-loaded screens (loaded on demand)
/* eslint-disable react-refresh/only-export-components */
const TaskDetailScreen = lazy(() => import('@/features/tasks/TaskDetailScreen').then(m => ({ default: m.TaskDetailScreen })));
const ProjectDetailScreen = lazy(() => import('@/features/projects/ProjectDetailScreen').then(m => ({ default: m.ProjectDetailScreen })));
const ProjectRoadmapScreen = lazy(() => import('@/features/projects/ProjectRoadmapScreen').then(m => ({ default: m.ProjectRoadmapScreen })));
const HabitListScreen = lazy(() => import('@/features/habits/HabitListScreen').then(m => ({ default: m.HabitListScreen })));
const HabitAnalyticsScreen = lazy(() => import('@/features/habits/HabitAnalyticsScreen').then(m => ({ default: m.HabitAnalyticsScreen })));
const WeekViewScreen = lazy(() => import('@/features/calendar/WeekViewScreen').then(m => ({ default: m.WeekViewScreen })));
const MonthViewScreen = lazy(() => import('@/features/calendar/MonthViewScreen').then(m => ({ default: m.MonthViewScreen })));
const TimelineScreen = lazy(() => import('@/features/calendar/TimelineScreen').then(m => ({ default: m.TimelineScreen })));
const CalendarRedirect = lazy(() => import('@/features/calendar/CalendarRedirect').then(m => ({ default: m.CalendarRedirect })));
const EisenhowerScreen = lazy(() => import('@/features/eisenhower/EisenhowerScreen').then(m => ({ default: m.EisenhowerScreen })));
const PomodoroScreen = lazy(() => import('@/features/pomodoro/PomodoroScreen').then(m => ({ default: m.PomodoroScreen })));
const TimeBlockScreen = lazy(() => import('@/features/timeblock/TimeBlockScreen').then(m => ({ default: m.TimeBlockScreen })));
const WeeklyReviewScreen = lazy(() => import('@/features/weeklyreview/WeeklyReviewScreen').then(m => ({ default: m.WeeklyReviewScreen })));
const TemplateListScreen = lazy(() => import('@/features/templates/TemplateListScreen').then(m => ({ default: m.TemplateListScreen })));
const ArchiveScreen = lazy(() => import('@/features/archive/ArchiveScreen').then(m => ({ default: m.ArchiveScreen })));
const SettingsScreen = lazy(() => import('@/features/settings/SettingsScreen').then(m => ({ default: m.SettingsScreen })));
const SchoolworkScreen = lazy(() => import('@/features/schoolwork/SchoolworkScreen').then(m => ({ default: m.SchoolworkScreen })));
const AdminLogsScreen = lazy(() => import('@/features/admin/AdminLogsScreen').then(m => ({ default: m.AdminLogsScreen })));
const BatchPreviewScreen = lazy(() => import('@/features/batch/BatchPreviewScreen').then(m => ({ default: m.BatchPreviewScreen })));
const OnboardingScreen = lazy(() => import('@/features/onboarding/OnboardingScreen').then(m => ({ default: m.OnboardingScreen })));
const DailyBriefingScreen = lazy(() => import('@/features/briefing/DailyBriefingScreen').then(m => ({ default: m.DailyBriefingScreen })));
const WeeklyPlannerScreen = lazy(() => import('@/features/planner/WeeklyPlannerScreen').then(m => ({ default: m.WeeklyPlannerScreen })));
const AnalyticsScreen = lazy(() => import('@/features/analytics/AnalyticsScreen').then(m => ({ default: m.AnalyticsScreen })));
const ConversationExtractScreen = lazy(() => import('@/features/extract/ConversationExtractScreen').then(m => ({ default: m.ConversationExtractScreen })));
const MedicationScreen = lazy(() => import('@/features/medication/MedicationScreen').then(m => ({ default: m.MedicationScreen })));
const MedicationRefillScreen = lazy(() => import('@/features/medication/MedicationRefillScreen').then(m => ({ default: m.MedicationRefillScreen })));
const MedicationHistoryScreen = lazy(() => import('@/features/medication/MedicationHistoryScreen').then(m => ({ default: m.MedicationHistoryScreen })));
const MoodScreen = lazy(() => import('@/features/mood/MoodScreen').then(m => ({ default: m.MoodScreen })));
const FocusReleaseScreen = lazy(() => import('@/features/focus/FocusReleaseScreen').then(m => ({ default: m.FocusReleaseScreen })));
const ChatScreen = lazy(() => import('@/features/chat/ChatScreen').then(m => ({ default: m.ChatScreen })));
const LeisurePoolScreen = lazy(() => import('@/features/leisure/LeisurePoolScreen').then(m => ({ default: m.LeisurePoolScreen })));
const SelfCareScreen = lazy(() => import('@/features/selfcare/SelfCareScreen').then(m => ({ default: m.SelfCareScreen })));
const CheckInHistoryScreen = lazy(() => import('@/features/checkin/CheckInHistoryScreen').then(m => ({ default: m.CheckInHistoryScreen })));

function LazyRoute({ Component, fallback }: { Component: ComponentType; fallback?: React.ReactNode }) {
  return (
    <Suspense fallback={fallback || <LoadingFallback />}>
      <Component />
    </Suspense>
  );
}

function LoadingFallback() {
  return (
    <div className="animate-pulse p-4">
      <div className="h-7 w-48 rounded bg-[var(--color-bg-secondary)] mb-4" />
      <TaskListSkeleton count={5} />
    </div>
  );
}

const routes: RouteObject[] = [
  // Public routes
  { path: '/login', element: <LoginScreen /> },
  { path: '/register', element: <RegisterScreen /> },

  // Onboarding is protected (auth required) but sits outside the AppShell
  // so it renders full-screen without the sidebar/header, and must not
  // be gated on itself. RestorePendingGate runs first so a deletion-
  // pending user lands on the restore prompt instead of onboarding.
  {
    path: '/onboarding',
    element: (
      <ProtectedRoute>
        <RestorePendingGate>
          <LazyRoute Component={OnboardingScreen} />
        </RestorePendingGate>
      </ProtectedRoute>
    ),
  },

  // Protected routes inside AppShell — RestorePendingGate runs before
  // OnboardingGate so a deletion-pending user can never reach any
  // sync surface (parity with Android `AuthScreen.kt:72-80`).
  {
    element: (
      <ProtectedRoute>
        <RestorePendingGate>
          <OnboardingGate>
            <AppShell />
          </OnboardingGate>
        </RestorePendingGate>
      </ProtectedRoute>
    ),
    children: [
      { index: true, element: <TodayScreen /> },
      { path: 'tasks', element: <TaskListScreen /> },
      { path: 'tasks/:id', element: <LazyRoute Component={TaskDetailScreen} fallback={<TaskListSkeleton />} /> },
      { path: 'projects', element: <ProjectListScreen /> },
      { path: 'projects/:id', element: <LazyRoute Component={ProjectDetailScreen} fallback={<ProjectListSkeleton />} /> },
      { path: 'projects/:id/roadmap', element: <LazyRoute Component={ProjectRoadmapScreen} fallback={<ProjectListSkeleton />} /> },
      { path: 'habits', element: <LazyRoute Component={HabitListScreen} fallback={<HabitListSkeleton />} /> },
      { path: 'habits/:id/analytics', element: <LazyRoute Component={HabitAnalyticsScreen} /> },
      { path: 'calendar', element: <LazyRoute Component={CalendarRedirect} /> },
      { path: 'calendar/week', element: <LazyRoute Component={WeekViewScreen} /> },
      { path: 'calendar/month', element: <LazyRoute Component={MonthViewScreen} /> },
      { path: 'calendar/timeline', element: <LazyRoute Component={TimelineScreen} /> },
      { path: 'eisenhower', element: <LazyRoute Component={EisenhowerScreen} /> },
      { path: 'pomodoro', element: <LazyRoute Component={PomodoroScreen} /> },
      { path: 'timeblock', element: <LazyRoute Component={TimeBlockScreen} /> },
      { path: 'weekly-review', element: <LazyRoute Component={WeeklyReviewScreen} /> },
      { path: 'templates', element: <LazyRoute Component={TemplateListScreen} /> },
      { path: 'schoolwork', element: <LazyRoute Component={SchoolworkScreen} /> },
      { path: 'archive', element: <LazyRoute Component={ArchiveScreen} /> },
      { path: 'settings', element: <LazyRoute Component={SettingsScreen} fallback={<SettingsSkeleton />} /> },
      { path: 'admin/logs', element: <LazyRoute Component={AdminLogsScreen} /> },
      { path: 'batch/preview', element: <LazyRoute Component={BatchPreviewScreen} /> },
      { path: 'briefing', element: <LazyRoute Component={DailyBriefingScreen} /> },
      { path: 'planner', element: <LazyRoute Component={WeeklyPlannerScreen} /> },
      { path: 'analytics', element: <LazyRoute Component={AnalyticsScreen} /> },
      { path: 'extract', element: <LazyRoute Component={ConversationExtractScreen} /> },
      { path: 'medication', element: <LazyRoute Component={MedicationScreen} /> },
      { path: 'medication/refills', element: <LazyRoute Component={MedicationRefillScreen} /> },
      { path: 'medication/history', element: <LazyRoute Component={MedicationHistoryScreen} /> },
      { path: 'mood', element: <LazyRoute Component={MoodScreen} /> },
      { path: 'focus', element: <LazyRoute Component={FocusReleaseScreen} /> },
      { path: 'chat', element: <LazyRoute Component={ChatScreen} /> },
      { path: 'leisure', element: <LazyRoute Component={LeisurePoolScreen} /> },
      { path: 'self-care', element: <LazyRoute Component={SelfCareScreen} /> },
      { path: 'checkin/history', element: <LazyRoute Component={CheckInHistoryScreen} /> },
    ],
  },

  // Catch-all redirect
  { path: '*', element: <Navigate to="/" replace /> },
];

export const router = createBrowserRouter(routes);

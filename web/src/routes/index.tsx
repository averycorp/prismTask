/* eslint-disable react-refresh/only-export-components */
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
const LoginScreen = lazy(() => import('@/features/auth/LoginScreen').then(m => ({ default: m.LoginScreen })));
const RegisterScreen = lazy(() => import('@/features/auth/RegisterScreen').then(m => ({ default: m.RegisterScreen })));

// Core screens (eagerly loaded — most common)
const TodayScreen = lazy(() => import('@/features/today/TodayScreen').then(m => ({ default: m.TodayScreen })));
const TaskListScreen = lazy(() => import('@/features/tasks/TaskListScreen').then(m => ({ default: m.TaskListScreen })));
const ProjectListScreen = lazy(() => import('@/features/projects/ProjectListScreen').then(m => ({ default: m.ProjectListScreen })));

// Lazy-loaded screens (loaded on demand)
/* eslint-disable react-refresh/only-export-components */
const TaskDetailScreen = lazy(() => import('@/features/tasks/TaskDetailScreen').then(m => ({ default: m.TaskDetailScreen })));
const ProjectDetailScreen = lazy(() => import('@/features/projects/ProjectDetailScreen').then(m => ({ default: m.ProjectDetailScreen })));
const ProjectRoadmapScreen = lazy(() => import('@/features/projects/ProjectRoadmapScreen').then(m => ({ default: m.ProjectRoadmapScreen })));
const HabitListScreen = lazy(() => import('@/features/habits/HabitListScreen').then(m => ({ default: m.HabitListScreen })));
const HabitAnalyticsScreen = lazy(() => import('@/features/habits/HabitAnalyticsScreen').then(m => ({ default: m.HabitAnalyticsScreen })));
const HabitLogsScreen = lazy(() => import('@/features/habits/HabitLogsScreen').then(m => ({ default: m.HabitLogsScreen })));
const WeekViewScreen = lazy(() => import('@/features/calendar/WeekViewScreen').then(m => ({ default: m.WeekViewScreen })));
const MonthViewScreen = lazy(() => import('@/features/calendar/MonthViewScreen').then(m => ({ default: m.MonthViewScreen })));
const TimelineScreen = lazy(() => import('@/features/calendar/TimelineScreen').then(m => ({ default: m.TimelineScreen })));
const CalendarRedirect = lazy(() => import('@/features/calendar/CalendarRedirect').then(m => ({ default: m.CalendarRedirect })));
const EisenhowerScreen = lazy(() => import('@/features/eisenhower/EisenhowerScreen').then(m => ({ default: m.EisenhowerScreen })));
const PomodoroScreen = lazy(() => import('@/features/pomodoro/PomodoroScreen').then(m => ({ default: m.PomodoroScreen })));
const TimeBlockScreen = lazy(() => import('@/features/timeblock/TimeBlockScreen').then(m => ({ default: m.TimeBlockScreen })));
const WeeklyReviewScreen = lazy(() => import('@/features/weeklyreview/WeeklyReviewScreen').then(m => ({ default: m.WeeklyReviewScreen })));
const WeeklyReviewsListScreen = lazy(() => import('@/features/weeklyreview/WeeklyReviewsListScreen').then(m => ({ default: m.WeeklyReviewsListScreen })));
const WeeklyReviewDetailScreen = lazy(() => import('@/features/weeklyreview/WeeklyReviewDetailScreen').then(m => ({ default: m.WeeklyReviewDetailScreen })));
const TemplateListScreen = lazy(() => import('@/features/templates/TemplateListScreen').then(m => ({ default: m.TemplateListScreen })));
const ArchiveScreen = lazy(() => import('@/features/archive/ArchiveScreen').then(m => ({ default: m.ArchiveScreen })));
const SettingsScreen = lazy(() => import('@/features/settings/SettingsScreen').then(m => ({ default: m.SettingsScreen })));
const BrainModeScreen = lazy(() => import('@/features/settings/BrainModeScreen').then(m => ({ default: m.BrainModeScreen })));
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
const MedicationClinicalReportScreen = lazy(() => import('@/features/medication/MedicationClinicalReportScreen').then(m => ({ default: m.MedicationClinicalReportScreen })));
const MoodScreen = lazy(() => import('@/features/mood/MoodScreen').then(m => ({ default: m.MoodScreen })));
const FocusReleaseScreen = lazy(() => import('@/features/focus/FocusReleaseScreen').then(m => ({ default: m.FocusReleaseScreen })));
const ChatScreen = lazy(() => import('@/features/chat/ChatScreen').then(m => ({ default: m.ChatScreen })));
const LeisurePoolScreen = lazy(() => import('@/features/leisure/LeisurePoolScreen').then(m => ({ default: m.LeisurePoolScreen })));
const SelfCareScreen = lazy(() => import('@/features/selfcare/SelfCareScreen').then(m => ({ default: m.SelfCareScreen })));
const CheckInHistoryScreen = lazy(() => import('@/features/checkin/CheckInHistoryScreen').then(m => ({ default: m.CheckInHistoryScreen })));
const WeeklyBalanceReportScreen = lazy(() => import('@/features/balance/WeeklyBalanceReportScreen').then(m => ({ default: m.WeeklyBalanceReportScreen })));
const BoundaryRulesListScreen = lazy(() => import('@/features/boundaries/BoundaryRulesListScreen').then(m => ({ default: m.BoundaryRulesListScreen })));
const BoundaryRuleEditScreen = lazy(() => import('@/features/boundaries/BoundaryRuleEditScreen').then(m => ({ default: m.BoundaryRuleEditScreen })));
const NotificationsHub = lazy(() => import('@/features/settings/sections/Notifications/NotificationsHub').then(m => ({ default: m.NotificationsHub })));
const NotificationsProfilesScreen = lazy(() => import('@/features/settings/sections/Notifications/ProfilesScreen').then(m => ({ default: m.ProfilesScreen })));
const NotificationsSoundScreen = lazy(() => import('@/features/settings/sections/Notifications/SoundScreen').then(m => ({ default: m.SoundScreen })));
const NotificationsVibrationScreen = lazy(() => import('@/features/settings/sections/Notifications/VibrationScreen').then(m => ({ default: m.VibrationScreen })));
const NotificationsQuietHoursScreen = lazy(() => import('@/features/settings/sections/Notifications/QuietHoursScreen').then(m => ({ default: m.QuietHoursScreen })));
const NotificationsEscalationScreen = lazy(() => import('@/features/settings/sections/Notifications/EscalationScreen').then(m => ({ default: m.EscalationScreen })));
const TagManagementScreen = lazy(() => import('@/features/tags/TagManagementScreen').then(m => ({ default: m.TagManagementScreen })));
const SearchScreen = lazy(() => import('@/features/search/SearchScreen').then(m => ({ default: m.SearchScreen })));

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
  { path: '/login', element: <LazyRoute Component={LoginScreen} /> },
  { path: '/register', element: <LazyRoute Component={RegisterScreen} /> },

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
      { index: true, element: <LazyRoute Component={TodayScreen} /> },
      { path: 'tasks', element: <LazyRoute Component={TaskListScreen} fallback={<TaskListSkeleton />} /> },
      { path: 'tasks/:id', element: <LazyRoute Component={TaskDetailScreen} fallback={<TaskListSkeleton />} /> },
      { path: 'projects', element: <LazyRoute Component={ProjectListScreen} fallback={<ProjectListSkeleton />} /> },
      { path: 'projects/:id', element: <LazyRoute Component={ProjectDetailScreen} fallback={<ProjectListSkeleton />} /> },
      { path: 'projects/:id/roadmap', element: <LazyRoute Component={ProjectRoadmapScreen} fallback={<ProjectListSkeleton />} /> },
      { path: 'habits', element: <LazyRoute Component={HabitListScreen} fallback={<HabitListSkeleton />} /> },
      { path: 'habits/:id/analytics', element: <LazyRoute Component={HabitAnalyticsScreen} /> },
      { path: 'habits/:id/logs', element: <LazyRoute Component={HabitLogsScreen} /> },
      { path: 'calendar', element: <LazyRoute Component={CalendarRedirect} /> },
      { path: 'calendar/week', element: <LazyRoute Component={WeekViewScreen} /> },
      { path: 'calendar/month', element: <LazyRoute Component={MonthViewScreen} /> },
      { path: 'calendar/timeline', element: <LazyRoute Component={TimelineScreen} /> },
      { path: 'eisenhower', element: <LazyRoute Component={EisenhowerScreen} /> },
      { path: 'pomodoro', element: <LazyRoute Component={PomodoroScreen} /> },
      { path: 'timeblock', element: <LazyRoute Component={TimeBlockScreen} /> },
      { path: 'weekly-review', element: <LazyRoute Component={WeeklyReviewScreen} /> },
      { path: 'weekly-review/history', element: <LazyRoute Component={WeeklyReviewsListScreen} /> },
      { path: 'weekly-review/detail/:weekStartDate', element: <LazyRoute Component={WeeklyReviewDetailScreen} /> },
      { path: 'templates', element: <LazyRoute Component={TemplateListScreen} /> },
      { path: 'schoolwork', element: <LazyRoute Component={SchoolworkScreen} /> },
      { path: 'archive', element: <LazyRoute Component={ArchiveScreen} /> },
      { path: 'settings', element: <LazyRoute Component={SettingsScreen} fallback={<SettingsSkeleton />} /> },
      { path: 'settings/brain-mode', element: <LazyRoute Component={BrainModeScreen} fallback={<SettingsSkeleton />} /> },
      { path: 'admin/logs', element: <LazyRoute Component={AdminLogsScreen} /> },
      { path: 'batch/preview', element: <LazyRoute Component={BatchPreviewScreen} /> },
      { path: 'briefing', element: <LazyRoute Component={DailyBriefingScreen} /> },
      { path: 'planner', element: <LazyRoute Component={WeeklyPlannerScreen} /> },
      { path: 'analytics', element: <LazyRoute Component={AnalyticsScreen} /> },
      { path: 'extract', element: <LazyRoute Component={ConversationExtractScreen} /> },
      { path: 'medication', element: <LazyRoute Component={MedicationScreen} /> },
      { path: 'medication/refills', element: <LazyRoute Component={MedicationRefillScreen} /> },
      { path: 'medication/history', element: <LazyRoute Component={MedicationHistoryScreen} /> },
      { path: 'medication/clinical-report', element: <LazyRoute Component={MedicationClinicalReportScreen} /> },
      { path: 'mood', element: <LazyRoute Component={MoodScreen} /> },
      { path: 'focus', element: <LazyRoute Component={FocusReleaseScreen} /> },
      { path: 'chat', element: <LazyRoute Component={ChatScreen} /> },
      { path: 'leisure', element: <LazyRoute Component={LeisurePoolScreen} /> },
      { path: 'self-care', element: <LazyRoute Component={SelfCareScreen} /> },
      { path: 'checkin/history', element: <LazyRoute Component={CheckInHistoryScreen} /> },
      { path: 'balance/weekly-report', element: <LazyRoute Component={WeeklyBalanceReportScreen} /> },
      { path: 'boundaries', element: <LazyRoute Component={BoundaryRulesListScreen} /> },
      { path: 'boundaries/new', element: <LazyRoute Component={BoundaryRuleEditScreen} /> },
      { path: 'boundaries/:id', element: <LazyRoute Component={BoundaryRuleEditScreen} /> },
      { path: 'settings/notifications', element: <LazyRoute Component={NotificationsHub} fallback={<SettingsSkeleton />} /> },
      { path: 'settings/notifications/profiles', element: <LazyRoute Component={NotificationsProfilesScreen} fallback={<SettingsSkeleton />} /> },
      { path: 'settings/notifications/sound', element: <LazyRoute Component={NotificationsSoundScreen} fallback={<SettingsSkeleton />} /> },
      { path: 'settings/notifications/vibration', element: <LazyRoute Component={NotificationsVibrationScreen} fallback={<SettingsSkeleton />} /> },
      { path: 'settings/notifications/quiet-hours', element: <LazyRoute Component={NotificationsQuietHoursScreen} fallback={<SettingsSkeleton />} /> },
      { path: 'settings/notifications/escalation', element: <LazyRoute Component={NotificationsEscalationScreen} fallback={<SettingsSkeleton />} /> },
      { path: 'tags', element: <LazyRoute Component={TagManagementScreen} /> },
      { path: 'search', element: <LazyRoute Component={SearchScreen} /> },
    ],
  },

  // Catch-all redirect
  { path: '*', element: <Navigate to="/" replace /> },
];

export const router = createBrowserRouter(routes);

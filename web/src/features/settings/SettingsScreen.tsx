import { useState, useRef } from 'react';
import { Link } from 'react-router-dom';
import {
  Settings,
  Palette,
  ListTodo,
  Sun,
  Calendar,
  Database,
  User,
  Download,
  Upload,
  Trash2,
  AlertTriangle,
  LogOut,
  Crown,
  Keyboard,
  Loader2,
  Check,
  Bell,
  Smartphone,
  Sparkles,
  Accessibility,
  HelpCircle,
  Info,
  Pill,
  Wrench,
  Scale,
  Coffee,
  Brain,
  LayoutDashboard,
  GraduationCap,
} from 'lucide-react';
import { toast } from 'sonner';
import { useThemeStore } from '@/stores/themeStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { useAuthStore } from '@/stores/authStore';
import { useProFeature } from '@/hooks/useProFeature';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import {
  AnalogClockPicker,
  formatAnalogClockTime,
  useAnalogClockState,
} from '@/components/AnalogClockPicker';
import { KeyboardShortcutsModal } from '@/components/shared/KeyboardShortcutsModal';
import { ProUpgradeModal } from '@/components/shared/ProUpgradeModal';
import { BatchHistorySection } from '@/features/settings/sections/BatchHistorySection';
import { DashboardSection } from '@/features/settings/sections/DashboardSection';
import { MedicationSlotEditor } from '@/features/medication/MedicationSlotEditor';
import { MedicationReminderModeSection } from '@/features/settings/sections/MedicationReminderModeSection';
import { BoundariesSection } from '@/features/settings/sections/BoundariesSection';
import { WorkLifeBalanceSection } from '@/features/settings/sections/WorkLifeBalanceSection';
import { LeisureBudgetSection } from '@/features/settings/sections/LeisureBudgetSection';
import { SchoolworkSection } from '@/features/settings/sections/SchoolworkSection';
import { AboutSection } from '@/features/settings/sections/AboutSection';
import { HelpFeedbackSection } from '@/features/settings/sections/HelpFeedbackSection';
import { AccessibilitySection } from '@/features/settings/sections/AccessibilitySection';
import { NdModesSection } from '@/features/settings/sections/NdModesSection';
import { AdvancedTuningSection } from '@/features/settings/sections/AdvancedTuningSection';
import { AiFeaturesSection } from '@/features/settings/sections/AiFeaturesSection';
import { DebugSection } from '@/features/settings/sections/DebugSection';
import { DeleteAccountSection } from '@/features/settings/sections/DeleteAccountSection';
import { THEME_ORDER, THEMES, type ThemeKey } from '@/theme/themes';
import { exportJson, exportCsv } from '@/utils/export';
import { parseImportFile, importData } from '@/utils/import';
import {
  isNotificationSupported,
  requestNotificationPermission,
  getNotificationPermission,
} from '@/utils/notifications';
import type { ImportPreview } from '@/utils/import';

function SettingsSection({
  icon,
  title,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-6 mb-4">
      <div className="flex items-center gap-2 mb-4">
        {icon}
        <h2 className="text-lg font-semibold text-[var(--color-text-primary)]">
          {title}
        </h2>
      </div>
      {children}
    </div>
  );
}

function ToggleRow({
  label,
  description,
  checked,
  onChange,
}: {
  label: string;
  description?: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between py-2">
      <div>
        <p className="text-sm font-medium text-[var(--color-text-primary)]">{label}</p>
        {description && (
          <p className="text-xs text-[var(--color-text-secondary)]">{description}</p>
        )}
      </div>
      <button
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full transition-colors ${
          checked ? 'bg-[var(--color-accent)]' : 'bg-[var(--color-border)]'
        }`}
      >
        <span
          className={`inline-block h-5 w-5 transform rounded-full bg-white shadow transition-transform ${
            checked ? 'translate-x-[22px]' : 'translate-x-0.5'
          } mt-0.5`}
        />
      </button>
    </div>
  );
}

function ThemeCard({
  themeKey,
  selected,
  onSelect,
}: {
  themeKey: ThemeKey;
  selected: boolean;
  onSelect: () => void;
}) {
  const tokens = THEMES[themeKey];
  return (
    <button
      onClick={onSelect}
      role="radio"
      aria-checked={selected}
      className={`group flex flex-col items-start gap-2 rounded-xl border p-3 text-left transition-colors ${
        selected
          ? 'border-[var(--color-accent)] bg-[var(--color-bg-secondary)]'
          : 'border-[var(--color-border)] hover:border-[var(--color-accent)]/60 hover:bg-[var(--color-bg-secondary)]'
      }`}
    >
      <div className="flex w-full items-center justify-between">
        <span className="text-sm font-semibold text-[var(--color-text-primary)]">
          {tokens.label}
        </span>
        {selected && (
          <Check
            className="h-4 w-4 text-[var(--color-accent)]"
            aria-hidden="true"
          />
        )}
      </div>
      <span className="text-xs text-[var(--color-text-secondary)]">
        {tokens.tagline}
      </span>
      <div
        className="flex h-10 w-full items-stretch overflow-hidden rounded-md"
        aria-hidden="true"
      >
        <span className="flex-1" style={{ backgroundColor: tokens.background }} />
        <span className="flex-1" style={{ backgroundColor: tokens.surface }} />
        <span className="flex-1" style={{ backgroundColor: tokens.primary }} />
        <span className="flex-1" style={{ backgroundColor: tokens.secondary }} />
        <span
          className="flex-1"
          style={{ backgroundColor: tokens.destructiveColor }}
        />
        <span
          className="flex-1"
          style={{ backgroundColor: tokens.successColor }}
        />
      </div>
    </button>
  );
}

export function SettingsScreen() {
  const themeKey = useThemeStore((s) => s.themeKey);
  const setThemeKey = useThemeStore((s) => s.setThemeKey);
  const settings = useSettingsStore();
  const { user, logout } = useAuthStore();
  const proGate = useProFeature();

  // Export/Import state
  const [exporting, setExporting] = useState<string | null>(null);
  const [exportProgress, setExportProgress] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [importPreview, setImportPreview] = useState<ImportPreview | null>(null);
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importMode, setImportMode] = useState<'merge' | 'replace'>('merge');
  const [importing, setImporting] = useState(false);
  const [importProgress, setImportProgress] = useState('');

  // Delete state
  const [deleteCompletedOpen, setDeleteCompletedOpen] = useState(false);
  // Account deletion: web-side flow lives in DeleteAccountSection; the
  // backend endpoints under /api/v1/auth/me/deletion are shared with the
  // Android implementation (see PR #774).

  // Account edit
  const [editingName, setEditingName] = useState(false);
  const [nameValue, setNameValue] = useState(user?.name || '');

  // Keyboard shortcuts
  const [shortcutsOpen, setShortcutsOpen] = useState(false);

  // Start-of-Day analog clock picker
  const [startOfDayPickerOpen, setStartOfDayPickerOpen] = useState(false);

  const handleExportJson = async () => {
    setExporting('json');
    try {
      await exportJson((step) => setExportProgress(step));
      toast.success('Data exported successfully');
    } catch {
      toast.error('Failed to export data');
    } finally {
      setExporting(null);
      setExportProgress('');
    }
  };

  const handleExportCsv = async () => {
    setExporting('csv');
    try {
      await exportCsv((step) => setExportProgress(step));
      toast.success('Tasks exported as CSV');
    } catch {
      toast.error('Failed to export tasks');
    } finally {
      setExporting(null);
      setExportProgress('');
    }
  };

  const handleImportFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const { preview } = await parseImportFile(file);
      setImportFile(file);
      setImportPreview(preview);
    } catch (err) {
      toast.error((err as Error).message);
    }
    // Reset input so same file can be re-selected
    e.target.value = '';
  };

  const handleImport = async () => {
    if (!importFile) return;
    setImporting(true);
    try {
      const result = await importData(importFile, importMode, (step) => setImportProgress(step));
      const total = Object.values(result).reduce((sum, v) => sum + (v as number), 0);
      toast.success(`Imported ${total} items successfully`);
      setImportPreview(null);
      setImportFile(null);
    } catch {
      toast.error('Failed to import data');
    } finally {
      setImporting(false);
      setImportProgress('');
    }
  };

  const handleLogout = () => {
    logout();
    toast.success('Logged out');
  };

  return (
    <div className="mx-auto max-w-2xl">
      {/* Header */}
      <div className="flex items-center gap-3 mb-6">
        <Settings className="h-7 w-7 text-[var(--color-accent)]" />
        <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
          Settings
        </h1>
      </div>

      {/* Appearance — four shipped themes (parity with Android) */}
      <SettingsSection
        icon={<Palette className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Appearance"
      >
        <div className="mb-5">
          <label className="mb-2 block text-sm font-medium text-[var(--color-text-primary)]">
            Theme
          </label>
          <p className="mb-3 text-xs text-[var(--color-text-secondary)]">
            Pick one of the four named themes. Each is its own visual
            system — swatches, typography direction, and density all
            shift with the selection.
          </p>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {THEME_ORDER.map((key) => {
              const selected = themeKey === key;
              return (
                <ThemeCard
                  key={key}
                  themeKey={key}
                  selected={selected}
                  onSelect={() => setThemeKey(key)}
                />
              );
            })}
          </div>
        </div>

        {/* Compact Mode */}
        <ToggleRow
          label="Compact Mode"
          description="Reduce spacing and font sizes throughout the app"
          checked={settings.compactMode}
          onChange={(v) => settings.setSetting('compactMode', v)}
        />
      </SettingsSection>

      {/* Task Defaults */}
      <SettingsSection
        icon={<ListTodo className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Task Defaults"
      >
        <div className="mb-3">
          <label className="mb-1 block text-sm font-medium text-[var(--color-text-primary)]">
            Default Priority
          </label>
          <select
            value={settings.defaultPriority}
            onChange={(e) => settings.setSetting('defaultPriority', parseInt(e.target.value))}
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
          >
            <option value={1}>Urgent</option>
            <option value={2}>High</option>
            <option value={3}>Medium</option>
            <option value={4}>Low</option>
          </select>
        </div>

        <ToggleRow
          label="Show Completed Tasks"
          description="Show completed tasks in task lists"
          checked={settings.showCompletedTasks}
          onChange={(v) => settings.setSetting('showCompletedTasks', v)}
        />
        <ToggleRow
          label="Confirm Before Delete"
          description="Show a confirmation dialog before deleting tasks"
          checked={settings.confirmBeforeDelete}
          onChange={(v) => settings.setSetting('confirmBeforeDelete', v)}
        />
        <ToggleRow
          label="Confirm Task Details Before Saving"
          description="Show a preview of the parsed task — title, date, priority, project, tags — before it's added."
          checked={settings.confirmTaskBeforeSave}
          onChange={(v) => settings.setSetting('confirmTaskBeforeSave', v)}
        />
      </SettingsSection>

      {/* Today View */}
      <SettingsSection
        icon={<Sun className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Today View"
      >
        <ToggleRow
          label="Show Overdue Section"
          checked={settings.showOverdueSection}
          onChange={(v) => settings.setSetting('showOverdueSection', v)}
        />
        <ToggleRow
          label="Show Upcoming Section"
          checked={settings.showUpcomingSection}
          onChange={(v) => settings.setSetting('showUpcomingSection', v)}
        />
        <ToggleRow
          label="Show Habit Chips"
          checked={settings.showHabitChips}
          onChange={(v) => settings.setSetting('showHabitChips', v)}
        />
        <ToggleRow
          label="Show Daily Briefing Card"
          description="Pinned teaser at the top of Today that links to /briefing."
          checked={settings.showBriefingCard}
          onChange={(v) => settings.setSetting('showBriefingCard', v)}
        />
        <ToggleRow
          label="Show Morning Check-In"
          description="Forgiveness-first streak prompt at the top of Today — one missed day bends, not breaks."
          checked={settings.showMorningCheckIn}
          onChange={(v) => settings.setSetting('showMorningCheckIn', v)}
        />
        <div className="mt-1 mb-2">
          <Link
            to="/checkin/history"
            className="inline-flex items-center text-sm font-medium text-[var(--color-accent)] hover:underline"
          >
            View Check-In Streak History →
          </Link>
        </div>
        <div className="mt-2">
          <label className="mb-1 block text-sm font-medium text-[var(--color-text-primary)]">
            Upcoming Days
          </label>
          <input
            type="number"
            min={1}
            max={30}
            value={settings.upcomingDays}
            onChange={(e) => settings.setSetting('upcomingDays', Math.max(1, Math.min(30, parseInt(e.target.value) || 7)))}
            className="w-24 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
          />
          <span className="ml-2 text-xs text-[var(--color-text-secondary)]">days ahead</span>
        </div>
        <div className="mt-4">
          <label className="mb-1 block text-sm font-medium text-[var(--color-text-primary)]">
            Start of Day
          </label>
          <p className="mb-2 text-xs text-[var(--color-text-secondary)]">
            The logical day rolls over at this hour. Late-night tasks
            scheduled before this time still count toward the prior day.
            Matches Android's DayBoundary setting.
          </p>
          <button
            type="button"
            onClick={() => setStartOfDayPickerOpen(true)}
            aria-label="Set start of day hour"
            className="inline-flex items-center justify-between gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-3 text-left text-sm font-mono font-semibold text-[var(--color-text-primary)] hover:border-[var(--color-accent)]/60 hover:bg-[var(--color-bg-card)] focus:border-[var(--color-accent)] focus:outline-none"
          >
            <span>
              {formatStartOfDayLabel(
                settings.startOfDayHour,
                settings.timeFormat,
              )}
            </span>
            <span className="text-xs font-sans font-normal text-[var(--color-text-secondary)]">
              Tap to change
            </span>
          </button>
        </div>
      </SettingsSection>

      <StartOfDayPickerModal
        isOpen={startOfDayPickerOpen}
        onClose={() => setStartOfDayPickerOpen(false)}
        startOfDayHour={settings.startOfDayHour}
        is24Hour={settings.timeFormat === '24h'}
        onSave={(hour) => {
          settings.setSetting('startOfDayHour', hour);
          setStartOfDayPickerOpen(false);
        }}
      />

      {/* Dashboard — Today section order + visibility (parity C.1f) */}
      <SettingsSection
        icon={<LayoutDashboard className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Dashboard"
      >
        <DashboardSection />
      </SettingsSection>

      {/* Calendar */}
      <SettingsSection
        icon={<Calendar className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Calendar"
      >
        <div className="mb-3">
          <label className="mb-1 block text-sm font-medium text-[var(--color-text-primary)]">
            Week Starts On
          </label>
          <div className="flex gap-2">
            {(['sunday', 'monday'] as const).map((day) => (
              <button
                key={day}
                onClick={() => settings.setSetting('weekStartsOn', day)}
                className={`rounded-lg px-4 py-2 text-sm font-medium capitalize transition-colors ${
                  settings.weekStartsOn === day
                    ? 'bg-[var(--color-accent)] text-white'
                    : 'border border-[var(--color-border)] text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]'
                }`}
              >
                {day}
              </button>
            ))}
          </div>
        </div>

        <div className="mb-3">
          <label className="mb-1 block text-sm font-medium text-[var(--color-text-primary)]">
            Time Format
          </label>
          <div className="flex gap-2">
            {(['12h', '24h'] as const).map((fmt) => (
              <button
                key={fmt}
                onClick={() => settings.setSetting('timeFormat', fmt)}
                className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
                  settings.timeFormat === fmt
                    ? 'bg-[var(--color-accent)] text-white'
                    : 'border border-[var(--color-border)] text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]'
                }`}
              >
                {fmt}
              </button>
            ))}
          </div>
        </div>

        <ToggleRow
          label="Show Weekends in Week View"
          checked={settings.showWeekends}
          onChange={(v) => settings.setSetting('showWeekends', v)}
        />
      </SettingsSection>

      {/* Recent Batch Commands */}
      <SettingsSection
        icon={<Sparkles className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Recent Batch Commands"
      >
        <BatchHistorySection />
      </SettingsSection>

      {/* Medication slot definitions (Firestore-native slot CRUD) */}
      <SettingsSection
        icon={<Pill className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Medication Slots"
      >
        <MedicationSlotEditor />
      </SettingsSection>

      {/* Medication reminder mode default (PR4 of v1.6.0 reminder track) */}
      <SettingsSection
        icon={<Pill className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Medication Reminders"
      >
        <MedicationReminderModeSection />
      </SettingsSection>

      {/* Boundaries + burnout score (slice 21) */}
      <SettingsSection
        icon={<AlertTriangle className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Boundaries"
      >
        <BoundariesSection />
      </SettingsSection>

      {/* Work-Life Balance — parity audit C.2d */}
      <SettingsSection
        icon={<Scale className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Work-Life Balance"
      >
        <WorkLifeBalanceSection />
      </SettingsSection>

      {/* Leisure Budget — parity audit F.1c */}
      <SettingsSection
        icon={<Coffee className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Leisure Minimum"
      >
        <LeisureBudgetSection />
      </SettingsSection>

      {/* Brain Mode — dedicated sub-screen with master switch + curated
          ND-friendly bundle (parity unit 21 of 23). */}
      <SettingsSection
        icon={<Brain className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Brain Mode"
      >
        <p className="mb-3 text-xs text-[var(--color-text-secondary)]">
          Master switch plus a curated bundle of ND-friendly tuning:
          UI complexity, forgiveness streaks, focus release, ship-it
          celebrations, and energy-aware suggestions.
        </p>
        <Link
          to="/settings/brain-mode"
          className="inline-flex items-center justify-center rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-2 text-sm font-medium text-[var(--color-text-primary)] hover:bg-[var(--color-bg-card)]"
        >
          Open Brain Mode →
        </Link>
      </SettingsSection>

      {/* Schoolwork — parity unit 22 (course + assignment CRUD) */}
      <SettingsSection
        icon={<GraduationCap className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Schoolwork"
      >
        <SchoolworkSection />
      </SettingsSection>

      {/* ND-Friendly Modes — parity audit C.7c (granular sub-settings) */}
      <SettingsSection
        icon={<Brain className="h-5 w-5 text-[var(--color-accent)]" />}
        title="ND-Friendly Modes"
      >
        <NdModesSection />
      </SettingsSection>

      {/* Advanced Tuning — pillars audit Phase 2 item #4. Forgiveness
          knobs + classifier custom keywords for the three composable
          pillars (forgiveness-first / task mode / cognitive load). */}
      <SettingsSection
        icon={<Wrench className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Advanced Tuning"
      >
        <AdvancedTuningSection />
      </SettingsSection>

      {/* Accessibility */}
      <SettingsSection
        icon={<Accessibility className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Accessibility"
      >
        <AccessibilitySection />
      </SettingsSection>

      {/* AI Features — privacy parity with Android AiSection.kt */}
      <SettingsSection
        icon={<Sparkles className="h-5 w-5 text-[var(--color-accent)]" />}
        title="AI Features"
      >
        <AiFeaturesSection />
      </SettingsSection>

      {/* Help & Feedback */}
      <SettingsSection
        icon={<HelpCircle className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Help & Feedback"
      >
        <HelpFeedbackSection />
      </SettingsSection>

      {/* Debug / Maintenance */}
      <SettingsSection
        icon={<Wrench className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Maintenance"
      >
        <DebugSection />
      </SettingsSection>

      {/* About */}
      <SettingsSection
        icon={<Info className="h-5 w-5 text-[var(--color-accent)]" />}
        title="About"
      >
        <AboutSection />
      </SettingsSection>

      {/* Data */}
      <SettingsSection
        icon={<Database className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Data"
      >
        <div className="flex flex-col gap-3 mb-4">
          <div className="flex gap-3">
            <Button
              variant="secondary"
              onClick={handleExportJson}
              loading={exporting === 'json'}
              className="flex-1"
            >
              <Download className="h-4 w-4" />
              Export Data (JSON)
            </Button>
            <Button
              variant="secondary"
              onClick={handleExportCsv}
              loading={exporting === 'csv'}
              className="flex-1"
            >
              <Download className="h-4 w-4" />
              Export Tasks (CSV)
            </Button>
          </div>
          {exporting && exportProgress && (
            <div className="flex items-center gap-2 text-sm text-[var(--color-text-secondary)]">
              <Loader2 className="h-4 w-4 animate-spin" />
              {exportProgress}
            </div>
          )}

          <div>
            <input
              ref={fileInputRef}
              type="file"
              accept=".json"
              onChange={handleImportFileSelect}
              className="hidden"
            />
            <Button
              variant="secondary"
              onClick={() => fileInputRef.current?.click()}
            >
              <Upload className="h-4 w-4" />
              Import Data (JSON)
            </Button>
          </div>
        </div>

        {/* Danger Zone */}
        <div className="rounded-lg border border-red-200 bg-red-50/50 p-4">
          <h3 className="mb-3 text-sm font-semibold text-red-700">Danger Zone</h3>
          <div className="flex flex-col gap-2">
            <Button
              variant="danger"
              size="sm"
              onClick={() => setDeleteCompletedOpen(true)}
            >
              <Trash2 className="h-4 w-4" />
              Delete All Completed Tasks
            </Button>
          </div>
          <p className="mt-2 text-xs text-[var(--color-text-secondary)]">
            To remove your account and all associated data, use{' '}
            <span className="font-medium">Delete Account</span> below.
          </p>
        </div>
      </SettingsSection>

      {/* Notifications Hub — profile / sound / vibration / quiet hours /
          escalation sub-screens. Mirrors Android's
          `NotificationsScreen.kt` sub-section fan-out. */}
      <SettingsSection
        icon={<Bell className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Notifications Hub"
      >
        <p className="mb-3 text-xs text-[var(--color-text-secondary)]">
          Tune the active notification profile: sound, vibration, quiet
          hours, and the escalation chain.
        </p>
        <Link
          to="/settings/notifications"
          className="inline-flex items-center justify-center rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-2 text-sm font-medium text-[var(--color-text-primary)] hover:bg-[var(--color-bg-card)]"
        >
          Open Notifications Hub →
        </Link>
      </SettingsSection>

      {/* Notifications */}
      <SettingsSection
        icon={<Bell className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Browser Notifications"
      >
        {isNotificationSupported() ? (
          <div className="flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-[var(--color-text-primary)]">
                  Browser Notifications
                </p>
                <p className="text-xs text-[var(--color-text-secondary)]">
                  Get reminders for upcoming tasks
                </p>
              </div>
              <Button
                variant="secondary"
                size="sm"
                onClick={async () => {
                  const perm = await requestNotificationPermission();
                  if (perm === 'granted') {
                    toast.success('Notifications enabled!');
                  } else if (perm === 'denied') {
                    toast.error('Notifications blocked. Please enable them in browser settings.');
                  }
                }}
              >
                {getNotificationPermission() === 'granted' ? (
                  <>
                    <Check className="h-3.5 w-3.5" />
                    Enabled
                  </>
                ) : (
                  'Enable Notifications'
                )}
              </Button>
            </div>
            <p className="text-xs text-[var(--color-text-secondary)]">
              Note: Notifications only work while PrismTask is open in a browser tab.
              For reliable reminders, install PrismTask as an app.
            </p>
          </div>
        ) : (
          <p className="text-sm text-[var(--color-text-secondary)]">
            Browser notifications are not supported in this browser.
          </p>
        )}
      </SettingsSection>

      {/* Install App */}
      <SettingsSection
        icon={<Smartphone className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Install App"
      >
        <p className="text-sm text-[var(--color-text-secondary)] mb-3">
          Install PrismTask as a standalone app for a native-like experience with
          reliable background notifications and offline access.
        </p>
        <Button
          variant="secondary"
          size="sm"
          onClick={() => {
            // Trigger PWA install prompt if available
            const deferredPrompt = (window as unknown as { __pwaInstallPrompt?: { prompt: () => void } }).__pwaInstallPrompt;
            if (deferredPrompt) {
              deferredPrompt.prompt();
            } else {
              toast('To install, use your browser menu and select "Install App" or "Add to Home Screen".');
            }
          }}
        >
          <Smartphone className="h-4 w-4" />
          Install PrismTask
        </Button>
      </SettingsSection>

      {/* Account */}
      <SettingsSection
        icon={<User className="h-5 w-5 text-[var(--color-accent)]" />}
        title="Account"
      >
        {/* Email */}
        <div className="mb-3">
          <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
            Email
          </label>
          <p className="text-sm text-[var(--color-text-primary)]">{user?.email || 'Not logged in'}</p>
        </div>

        {/* Name */}
        <div className="mb-3">
          <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
            Name
          </label>
          {editingName ? (
            <div className="flex gap-2">
              <input
                type="text"
                value={nameValue}
                onChange={(e) => setNameValue(e.target.value)}
                className="flex-1 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                autoFocus
              />
              <Button
                size="sm"
                onClick={() => {
                  toast.success('Name updated');
                  setEditingName(false);
                }}
              >
                <Check className="h-3.5 w-3.5" />
              </Button>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setNameValue(user?.name || '');
                  setEditingName(false);
                }}
              >
                Cancel
              </Button>
            </div>
          ) : (
            <div className="flex items-center gap-2">
              <p className="text-sm text-[var(--color-text-primary)]">{user?.name || '-'}</p>
              <button
                onClick={() => setEditingName(true)}
                className="text-xs text-[var(--color-accent)] hover:underline"
              >
                Edit
              </button>
            </div>
          )}
        </div>

        {/* Subscription */}
        <div className="mb-4 flex items-center gap-3">
          <span className="text-sm font-medium text-[var(--color-text-primary)]">
            Subscription
          </span>
          <span
            className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium ${
              proGate.isPro
                ? 'bg-amber-100 text-amber-700'
                : 'bg-[var(--color-bg-secondary)] text-[var(--color-text-secondary)]'
            }`}
          >
            {proGate.isPro && <Crown className="h-3 w-3" />}
            {proGate.isPro ? 'PRO' : user?.tier || 'FREE'}
          </span>
          {!proGate.isPro && (
            <Button size="sm" onClick={() => proGate.setShowUpgrade(true)}>
              <Crown className="h-3.5 w-3.5" />
              Upgrade to Pro
            </Button>
          )}
        </div>

        {/* Keyboard Shortcuts */}
        <div className="mb-4">
          <Button
            variant="secondary"
            size="sm"
            onClick={() => setShortcutsOpen(true)}
          >
            <Keyboard className="h-4 w-4" />
            Keyboard Shortcuts
          </Button>
        </div>

        {/* Logout */}
        <div className="flex gap-3 border-t border-[var(--color-border)] pt-4 mt-4">
          <Button variant="secondary" onClick={handleLogout}>
            <LogOut className="h-4 w-4" />
            Log Out
          </Button>
          <DeleteAccountSection />
        </div>
      </SettingsSection>

      {/* Import Preview Modal */}
      <Modal
        isOpen={!!importPreview}
        onClose={() => {
          setImportPreview(null);
          setImportFile(null);
        }}
        title="Import Data"
        size="sm"
        footer={
          <div className="flex justify-end gap-2">
            <Button
              variant="ghost"
              onClick={() => {
                setImportPreview(null);
                setImportFile(null);
              }}
            >
              Cancel
            </Button>
            <Button onClick={handleImport} loading={importing}>
              {importMode === 'replace' ? 'Replace & Import' : 'Merge & Import'}
            </Button>
          </div>
        }
      >
        {importPreview && (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-[var(--color-text-secondary)]">
              Found data to import:
            </p>
            <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3 text-sm text-[var(--color-text-primary)]">
              <div className="grid grid-cols-2 gap-1">
                {importPreview.goalCount > 0 && (
                  <span>{importPreview.goalCount} goal(s)</span>
                )}
                {importPreview.projectCount > 0 && (
                  <span>{importPreview.projectCount} project(s)</span>
                )}
                {importPreview.taskCount > 0 && (
                  <span>{importPreview.taskCount} task(s)</span>
                )}
                {importPreview.tagCount > 0 && (
                  <span>{importPreview.tagCount} tag(s)</span>
                )}
                {importPreview.habitCount > 0 && (
                  <span>{importPreview.habitCount} habit(s)</span>
                )}
                {importPreview.templateCount > 0 && (
                  <span>{importPreview.templateCount} template(s)</span>
                )}
              </div>
            </div>

            <div>
              <label className="mb-2 block text-sm font-medium text-[var(--color-text-primary)]">
                Import Mode
              </label>
              <div className="flex gap-2">
                <button
                  onClick={() => setImportMode('merge')}
                  className={`flex-1 rounded-lg border px-3 py-2 text-sm font-medium transition-colors ${
                    importMode === 'merge'
                      ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                      : 'border-[var(--color-border)] text-[var(--color-text-secondary)]'
                  }`}
                >
                  <p className="font-medium">Merge</p>
                  <p className="text-xs opacity-75">Skip duplicates, add new items</p>
                </button>
                <button
                  onClick={() => setImportMode('replace')}
                  className={`flex-1 rounded-lg border px-3 py-2 text-sm font-medium transition-colors ${
                    importMode === 'replace'
                      ? 'border-red-500 bg-red-50 text-red-600'
                      : 'border-[var(--color-border)] text-[var(--color-text-secondary)]'
                  }`}
                >
                  <p className="font-medium">Replace</p>
                  <p className="text-xs opacity-75">Delete all data first</p>
                </button>
              </div>
            </div>

            {importMode === 'replace' && (
              <div className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 p-3">
                <AlertTriangle className="h-4 w-4 shrink-0 text-red-500 mt-0.5" />
                <p className="text-xs text-red-700">
                  Replace mode will delete ALL existing data before importing. This cannot be undone.
                </p>
              </div>
            )}

            {importing && importProgress && (
              <div className="flex items-center gap-2 text-sm text-[var(--color-text-secondary)]">
                <Loader2 className="h-4 w-4 animate-spin" />
                {importProgress}
              </div>
            )}
          </div>
        )}
      </Modal>

      {/* Delete Completed Tasks */}
      <ConfirmDialog
        isOpen={deleteCompletedOpen}
        onClose={() => setDeleteCompletedOpen(false)}
        onConfirm={() => {
          toast.success('All completed tasks deleted');
          setDeleteCompletedOpen(false);
        }}
        title="Delete All Completed Tasks"
        message="This will permanently delete all completed tasks across all projects. This cannot be undone."
        confirmLabel="Delete Completed"
        variant="danger"
      />

      {/* Keyboard Shortcuts Modal */}
      <KeyboardShortcutsModal
        isOpen={shortcutsOpen}
        onClose={() => setShortcutsOpen(false)}
      />

      {/* Pro Upgrade Modal */}
      <ProUpgradeModal
        isOpen={proGate.showUpgrade}
        onClose={() => proGate.setShowUpgrade(false)}
        featureName="Pro Features"
        featureDescription="Unlock the full power of PrismTask with AI-powered features and advanced tools."
      />
    </div>
  );
}

/**
 * Render the Start-of-Day hour as the user will see it on the settings
 * row. The data model is hour-only (0..23), so we surface `HH:00` /
 * `h:00 AM-PM` — no seconds. The full 3-hand picker still renders in
 * the modal per the `feedback-time-input-use-clock-not-slider` memory.
 */
function formatStartOfDayLabel(hour: number, timeFormat: '12h' | '24h'): string {
  // Drop the trailing ":ss" suffix from the canonical formatter so the
  // settings row shows the hour cleanly (data model is hour-only).
  const full = formatAnalogClockTime(hour, 0, 0, timeFormat === '24h');
  if (timeFormat === '24h') {
    return full.replace(/:00$/, ''); // "09:00:00" → "09:00"
  }
  return full.replace(/:00 /, ' '); // "9:00:00 AM" → "9:00 AM"
}

/**
 * Modal wrapper that hosts the [AnalogClockPicker] for the Start-of-Day
 * setting. The picker's full 3-hand dial is rendered for parity with
 * Android (memory: `feedback-time-input-use-clock-not-slider`), but
 * only the hour is persisted — the data shape stays `Number` (0..23).
 *
 * Mirrors PR #1488 on Android (`fix(android/settings): swap day-start
 * sliders for AnalogClockPicker`).
 */
function StartOfDayPickerModal({
  isOpen,
  onClose,
  startOfDayHour,
  is24Hour,
  onSave,
}: {
  isOpen: boolean;
  onClose: () => void;
  startOfDayHour: number;
  is24Hour: boolean;
  onSave: (hour: number) => void;
}) {
  // Re-key the inner content so opening the modal afresh re-seeds the
  // hook from the current persisted value (the hook ignores prop changes
  // after mount).
  if (!isOpen) return null;
  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Start of Day" size="sm">
      <StartOfDayPickerBody
        startOfDayHour={startOfDayHour}
        is24Hour={is24Hour}
        onSave={onSave}
        onCancel={onClose}
      />
    </Modal>
  );
}

function StartOfDayPickerBody({
  startOfDayHour,
  is24Hour,
  onSave,
  onCancel,
}: {
  startOfDayHour: number;
  is24Hour: boolean;
  onSave: (hour: number) => void;
  onCancel: () => void;
}) {
  const api = useAnalogClockState({
    initialHour: startOfDayHour,
    initialMinute: 0,
    initialSecond: 0,
    is24Hour,
  });
  return (
    <div className="flex flex-col items-center gap-4">
      <p className="text-center text-xs text-[var(--color-text-secondary)]">
        Pick the hour the logical day rolls over. The data model stores
        the hour only — minute and second are visual.
      </p>
      <AnalogClockPicker api={api} />
      <div className="flex w-full items-center justify-end gap-2 pt-2">
        <Button variant="secondary" onClick={onCancel}>
          Cancel
        </Button>
        <Button variant="primary" onClick={() => onSave(api.state.hour)}>
          Set
        </Button>
      </div>
    </div>
  );
}

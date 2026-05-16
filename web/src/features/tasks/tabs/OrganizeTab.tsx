import { Plus, Trash2, Loader2, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { ClassificationChipRow } from '@/features/tasks/ClassificationChipRow';
import { DependencyEditor } from '@/features/tasks/DependencyEditor';
import type {
  CognitiveLoad,
  LifeCategory,
  TaskMode,
  Task,
} from '@/types/task';
import type { Project } from '@/types/project';
import type { Tag } from '@/types/tag';

/**
 * Organize tab content for the task editor — mirrors Android
 * `addedittask/tabs/OrganizeTab.kt`.
 *
 * Sections (in order):
 *   1. Project selector
 *   2. Tags (FlowRow + add)
 *   3. Life Category (chips + AI "Auto" button)
 *   4. Task Mode (chips + on-device "Auto")
 *   5. Cognitive Load (chips + on-device "Auto")
 *   6. Focus-Release per-task overrides (Good-Enough minutes, max revisions)
 *   7. Blockers / Dependencies
 *   8. Notes
 *   9. Delete Task button (edit mode only) + confirmation dialog
 */

/**
 * Tag palette used by the inline tag-create form. Exported so the
 * parent `TaskEditor` can seed `newTagColor` from the same source —
 * keeps the swatch row and the initial-state seed in sync.
 */
// eslint-disable-next-line react-refresh/only-export-components -- parity batch follow-up; see #1573
export const TAG_COLORS = [
  '#ef4444', '#f97316', '#f59e0b', '#eab308',
  '#84cc16', '#22c55e', '#14b8a6', '#06b6d4',
  '#3b82f6', '#6366f1', '#a855f7', '#ec4899',
];

const LIFE_CATEGORY_OPTIONS: { value: LifeCategory; label: string }[] = [
  { value: 'WORK', label: 'Work' },
  { value: 'PERSONAL', label: 'Personal' },
  { value: 'SELF_CARE', label: 'Self-Care' },
  { value: 'HEALTH', label: 'Health' },
];

const COGNITIVE_LOAD_OPTIONS: { value: CognitiveLoad; label: string }[] = [
  { value: 'EASY', label: 'Easy-Load' },
  { value: 'MEDIUM', label: 'Medium-Load' },
  { value: 'HARD', label: 'Hard-Load' },
];

const TASK_MODE_OPTIONS: { value: TaskMode; label: string }[] = [
  { value: 'WORK', label: 'Work-Mode' },
  { value: 'PLAY', label: 'Play-Mode' },
  { value: 'RELAX', label: 'Relax-Mode' },
];

interface FocusReleaseOverrideRowProps {
  title: string;
  enabledLabel: string;
  disabledLabel: string;
  overrideAriaLabel: string;
  valueAriaLabel: string;
  placeholder: string;
  min: number;
  max: number;
  overrideEnabled: boolean;
  value: string;
  onOverrideToggle: (enabled: boolean) => void;
  onValueChange: (raw: string) => void;
}

function FocusReleaseOverrideRow({
  title,
  enabledLabel,
  disabledLabel,
  overrideAriaLabel,
  valueAriaLabel,
  placeholder,
  min,
  max,
  overrideEnabled,
  value,
  onOverrideToggle,
  onValueChange,
}: FocusReleaseOverrideRowProps) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="flex items-center justify-between gap-3">
        <div className="flex flex-col">
          <span className="text-sm text-[var(--color-text-primary)]">
            {title}
          </span>
          <span className="text-xs text-[var(--color-text-secondary)]">
            {overrideEnabled ? enabledLabel : disabledLabel}
          </span>
        </div>
        <input
          type="checkbox"
          role="switch"
          checked={overrideEnabled}
          onChange={(e) => onOverrideToggle(e.target.checked)}
          className="h-5 w-9 cursor-pointer appearance-none rounded-full bg-[var(--color-border)] transition-colors checked:bg-[var(--color-accent)] relative after:absolute after:top-0.5 after:left-0.5 after:h-4 after:w-4 after:rounded-full after:bg-white after:transition-transform checked:after:translate-x-4"
          aria-label={overrideAriaLabel}
        />
      </label>
      {overrideEnabled && (
        <input
          type="number"
          min={min}
          max={max}
          value={value}
          onChange={(e) => onValueChange(e.target.value)}
          placeholder={placeholder}
          className="w-32 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
          aria-label={valueAriaLabel}
        />
      )}
    </div>
  );
}

export interface OrganizeTabProps {
  isCreate: boolean;
  selectedTask: Task | null;
  projects: Project[];
  projectId: string | null;
  onProjectChange: (id: string | null) => void;
  tags: Tag[];
  taskTagIds: string[];
  onToggleTag: (id: string) => void;
  showNewTag: boolean;
  onToggleNewTag: () => void;
  newTagName: string;
  newTagColor: string;
  onNewTagNameChange: (v: string) => void;
  onNewTagColorChange: (c: string) => void;
  onCreateTag: () => void;
  // Life Category
  lifeCategory: LifeCategory | '';
  onLifeCategoryChange: (v: LifeCategory | '') => void;
  onLifeCategoryAutoClick: () => void;
  lifeCategoryAutoBusy: boolean;
  aiFeaturesEnabled: boolean;
  // Task Mode
  taskMode: TaskMode | '';
  onTaskModeChange: (v: TaskMode | '') => void;
  onTaskModeAutoClick: () => void;
  taskModeAutoBusy: boolean;
  // Cognitive Load
  cognitiveLoad: CognitiveLoad | '';
  onCognitiveLoadChange: (v: CognitiveLoad | '') => void;
  onCognitiveLoadAutoClick: () => void;
  cognitiveLoadAutoBusy: boolean;
  // Focus-Release overrides
  goodEnoughOverrideEnabled: boolean;
  goodEnoughMinutes: string;
  onGoodEnoughOverrideToggle: (enabled: boolean) => void;
  onGoodEnoughMinutesChange: (raw: string) => void;
  maxRevisionsOverrideEnabled: boolean;
  maxRevisions: string;
  onMaxRevisionsOverrideToggle: (enabled: boolean) => void;
  onMaxRevisionsChange: (raw: string) => void;
  // Notes
  notes: string;
  onNotesChange: (v: string) => void;
  // Title (needed for auto-classify gating)
  title: string;
  // Delete (edit mode only)
  onRequestDelete: () => void;
}

export function OrganizeTab(props: OrganizeTabProps) {
  const {
    isCreate,
    selectedTask,
    projects,
    projectId,
    onProjectChange,
    tags,
    taskTagIds,
    onToggleTag,
    showNewTag,
    onToggleNewTag,
    newTagName,
    newTagColor,
    onNewTagNameChange,
    onNewTagColorChange,
    onCreateTag,
    lifeCategory,
    onLifeCategoryChange,
    onLifeCategoryAutoClick,
    lifeCategoryAutoBusy,
    aiFeaturesEnabled,
    taskMode,
    onTaskModeChange,
    onTaskModeAutoClick,
    taskModeAutoBusy,
    cognitiveLoad,
    onCognitiveLoadChange,
    onCognitiveLoadAutoClick,
    cognitiveLoadAutoBusy,
    goodEnoughOverrideEnabled,
    goodEnoughMinutes,
    onGoodEnoughOverrideToggle,
    onGoodEnoughMinutesChange,
    maxRevisionsOverrideEnabled,
    maxRevisions,
    onMaxRevisionsOverrideToggle,
    onMaxRevisionsChange,
    notes,
    onNotesChange,
    title,
    onRequestDelete,
  } = props;

  return (
    <div className="flex flex-col gap-4" data-testid="task-editor-organize-tab">
      {/* Project */}
      <div>
        <label
          htmlFor="task-project-select"
          className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
        >
          Project
        </label>
        <select
          id="task-project-select"
          value={projectId || ''}
          onChange={(e) => onProjectChange(e.target.value || null)}
          className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        >
          <option value="">No Project</option>
          {projects.map((p) => (
            <option key={p.id} value={p.id}>
              {p.title}
            </option>
          ))}
        </select>
      </div>

      {/* Tags */}
      <div>
        <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
          Tags
        </label>
        <div className="flex flex-wrap gap-1.5">
          {tags.map((tag) => (
            <button
              key={tag.id}
              type="button"
              onClick={() => onToggleTag(tag.id)}
              aria-pressed={taskTagIds.includes(tag.id)}
              className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition-colors ${
                taskTagIds.includes(tag.id)
                  ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                  : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:border-[var(--color-accent)]'
              }`}
            >
              <span
                className="h-2 w-2 rounded-full"
                style={{
                  backgroundColor: tag.color || 'var(--color-accent)',
                }}
              />
              {tag.name}
            </button>
          ))}
          <button
            type="button"
            onClick={onToggleNewTag}
            className="inline-flex items-center gap-1 rounded-full border border-dashed border-[var(--color-border)] px-2.5 py-1 text-xs text-[var(--color-text-secondary)] hover:border-[var(--color-accent)] hover:text-[var(--color-accent)]"
          >
            <Plus className="h-3 w-3" />
            New Tag
          </button>
        </div>

        {showNewTag && (
          <div className="mt-2 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3">
            <input
              type="text"
              value={newTagName}
              onChange={(e) => onNewTagNameChange(e.target.value)}
              placeholder="Tag Name..."
              aria-label="New Tag Name"
              className="mb-2 w-full rounded-md border border-[var(--color-border)] bg-[var(--color-bg-card)] px-2 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
              autoFocus
              onKeyDown={(e) => {
                if (e.key === 'Enter') onCreateTag();
              }}
            />
            <div className="mb-2 flex flex-wrap gap-1.5">
              {TAG_COLORS.map((c) => (
                <button
                  key={c}
                  type="button"
                  onClick={() => onNewTagColorChange(c)}
                  aria-label={`Pick Color ${c}`}
                  className={`h-6 w-6 rounded-full transition-transform ${
                    newTagColor === c
                      ? 'scale-110 ring-2 ring-offset-1'
                      : ''
                  }`}
                  style={{
                    backgroundColor: c,
                    outlineColor: c,
                  }}
                />
              ))}
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="ghost" size="sm" onClick={onToggleNewTag}>
                Cancel
              </Button>
              <Button size="sm" onClick={onCreateTag}>
                Create
              </Button>
            </div>
          </div>
        )}
      </div>

      {/* Life Category — chips + AI Auto button */}
      <div>
        <div className="mb-1 flex items-center justify-between">
          <label className="block text-xs font-medium text-[var(--color-text-secondary)]">
            Life Category
          </label>
          <button
            type="button"
            onClick={onLifeCategoryAutoClick}
            disabled={
              lifeCategoryAutoBusy || !aiFeaturesEnabled || !title.trim()
            }
            aria-label="Auto-Classify Life Category with AI"
            title={
              !aiFeaturesEnabled
                ? 'AI Features are off — enable them in Settings'
                : !title.trim()
                  ? 'Enter a Title First'
                  : 'Auto-Classify with AI'
            }
            className="inline-flex items-center gap-1 rounded-full border border-[var(--color-accent)]/40 bg-[var(--color-accent)]/10 px-2 py-0.5 text-[10px] font-medium text-[var(--color-accent)] transition hover:bg-[var(--color-accent)]/20 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {lifeCategoryAutoBusy ? (
              <Loader2 className="h-3 w-3 animate-spin" />
            ) : (
              <Sparkles className="h-3 w-3" />
            )}
            Auto
          </button>
        </div>
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="Life Category">
          {LIFE_CATEGORY_OPTIONS.map((opt) => {
            const selected = lifeCategory === opt.value;
            return (
              <button
                key={opt.value}
                type="button"
                onClick={() =>
                  onLifeCategoryChange(selected ? '' : opt.value)
                }
                aria-pressed={selected}
                className={`rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
                  selected
                    ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                    : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:border-[var(--color-accent)] hover:text-[var(--color-text-primary)]'
                }`}
              >
                {opt.label}
              </button>
            );
          })}
          <button
            type="button"
            onClick={() => onLifeCategoryChange('')}
            aria-pressed={lifeCategory === ''}
            className={`rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
              lifeCategory === ''
                ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:border-[var(--color-accent)] hover:text-[var(--color-text-primary)]'
            }`}
          >
            Uncategorized
          </button>
        </div>
        <p className="mt-1 text-xs text-[var(--color-text-secondary)]">
          Powers the Work-Life Balance dashboard. Tap Auto to let Claude
          classify the task from its Title + Description.
        </p>
      </div>

      {/* Task Mode — chips + on-device Auto */}
      <ClassificationChipRow<TaskMode>
        label="Task Mode"
        value={taskMode}
        options={TASK_MODE_OPTIONS}
        onChange={onTaskModeChange}
        onAutoClick={onTaskModeAutoClick}
        autoBusy={taskModeAutoBusy}
        autoDisabled={!title.trim()}
        autoTooltip={
          !title.trim()
            ? 'Enter a Title First'
            : 'Auto-Classify From Keywords'
        }
        autoAriaLabel="Auto-Classify Task Mode"
        helperText="What kind of output does this produce? Orthogonal to Life Category and Cognitive Load. Tap Auto to classify from the Title + Description."
      />

      {/* Cognitive Load — chips + on-device Auto */}
      <ClassificationChipRow<CognitiveLoad>
        label="Cognitive Load"
        value={cognitiveLoad}
        options={COGNITIVE_LOAD_OPTIONS}
        onChange={onCognitiveLoadChange}
        onAutoClick={onCognitiveLoadAutoClick}
        autoBusy={cognitiveLoadAutoBusy}
        autoDisabled={!title.trim()}
        autoTooltip={
          !title.trim()
            ? 'Enter a Title First'
            : 'Auto-Classify From Keywords'
        }
        autoAriaLabel="Auto-Classify Cognitive Load"
        helperText="How hard is it to start? Independent of duration, importance, and reward type. Tap Auto to classify from the Title + Description."
      />

      {/* Focus-Release per-task overrides */}
      {!isCreate && (
        <div className="flex flex-col gap-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)]/40 p-3">
          <div className="flex flex-col gap-0.5">
            <span className="text-sm font-medium text-[var(--color-text-primary)]">
              Focus & Release Overrides
            </span>
            <span className="text-xs text-[var(--color-text-secondary)]">
              Per-task overrides for the Good-Enough Timer and anti-rework
              guard. Leave off to inherit the global defaults from your ND
              preferences.
            </span>
          </div>

          <FocusReleaseOverrideRow
            title="Good-Enough Timer Minutes"
            enabledLabel="Override the Global Good-Enough Threshold"
            disabledLabel="Use the Global Good-Enough Threshold"
            overrideAriaLabel="Override Good-Enough Timer Minutes"
            valueAriaLabel="Good-Enough Timer Minutes Value"
            placeholder="e.g. 25"
            min={1}
            max={240}
            overrideEnabled={goodEnoughOverrideEnabled}
            value={goodEnoughMinutes}
            onOverrideToggle={onGoodEnoughOverrideToggle}
            onValueChange={onGoodEnoughMinutesChange}
          />

          <FocusReleaseOverrideRow
            title="Max Revisions"
            enabledLabel="Override the Global Revision Limit"
            disabledLabel="Use the Global Revision Limit"
            overrideAriaLabel="Override Max Revisions"
            valueAriaLabel="Max Revisions Value"
            placeholder="e.g. 3"
            min={1}
            max={20}
            overrideEnabled={maxRevisionsOverrideEnabled}
            value={maxRevisions}
            onOverrideToggle={onMaxRevisionsOverrideToggle}
            onValueChange={onMaxRevisionsChange}
          />
        </div>
      )}

      {/* Blockers / Dependencies */}
      {selectedTask ? (
        <DependencyEditor taskId={selectedTask.id} />
      ) : (
        <div>
          <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
            Blockers
          </label>
          <p className="text-xs italic text-[var(--color-text-secondary)]">
            Save the Task First to Add Blockers.
          </p>
        </div>
      )}

      {/* Notes */}
      <div>
        <label
          htmlFor="task-notes-input"
          className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]"
        >
          Notes
        </label>
        <textarea
          id="task-notes-input"
          value={notes}
          onChange={(e) => onNotesChange(e.target.value)}
          placeholder="Add Notes..."
          rows={4}
          className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        />
      </div>

      {/* Delete Task — bottom-of-tab parity with Android Organize tab */}
      {!isCreate && (
        <div className="mt-2 border-t border-[var(--color-border)] pt-4">
          <Button
            variant="danger"
            size="sm"
            onClick={onRequestDelete}
            aria-label="Delete Task"
          >
            <Trash2 className="h-4 w-4" />
            Delete Task
          </Button>
        </div>
      )}
    </div>
  );
}

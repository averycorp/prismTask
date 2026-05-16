import { useState, useRef, useEffect, useCallback } from 'react';
import { Sparkles, X, Loader2, FileText, ListPlus } from 'lucide-react';
import { toast } from 'sonner';
import { useNavigate } from 'react-router-dom';
import { parseApi } from '@/api/parse';
import { Button } from '@/components/ui/Button';
import { ProUpgradeModal } from '@/components/shared/ProUpgradeModal';
import { useTemplateStore } from '@/stores/templateStore';
import { useBatchStore } from '@/stores/batchStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { useProFeature } from '@/hooks/useProFeature';
import { detectBatchIntent } from '@/utils/batchIntentDetector';
import { detectMultiCreate } from '@/utils/multiCreateDetector';
import { parseQuickAdd } from '@/utils/nlp';
import type { NLPParseResult } from '@/types/api';
import type { TaskTemplate } from '@/types/template';

interface MultiTaskPreviewItem {
  title: string;
  due_date: string | null;
  priority: number | null;
}

interface NLPInputProps {
  onTaskCreate?: (data: { title: string; due_date?: string; priority?: number; project_suggestion?: string }) => void;
  onTemplateUse?: (templateId: string) => void;
  className?: string;
}

export function NLPInput({ onTaskCreate, onTemplateUse, className = '' }: NLPInputProps) {
  const [value, setValue] = useState('');
  const [loading, setLoading] = useState(false);
  const [parseResult, setParseResult] = useState<NLPParseResult | null>(null);
  const [editedResult, setEditedResult] = useState<Partial<NLPParseResult>>({});
  const [templateSuggestions, setTemplateSuggestions] = useState<TaskTemplate[]>([]);
  const [selectedTemplateIdx, setSelectedTemplateIdx] = useState(0);
  // Multi-task paste preview (parity B.9). Null when input is a single
  // task; populated when the detector flags newline / comma-with-marker
  // segments. Confirming fans out one `onTaskCreate` call per item.
  const [multiPreview, setMultiPreview] = useState<
    MultiTaskPreviewItem[] | null
  >(null);
  const [submittingMulti, setSubmittingMulti] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  const templateDropdownRef = useRef<HTMLDivElement>(null);
  const multiPreviewRef = useRef<HTMLDivElement>(null);

  const { templates, fetch: fetchTemplates, use: applyTemplate } = useTemplateStore();
  const navigate = useNavigate();
  const setPendingBatchCommand = useBatchStore((s) => s.setPendingCommand);
  const confirmTaskBeforeSave = useSettingsStore((s) => s.confirmTaskBeforeSave);
  const { isPro, showUpgrade, setShowUpgrade } = useProFeature();

  // Fetch templates on mount for autocomplete
  useEffect(() => {
    fetchTemplates();
  }, [fetchTemplates]);

  // Global `/` shortcut
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (
        e.key === '/' &&
        !e.ctrlKey &&
        !e.metaKey &&
        !e.altKey &&
        document.activeElement?.tagName !== 'INPUT' &&
        document.activeElement?.tagName !== 'TEXTAREA' &&
        !document.activeElement?.getAttribute('contenteditable')
      ) {
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, []);

  // Close popover on outside click
  useEffect(() => {
    if (
      !parseResult &&
      templateSuggestions.length === 0 &&
      !multiPreview
    ) {
      return;
    }
    const handler = (e: MouseEvent) => {
      const target = e.target as Node;
      const inPopover = popoverRef.current?.contains(target);
      const inTemplate = templateDropdownRef.current?.contains(target);
      const inMulti = multiPreviewRef.current?.contains(target);
      if (!inPopover && !inTemplate && !inMulti) {
        setParseResult(null);
        setEditedResult({});
        setTemplateSuggestions([]);
        setMultiPreview(null);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [parseResult, templateSuggestions, multiPreview]);

  // `<input type="text">` collapses pasted newlines to spaces, so the
  // detector is fed by two sources: typed comma+marker lists (via
  // `handleValueChange`) and a paste interceptor that preserves the raw
  // multi-line clipboard text (`handlePaste`).
  const evaluateMulti = useCallback((text: string) => {
    const detection = detectMultiCreate(text);
    if (detection.kind !== 'multi-create') {
      setMultiPreview(null);
      return;
    }
    setMultiPreview(
      detection.segments.map((seg) => {
        const result = parseQuickAdd(seg);
        return {
          title: result.title || seg,
          due_date: result.dueDate,
          priority: result.priority,
        };
      }),
    );
  }, []);

  const handlePaste = useCallback(
    (e: React.ClipboardEvent<HTMLInputElement>) => {
      const pasted = e.clipboardData.getData('text');
      if (!pasted || !pasted.includes('\n')) return;
      e.preventDefault();
      const input = inputRef.current;
      const start = input?.selectionStart ?? value.length;
      const end = input?.selectionEnd ?? value.length;
      const next = value.slice(0, start) + pasted + value.slice(end);
      setValue(next);
      evaluateMulti(next);
    },
    [value, evaluateMulti],
  );

  // Template autocomplete when typing /templatename
  const handleValueChange = useCallback(
    (newValue: string) => {
      setValue(newValue);

      // Check for /template shortcut
      const slashMatch = newValue.match(/^\/(\S*)$/);
      if (slashMatch) {
        const query = slashMatch[1].toLowerCase();
        const matches = templates.filter(
          (t) =>
            t.name.toLowerCase().includes(query) ||
            t.template_title?.toLowerCase().includes(query),
        );
        setTemplateSuggestions(matches.slice(0, 6));
        setSelectedTemplateIdx(0);
      } else {
        setTemplateSuggestions([]);
      }

      evaluateMulti(newValue);
    },
    [templates, evaluateMulti],
  );

  const handleTemplateSelect = useCallback(async (template: TaskTemplate) => {
    setTemplateSuggestions([]);
    setValue('');
    if (onTemplateUse) {
      onTemplateUse(template.id);
    } else {
      try {
        const result = await applyTemplate(template.id);
        toast.success(result.message || `Task created from "${template.name}"`);
      } catch {
        toast.error('Failed to use template');
      }
    }
  }, [onTemplateUse, applyTemplate]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (templateSuggestions.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedTemplateIdx((prev) =>
          prev < templateSuggestions.length - 1 ? prev + 1 : 0,
        );
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedTemplateIdx((prev) =>
          prev > 0 ? prev - 1 : templateSuggestions.length - 1,
        );
      } else if (e.key === 'Enter') {
        e.preventDefault();
        handleTemplateSelect(templateSuggestions[selectedTemplateIdx]);
      } else if (e.key === 'Escape') {
        setTemplateSuggestions([]);
      }
    }
  };

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    const text = value.trim();
    if (!text) return;

    // Multi-task preview is showing — Enter is a no-op so the user
    // commits explicitly via the "Create N Tasks" button (avoids
    // accidental fan-out on a stray Enter).
    if (multiPreview) {
      return;
    }

    // If it's a template shortcut, handle via template
    if (text.startsWith('/') && templateSuggestions.length > 0) {
      handleTemplateSelect(templateSuggestions[selectedTemplateIdx]);
      return;
    }

    // Batch-intent intercept — must run before the single-task parser so
    // commands like "reschedule all overdue tasks to tomorrow" route to
    // the preview screen instead of getting flattened into one task.
    const batchIntent = detectBatchIntent(text);
    if (batchIntent.kind === 'batch') {
      if (!isPro) {
        setShowUpgrade(true);
        return;
      }
      setPendingBatchCommand(batchIntent.command_text);
      setValue('');
      setParseResult(null);
      setEditedResult({});
      navigate('/batch/preview');
      return;
    }

    setLoading(true);
    try {
      const result = await parseApi.parse({ text });
      // When confirmation is disabled, skip the preview popover and
      // submit straight to the caller. Mirrors Android's
      // `showConfirmation=false` direct-insert path.
      if (!confirmTaskBeforeSave) {
        onTaskCreate?.({
          title: result.title || text,
          due_date: result.due_date ?? undefined,
          priority: result.priority ?? undefined,
          project_suggestion: result.project_suggestion ?? undefined,
        });
        setValue('');
        return;
      }
      setParseResult(result);
      setEditedResult({
        title: result.title,
        due_date: result.due_date,
        priority: result.priority,
        project_suggestion: result.project_suggestion,
      });
    } catch {
      // On parse error, fall back to raw text. Still respect the
      // confirm-before-save preference — when it's off we just create
      // the task with the raw title.
      if (!confirmTaskBeforeSave) {
        onTaskCreate?.({ title: text });
        setValue('');
        return;
      }
      setParseResult({
        title: text,
        project_suggestion: null,
        due_date: null,
        priority: null,
        parent_task_suggestion: null,
        confidence: 0,
        suggestions: [],
        needs_confirmation: false,
      });
      setEditedResult({ title: text });
    } finally {
      setLoading(false);
    }
  }, [
    value,
    templateSuggestions,
    selectedTemplateIdx,
    handleTemplateSelect,
    isPro,
    setShowUpgrade,
    setPendingBatchCommand,
    navigate,
    multiPreview,
    confirmTaskBeforeSave,
    onTaskCreate,
  ]);

  const handleConfirmMulti = useCallback(async () => {
    if (!multiPreview || !onTaskCreate) return;
    setSubmittingMulti(true);
    let failed = 0;
    for (const item of multiPreview) {
      try {
        await onTaskCreate({
          title: item.title,
          due_date: item.due_date ?? undefined,
          priority: item.priority ?? undefined,
        });
      } catch {
        failed += 1;
      }
    }
    setSubmittingMulti(false);
    const total = multiPreview.length;
    setMultiPreview(null);
    setValue('');
    if (failed === 0) {
      toast.success(`Created ${total} ${total === 1 ? 'task' : 'tasks'}`);
    } else if (failed < total) {
      toast.error(`Created ${total - failed} of ${total}; ${failed} failed`);
    } else {
      toast.error('Failed to create tasks');
    }
  }, [multiPreview, onTaskCreate]);

  const handleCancelMulti = useCallback(() => {
    setMultiPreview(null);
  }, []);

  const handleConfirm = () => {
    onTaskCreate?.({
      title: (editedResult.title || parseResult?.title) ?? '',
      due_date: editedResult.due_date ?? parseResult?.due_date ?? undefined,
      priority: editedResult.priority ?? parseResult?.priority ?? undefined,
      project_suggestion: editedResult.project_suggestion ?? parseResult?.project_suggestion ?? undefined,
    });
    setValue('');
    setParseResult(null);
    setEditedResult({});
  };

  const handleCancel = () => {
    setParseResult(null);
    setEditedResult({});
  };

  const priorityLabels: Record<number, string> = { 1: 'Urgent', 2: 'High', 3: 'Medium', 4: 'Low' };

  return (
    <div className={`relative flex-1 max-w-2xl ${className}`}>
      <form onSubmit={handleSubmit}>
        <div className="relative">
          <Sparkles className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--color-text-secondary)]" />
          <input
            ref={inputRef}
            type="text"
            placeholder="Add task... (e.g. 'Buy milk tomorrow !high #shopping')  Press /"
            value={value}
            onChange={(e) => handleValueChange(e.target.value)}
            onKeyDown={handleKeyDown}
            onPaste={handlePaste}
            className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] py-2 pl-10 pr-4 text-sm text-[var(--color-text-primary)] placeholder-[var(--color-text-secondary)] outline-none transition-colors focus:border-[var(--color-accent)] focus:ring-1 focus:ring-[var(--color-accent)]"
            aria-label="Quick add task"
          />
          {loading && (
            <Loader2 className="absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 animate-spin text-[var(--color-accent)]" />
          )}
        </div>
      </form>

      {/* Template autocomplete dropdown */}
      {templateSuggestions.length > 0 && (
        <div
          ref={templateDropdownRef}
          className="absolute left-0 top-full z-50 mt-1 w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] shadow-lg overflow-hidden"
        >
          <div className="px-3 py-1.5 text-xs font-medium text-[var(--color-text-secondary)] bg-[var(--color-bg-secondary)]">
            Templates
          </div>
          {templateSuggestions.map((t, i) => (
            <button
              key={t.id}
              onClick={() => handleTemplateSelect(t)}
              className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors ${
                i === selectedTemplateIdx
                  ? 'bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                  : 'text-[var(--color-text-primary)] hover:bg-[var(--color-bg-secondary)]'
              }`}
            >
              <span>{t.icon || '📋'}</span>
              <div className="flex-1 min-w-0">
                <span className="font-medium">{t.name}</span>
                {t.description && (
                  <span className="ml-2 text-xs text-[var(--color-text-secondary)] truncate">
                    {t.description}
                  </span>
                )}
              </div>
              <FileText className="h-3.5 w-3.5 shrink-0 text-[var(--color-text-secondary)]" />
            </button>
          ))}
        </div>
      )}

      {/* Parse result popover */}
      {parseResult && (
        <div
          ref={popoverRef}
          className="absolute left-0 top-full z-50 mt-2 w-full rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4 shadow-lg"
        >
          <div className="mb-3 flex items-center justify-between">
            <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">
              Parsed Task
            </h4>
            <button
              onClick={handleCancel}
              className="text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          <div className="flex flex-col gap-3">
            {/* Title */}
            <div>
              <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                Title
              </label>
              <input
                type="text"
                value={editedResult.title || ''}
                onChange={(e) => setEditedResult({ ...editedResult, title: e.target.value })}
                className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
              />
            </div>

            <div className="grid grid-cols-2 gap-3">
              {/* Due date */}
              <div>
                <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                  Due Date
                </label>
                <input
                  type="date"
                  value={editedResult.due_date || ''}
                  onChange={(e) => setEditedResult({ ...editedResult, due_date: e.target.value || null })}
                  className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
              </div>

              {/* Priority */}
              <div>
                <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                  Priority
                </label>
                <select
                  value={editedResult.priority ?? ''}
                  onChange={(e) =>
                    setEditedResult({
                      ...editedResult,
                      priority: e.target.value ? Number(e.target.value) : null,
                    })
                  }
                  className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                >
                  <option value="">None</option>
                  {[1, 2, 3, 4].map((p) => (
                    <option key={p} value={p}>
                      {priorityLabels[p]}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            {/* Project suggestion */}
            {editedResult.project_suggestion && (
              <div>
                <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                  Project Suggestion
                </label>
                <input
                  type="text"
                  value={editedResult.project_suggestion || ''}
                  onChange={(e) =>
                    setEditedResult({ ...editedResult, project_suggestion: e.target.value || null })
                  }
                  className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
              </div>
            )}
          </div>

          <div className="mt-4 flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={handleCancel}>
              Cancel
            </Button>
            <Button size="sm" onClick={handleConfirm}>
              Add Task
            </Button>
          </div>
        </div>
      )}

      {/* Multi-task paste preview (parity B.9). */}
      {multiPreview && (
        <div
          ref={multiPreviewRef}
          className="absolute left-0 top-full z-50 mt-2 w-full rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4 shadow-lg"
          role="dialog"
          aria-label="Multi-task paste preview"
        >
          <div className="mb-3 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <ListPlus
                className="h-4 w-4 text-[var(--color-accent)]"
                aria-hidden="true"
              />
              <h4 className="text-sm font-semibold text-[var(--color-text-primary)]">
                Create {multiPreview.length} Tasks?
              </h4>
            </div>
            <button
              onClick={handleCancelMulti}
              className="text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
              aria-label="Cancel multi-task create"
            >
              <X className="h-4 w-4" aria-hidden="true" />
            </button>
          </div>

          <ul className="max-h-64 overflow-y-auto rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)]">
            {multiPreview.map((item, i) => (
              <li
                key={i}
                className="flex items-start gap-2 border-b border-[var(--color-border)] px-3 py-2 last:border-b-0"
              >
                <span className="mt-0.5 text-xs font-mono text-[var(--color-text-secondary)]">
                  {i + 1}.
                </span>
                <div className="flex-1 min-w-0">
                  <div className="text-sm text-[var(--color-text-primary)] truncate">
                    {item.title}
                  </div>
                  {(item.due_date || item.priority) && (
                    <div className="mt-0.5 flex gap-2 text-xs text-[var(--color-text-secondary)]">
                      {item.due_date && <span>Due {item.due_date}</span>}
                      {item.priority && <span>P{item.priority}</span>}
                    </div>
                  )}
                </div>
              </li>
            ))}
          </ul>

          <div className="mt-4 flex justify-end gap-2">
            <Button
              variant="ghost"
              size="sm"
              onClick={handleCancelMulti}
              disabled={submittingMulti}
            >
              Cancel
            </Button>
            <Button
              size="sm"
              onClick={handleConfirmMulti}
              disabled={submittingMulti}
            >
              {submittingMulti
                ? 'Creating...'
                : `Create ${multiPreview.length} Tasks`}
            </Button>
          </div>
        </div>
      )}

      <ProUpgradeModal
        isOpen={showUpgrade}
        onClose={() => setShowUpgrade(false)}
        featureName="Batch Commands"
        featureDescription="Let AI apply a single command across many tasks at once — rescheduling, tagging, completing, or moving in bulk."
      />
    </div>
  );
}

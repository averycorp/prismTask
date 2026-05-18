import { useState, useEffect } from 'react';
import { Plus, X, GripVertical } from 'lucide-react';
import { toast } from 'sonner';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { useTemplateStore } from '@/stores/templateStore';
import { useProjectStore } from '@/stores/projectStore';
import { useTagStore } from '@/stores/tagStore';
import { PRIORITY_CONFIG } from '@/utils/priority';
import { pickerProjects as filterPickerProjects } from '@/utils/projectFilters';
import type { TaskTemplate, TemplateCreate, TemplateUpdate } from '@/types/template';
import type { TaskPriority } from '@/types/task';

interface TemplateEditorModalProps {
  isOpen: boolean;
  onClose: () => void;
  template?: TaskTemplate | null;
  prefillFromTask?: {
    title?: string;
    description?: string;
    priority?: number;
    project_id?: string;
    tags?: string[];
    subtasks?: string[];
    recurrence_json?: string;
    estimated_duration?: number;
  };
}

const RECURRENCE_TYPES = [
  { value: '', label: 'None' },
  { value: 'daily', label: 'Daily' },
  { value: 'weekly', label: 'Weekly' },
  { value: 'monthly', label: 'Monthly' },
  { value: 'yearly', label: 'Yearly' },
];

const EMOJI_PICKS = [
  '📋', '📝', '🌅', '🤝', '🛒', '🧹', '📚', '💪',
  '🎯', '💡', '🔧', '📊', '🎨', '🏃', '🍳', '✈️',
  '💻', '📞', '🏠', '🎓', '💰', '🌟', '📦', '🔔',
];

export function TemplateEditorModal({
  isOpen,
  onClose,
  template,
  prefillFromTask,
}: TemplateEditorModalProps) {
  const { create, update } = useTemplateStore();
  const { projects } = useProjectStore();
  const { tags } = useTagStore();

  const [saving, setSaving] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [icon, setIcon] = useState('📋');
  const [category, setCategory] = useState('');
  const [templateTitle, setTemplateTitle] = useState('');
  const [templateDescription, setTemplateDescription] = useState('');
  const [templatePriority, setTemplatePriority] = useState<number | null>(null);
  const [templateProjectId, setTemplateProjectId] = useState<string | null>(null);
  const [templateTagIds, setTemplateTagIds] = useState<string[]>([]);
  const [subtasks, setSubtasks] = useState<string[]>([]);
  const [newSubtask, setNewSubtask] = useState('');
  const [recurrenceType, setRecurrenceType] = useState('');
  const [recurrenceInterval, setRecurrenceInterval] = useState(1);
  const [duration, setDuration] = useState('');
  const [showIconPicker, setShowIconPicker] = useState(false);

  const isEditing = !!template;

  useEffect(() => {
    if (template) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- form-init: seed editor buffer from template prop on open
      setName(template.name);
      setDescription(template.description || '');
      setIcon(template.icon || '📋');
      setCategory(template.category || '');
      setTemplateTitle(template.template_title || '');
      setTemplateDescription(template.template_description || '');
      setTemplatePriority(template.template_priority);
      setTemplateProjectId(template.template_project_id);
      setDuration(template.template_duration?.toString() || '');

      if (template.template_tags_json) {
        try {
          setTemplateTagIds(JSON.parse(template.template_tags_json));
        } catch {
          setTemplateTagIds([]);
        }
      }
      if (template.template_subtasks_json) {
        try {
          setSubtasks(JSON.parse(template.template_subtasks_json));
        } catch {
          setSubtasks([]);
        }
      }
      if (template.template_recurrence_json) {
        try {
          const rule = JSON.parse(template.template_recurrence_json);
          setRecurrenceType(rule.type || '');
          setRecurrenceInterval(rule.interval || 1);
        } catch {
          // ignore
        }
      }
    } else if (prefillFromTask) {
      setTemplateTitle(prefillFromTask.title || '');
      setName(prefillFromTask.title || '');
      setTemplateDescription(prefillFromTask.description || '');
      setTemplatePriority(prefillFromTask.priority ?? null);
      setTemplateProjectId(prefillFromTask.project_id ?? null);
      setTemplateTagIds(prefillFromTask.tags || []);
      setSubtasks(prefillFromTask.subtasks || []);
      setDuration(prefillFromTask.estimated_duration?.toString() || '');
      if (prefillFromTask.recurrence_json) {
        try {
          const rule = JSON.parse(prefillFromTask.recurrence_json);
          setRecurrenceType(rule.type || '');
          setRecurrenceInterval(rule.interval || 1);
        } catch {
          // ignore
        }
      }
    } else {
      // Reset all fields
      setName('');
      setDescription('');
      setIcon('📋');
      setCategory('');
      setTemplateTitle('');
      setTemplateDescription('');
      setTemplatePriority(null);
      setTemplateProjectId(null);
      setTemplateTagIds([]);
      setSubtasks([]);
      setNewSubtask('');
      setRecurrenceType('');
      setRecurrenceInterval(1);
      setDuration('');
    }
  }, [template, prefillFromTask, isOpen]);

  const handleAddSubtask = () => {
    if (!newSubtask.trim()) return;
    setSubtasks((prev) => [...prev, newSubtask.trim()]);
    setNewSubtask('');
  };

  const handleRemoveSubtask = (index: number) => {
    setSubtasks((prev) => prev.filter((_, i) => i !== index));
  };

  const toggleTag = (tagId: string) => {
    setTemplateTagIds((prev) =>
      prev.includes(tagId) ? prev.filter((id) => id !== tagId) : [...prev, tagId],
    );
  };

  const handleSave = async () => {
    if (!name.trim()) {
      toast.error('Template name is required');
      return;
    }
    setSaving(true);
    try {
      const recurrenceJson =
        recurrenceType
          ? JSON.stringify({ type: recurrenceType, interval: recurrenceInterval })
          : undefined;

      const data: TemplateCreate | TemplateUpdate = {
        name: name.trim(),
        description: description || undefined,
        icon,
        category: category || undefined,
        template_title: templateTitle || undefined,
        template_description: templateDescription || undefined,
        template_priority: templatePriority ?? undefined,
        template_project_id: templateProjectId ?? undefined,
        template_tags_json: templateTagIds.length > 0 ? JSON.stringify(templateTagIds) : undefined,
        template_subtasks_json: subtasks.length > 0 ? JSON.stringify(subtasks) : undefined,
        template_recurrence_json: recurrenceJson,
        template_duration: duration ? parseInt(duration) : undefined,
      };

      if (isEditing && template) {
        await update(template.id, data);
        toast.success('Template updated');
      } else {
        await create(data as TemplateCreate);
        toast.success('Template created');
      }
      onClose();
    } catch {
      toast.error(isEditing ? 'Failed to update template' : 'Failed to create template');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={isEditing ? 'Edit Template' : 'Create Template'}
      size="lg"
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={handleSave} loading={saving}>
            {isEditing ? 'Save Changes' : 'Create Template'}
          </Button>
        </div>
      }
    >
      <div className="flex flex-col gap-5">
        {/* Template Meta */}
        <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-4">
          <h3 className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
            Template Info
          </h3>
          <div className="flex gap-3">
            {/* Icon picker */}
            <div className="relative">
              <button
                onClick={() => setShowIconPicker(!showIconPicker)}
                className="flex h-12 w-12 items-center justify-center rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] text-2xl hover:border-[var(--color-accent)]"
              >
                {icon}
              </button>
              {showIconPicker && (
                <div className="absolute left-0 top-14 z-50 grid grid-cols-6 gap-1 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] p-2 shadow-lg">
                  {EMOJI_PICKS.map((emoji) => (
                    <button
                      key={emoji}
                      onClick={() => {
                        setIcon(emoji);
                        setShowIconPicker(false);
                      }}
                      className="flex h-8 w-8 items-center justify-center rounded hover:bg-[var(--color-bg-secondary)]"
                    >
                      {emoji}
                    </button>
                  ))}
                </div>
              )}
            </div>
            <div className="flex-1 space-y-2">
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Template name (required)"
                className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                autoFocus
              />
              <div className="flex gap-2">
                <input
                  type="text"
                  value={category}
                  onChange={(e) => setCategory(e.target.value)}
                  placeholder="Category (e.g. Productivity)"
                  className="flex-1 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
              </div>
            </div>
          </div>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Template description..."
            rows={2}
            className="mt-2 w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
          />
        </div>

        {/* Task Blueprint */}
        <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-4">
          <h3 className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
            Task Blueprint
          </h3>
          <div className="space-y-3">
            <input
              type="text"
              value={templateTitle}
              onChange={(e) => setTemplateTitle(e.target.value)}
              placeholder="Default task title"
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            />
            <textarea
              value={templateDescription}
              onChange={(e) => setTemplateDescription(e.target.value)}
              placeholder="Default task description"
              rows={2}
              className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
            />

            {/* Priority */}
            <div>
              <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                Default Priority
              </label>
              <div className="flex gap-2">
                <button
                  onClick={() => setTemplatePriority(null)}
                  className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
                    templatePriority === null
                      ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                      : 'border-[var(--color-border)] text-[var(--color-text-secondary)]'
                  }`}
                >
                  None
                </button>
                {([1, 2, 3, 4] as TaskPriority[]).map((p) => (
                  <button
                    key={p}
                    onClick={() => setTemplatePriority(p)}
                    className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
                      templatePriority === p ? 'border-current' : 'border-[var(--color-border)]'
                    }`}
                    style={{
                      color: templatePriority === p ? PRIORITY_CONFIG[p].color : 'var(--color-text-secondary)',
                      backgroundColor: templatePriority === p ? PRIORITY_CONFIG[p].bgColor : 'transparent',
                    }}
                  >
                    {PRIORITY_CONFIG[p].label}
                  </button>
                ))}
              </div>
            </div>

            {/* Project */}
            <div>
              <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                Default Project
              </label>
              <select
                value={templateProjectId ?? ''}
                onChange={(e) => setTemplateProjectId(e.target.value || null)}
                className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
              >
                <option value="">No Project</option>
                {filterPickerProjects(projects, templateProjectId).map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.title}
                  </option>
                ))}
              </select>
            </div>

            {/* Tags */}
            <div>
              <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                Default Tags
              </label>
              <div className="flex flex-wrap gap-1.5">
                {tags.map((tag) => (
                  <button
                    key={tag.id}
                    onClick={() => toggleTag(tag.id)}
                    className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition-colors ${
                      templateTagIds.includes(tag.id)
                        ? 'border-[var(--color-accent)] bg-[var(--color-accent)]/10 text-[var(--color-accent)]'
                        : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:border-[var(--color-accent)]'
                    }`}
                  >
                    <span
                      className="h-2 w-2 rounded-full"
                      style={{ backgroundColor: tag.color || 'var(--color-accent)' }}
                    />
                    {tag.name}
                  </button>
                ))}
              </div>
            </div>

            {/* Recurrence */}
            <div className="flex gap-3">
              <div className="flex-1">
                <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                  Recurrence
                </label>
                <select
                  value={recurrenceType}
                  onChange={(e) => setRecurrenceType(e.target.value)}
                  className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                >
                  {RECURRENCE_TYPES.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </select>
              </div>
              {recurrenceType && (
                <div className="w-24">
                  <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                    Interval
                  </label>
                  <input
                    type="number"
                    min={1}
                    value={recurrenceInterval}
                    onChange={(e) => setRecurrenceInterval(parseInt(e.target.value) || 1)}
                    className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                  />
                </div>
              )}
              <div className="w-28">
                <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                  Duration (Min)
                </label>
                <input
                  type="number"
                  min={0}
                  value={duration}
                  onChange={(e) => setDuration(e.target.value)}
                  placeholder="e.g. 30"
                  className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
                />
              </div>
            </div>

            {/* Subtasks */}
            <div>
              <label className="mb-1 block text-xs font-medium text-[var(--color-text-secondary)]">
                Default Subtasks
              </label>
              <div className="flex flex-col gap-1">
                {subtasks.map((s, i) => (
                  <div
                    key={i}
                    className="group flex items-center gap-2 rounded-md bg-[var(--color-bg-card)] px-2 py-1.5"
                  >
                    <GripVertical className="h-3 w-3 shrink-0 text-[var(--color-text-secondary)] opacity-50" />
                    <span className="flex-1 text-sm text-[var(--color-text-primary)]">{s}</span>
                    <button
                      onClick={() => handleRemoveSubtask(i)}
                      className="shrink-0 text-[var(--color-text-secondary)] opacity-0 hover:text-red-500 group-hover:opacity-100"
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>
                  </div>
                ))}
              </div>
              <div className="mt-1 flex items-center gap-2">
                <Plus className="h-4 w-4 shrink-0 text-[var(--color-text-secondary)]" />
                <input
                  type="text"
                  value={newSubtask}
                  onChange={(e) => setNewSubtask(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      handleAddSubtask();
                    }
                  }}
                  placeholder="Add subtask..."
                  className="flex-1 border-none bg-transparent py-1 text-sm text-[var(--color-text-primary)] outline-none placeholder-[var(--color-text-secondary)]"
                />
              </div>
            </div>
          </div>
        </div>
      </div>
    </Modal>
  );
}

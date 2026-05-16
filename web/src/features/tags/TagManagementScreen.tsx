import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  DndContext,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import {
  Archive,
  ArchiveRestore,
  ArrowLeft,
  GripVertical,
  Pencil,
  Plus,
  Trash2,
} from 'lucide-react';
import { toast } from 'sonner';

import { Button } from '@/components/ui/Button';
import { Checkbox } from '@/components/ui/Checkbox';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState } from '@/components/ui/EmptyState';
import { Input } from '@/components/ui/Input';
import { Modal } from '@/components/ui/Modal';
import { useTagStore } from '@/stores/tagStore';
import type { Tag } from '@/types/tag';
import { reorderedTagIds, sortTagsForDisplay } from './tagSortHelpers';

const PRESET_COLORS: readonly string[] = [
  '#E86F3C',
  '#D4534A',
  '#4A90D9',
  '#7B61C2',
  '#2E9E6E',
  '#E8B84A',
  '#5B8C5A',
  '#8B5CF6',
  '#EC4899',
  '#06B6D4',
  '#F59E0B',
  '#6B7280',
] as const;

const DEFAULT_COLOR = '#6B7280';

function ColorSwatchPicker({
  value,
  onChange,
}: {
  value: string;
  onChange: (hex: string) => void;
}) {
  return (
    <div className="flex flex-wrap gap-2">
      {PRESET_COLORS.map((hex) => {
        const selected = value.toLowerCase() === hex.toLowerCase();
        return (
          <button
            key={hex}
            type="button"
            onClick={() => onChange(hex)}
            aria-label={`Color ${hex}`}
            aria-pressed={selected}
            className={`h-8 w-8 rounded-full transition-transform ${
              selected
                ? 'scale-110 ring-2 ring-offset-2 ring-offset-[var(--color-bg-card)] ring-[var(--color-accent)]'
                : 'opacity-80 hover:opacity-100 hover:scale-105'
            }`}
            style={{ backgroundColor: hex }}
          />
        );
      })}
    </div>
  );
}

function SortableTagRow({
  tag,
  selected,
  onToggleSelect,
  onEdit,
  onArchiveToggle,
  onDelete,
}: {
  tag: Tag;
  selected: boolean;
  onToggleSelect: () => void;
  onEdit: () => void;
  onArchiveToggle: () => void;
  onDelete: () => void;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: tag.id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  } as const;

  return (
    <li
      ref={setNodeRef}
      style={style}
      data-testid={`tag-row-${tag.id}`}
      className="flex items-center gap-2 rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-card)] px-2 py-2"
    >
      <button
        type="button"
        aria-label={`Drag ${tag.name}`}
        className="cursor-grab touch-none rounded-md p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)] active:cursor-grabbing"
        {...attributes}
        {...listeners}
      >
        <GripVertical className="h-4 w-4" />
      </button>
      <Checkbox
        checked={selected}
        onChange={() => onToggleSelect()}
      />
      <span
        className="h-3 w-3 shrink-0 rounded-full"
        style={{ backgroundColor: tag.color ?? DEFAULT_COLOR }}
      />
      <span
        className={`flex-1 truncate text-sm ${
          tag.archived
            ? 'text-[var(--color-text-secondary)] line-through'
            : 'text-[var(--color-text-primary)]'
        }`}
      >
        {tag.name}
      </span>
      <button
        type="button"
        onClick={onEdit}
        className="rounded-md p-1.5 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
        aria-label={`Edit ${tag.name}`}
      >
        <Pencil className="h-4 w-4" />
      </button>
      <button
        type="button"
        onClick={onArchiveToggle}
        className="rounded-md p-1.5 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
        aria-label={tag.archived ? `Unarchive ${tag.name}` : `Archive ${tag.name}`}
      >
        {tag.archived ? (
          <ArchiveRestore className="h-4 w-4" />
        ) : (
          <Archive className="h-4 w-4" />
        )}
      </button>
      <button
        type="button"
        onClick={onDelete}
        className="rounded-md p-1.5 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-danger,red)]"
        aria-label={`Delete ${tag.name}`}
      >
        <Trash2 className="h-4 w-4" />
      </button>
    </li>
  );
}

interface EditState {
  tag: Tag;
  name: string;
  color: string;
}

export function TagManagementScreen() {
  const navigate = useNavigate();
  const tags = useTagStore((s) => s.tags);
  const fetchTags = useTagStore((s) => s.fetchTags);
  const createTag = useTagStore((s) => s.createTag);
  const updateTag = useTagStore((s) => s.updateTag);
  const deleteTag = useTagStore((s) => s.deleteTag);
  const bulkDeleteTags = useTagStore((s) => s.bulkDeleteTags);
  const reorderTags = useTagStore((s) => s.reorderTags);

  // Lazy initial fetch — `useFirestoreSync` keeps the store warm in the
  // app shell, but we still call here for direct deep-links to /tags
  // (e.g. opening the route in a fresh tab before the subscription has
  // settled).
  useEffect(() => {
    void fetchTags();
  }, [fetchTags]);

  const [showArchived, setShowArchived] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkDeleteOpen, setBulkDeleteOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Tag | null>(null);

  // Add-tag form state.
  const [newName, setNewName] = useState('');
  const [newColor, setNewColor] = useState(DEFAULT_COLOR);

  // Edit modal state.
  const [editState, setEditState] = useState<EditState | null>(null);

  const sorted = useMemo(() => sortTagsForDisplay(tags), [tags]);
  const visible = useMemo(
    () => (showArchived ? sorted : sorted.filter((t) => !t.archived)),
    [showArchived, sorted],
  );
  const visibleIds = useMemo(() => visible.map((t) => t.id), [visible]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
  );

  const handleDragEnd = useCallback(
    async (event: DragEndEvent) => {
      const { active, over } = event;
      if (!over || active.id === over.id) return;
      const fromIndex = visibleIds.indexOf(String(active.id));
      const toIndex = visibleIds.indexOf(String(over.id));
      if (fromIndex < 0 || toIndex < 0) return;
      const next = reorderedTagIds(visible, fromIndex, toIndex);
      try {
        await reorderTags(next);
      } catch (e) {
        toast.error(`Reorder failed: ${(e as Error).message}`);
      }
    },
    [reorderTags, visible, visibleIds],
  );

  const handleToggleSelect = useCallback((id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const handleAddTag = useCallback(async () => {
    const name = newName.trim();
    if (!name) return;
    try {
      await createTag({ name, color: newColor });
      setNewName('');
      setNewColor(DEFAULT_COLOR);
      toast.success(`Created tag "${name}"`);
    } catch (e) {
      toast.error(`Could not create tag: ${(e as Error).message}`);
    }
  }, [createTag, newColor, newName]);

  const handleSaveEdit = useCallback(async () => {
    if (!editState) return;
    const name = editState.name.trim();
    if (!name) {
      toast.error('Tag name is required');
      return;
    }
    try {
      await updateTag(editState.tag.id, { name, color: editState.color });
      setEditState(null);
    } catch (e) {
      toast.error(`Could not update tag: ${(e as Error).message}`);
    }
  }, [editState, updateTag]);

  const handleArchiveToggle = useCallback(
    async (tag: Tag) => {
      try {
        await updateTag(tag.id, { archived: !tag.archived });
      } catch (e) {
        toast.error(`Could not archive tag: ${(e as Error).message}`);
      }
    },
    [updateTag],
  );

  const handleBulkDelete = useCallback(async () => {
    const ids = Array.from(selected);
    if (ids.length === 0) {
      setBulkDeleteOpen(false);
      return;
    }
    try {
      await bulkDeleteTags(ids);
      setSelected(new Set());
      setBulkDeleteOpen(false);
      toast.success(`Deleted ${ids.length} tag${ids.length === 1 ? '' : 's'}`);
    } catch (e) {
      toast.error(`Bulk delete failed: ${(e as Error).message}`);
    }
  }, [bulkDeleteTags, selected]);

  const handleDeleteSingle = useCallback(async () => {
    if (!deleteTarget) return;
    try {
      await deleteTag(deleteTarget.id);
      setDeleteTarget(null);
      toast.success(`Deleted tag "${deleteTarget.name}"`);
    } catch (e) {
      toast.error(`Could not delete tag: ${(e as Error).message}`);
    }
  }, [deleteTag, deleteTarget]);

  return (
    <div className="mx-auto w-full max-w-3xl">
      {/* Header */}
      <div className="mb-4 flex items-center gap-2">
        <button
          type="button"
          onClick={() => navigate(-1)}
          className="rounded-md p-1.5 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)] hover:text-[var(--color-text-primary)]"
          aria-label="Back"
        >
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="flex-1 text-2xl font-bold text-[var(--color-text-primary)]">
          Tag Management
        </h1>
        <Checkbox
          checked={showArchived}
          onChange={() => setShowArchived((v) => !v)}
          label="Show Archived"
        />
      </div>

      {/* Bulk action bar */}
      {selected.size > 0 && (
        <div className="mb-3 flex items-center justify-between rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2">
          <span className="text-sm text-[var(--color-text-primary)]">
            {selected.size} Selected
          </span>
          <div className="flex gap-2">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setSelected(new Set())}
            >
              Clear
            </Button>
            <Button
              variant="danger"
              size="sm"
              onClick={() => setBulkDeleteOpen(true)}
            >
              <Trash2 className="mr-1 h-3.5 w-3.5" /> Delete
            </Button>
          </div>
        </div>
      )}

      {/* Tag list */}
      {visible.length === 0 ? (
        <EmptyState
          icon={<Plus className="h-8 w-8" />}
          title="No Tags"
          description="Create your first tag below to organize tasks across projects."
        />
      ) : (
        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          onDragEnd={handleDragEnd}
        >
          <SortableContext
            items={visibleIds}
            strategy={verticalListSortingStrategy}
          >
            <ul className="mb-6 flex flex-col gap-2" data-testid="tag-list">
              {visible.map((tag) => (
                <SortableTagRow
                  key={tag.id}
                  tag={tag}
                  selected={selected.has(tag.id)}
                  onToggleSelect={() => handleToggleSelect(tag.id)}
                  onEdit={() =>
                    setEditState({
                      tag,
                      name: tag.name,
                      color: tag.color ?? DEFAULT_COLOR,
                    })
                  }
                  onArchiveToggle={() => handleArchiveToggle(tag)}
                  onDelete={() => setDeleteTarget(tag)}
                />
              ))}
            </ul>
          </SortableContext>
        </DndContext>
      )}

      {/* Add tag */}
      <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
        <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">
          Add Tag
        </h2>
        <div className="mb-3">
          <Input
            label="Tag Name"
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder="e.g. urgent"
          />
        </div>
        <div className="mb-3">
          <p className="mb-2 text-xs font-medium text-[var(--color-text-secondary)]">
            Color
          </p>
          <ColorSwatchPicker value={newColor} onChange={setNewColor} />
        </div>
        <Button
          onClick={handleAddTag}
          disabled={!newName.trim()}
          className="w-full"
        >
          <Plus className="mr-1 h-4 w-4" /> Add Tag
        </Button>
      </div>

      {/* Edit modal */}
      {editState && (
        <Modal
          isOpen={true}
          onClose={() => setEditState(null)}
          title="Edit Tag"
          size="sm"
          footer={
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setEditState(null)}>
                Cancel
              </Button>
              <Button onClick={handleSaveEdit}>Save</Button>
            </div>
          }
        >
          <div className="flex flex-col gap-3">
            <Input
              label="Tag Name"
              value={editState.name}
              onChange={(e) =>
                setEditState((prev) =>
                  prev ? { ...prev, name: e.target.value } : prev,
                )
              }
            />
            <div>
              <p className="mb-2 text-xs font-medium text-[var(--color-text-secondary)]">
                Color
              </p>
              <ColorSwatchPicker
                value={editState.color}
                onChange={(hex) =>
                  setEditState((prev) =>
                    prev ? { ...prev, color: hex } : prev,
                  )
                }
              />
            </div>
          </div>
        </Modal>
      )}

      {/* Bulk delete confirmation */}
      <ConfirmDialog
        isOpen={bulkDeleteOpen}
        onClose={() => setBulkDeleteOpen(false)}
        onConfirm={handleBulkDelete}
        title="Delete Tags"
        message={`Delete ${selected.size} tag${selected.size === 1 ? '' : 's'}? This cannot be undone.`}
        confirmLabel="Delete"
        variant="danger"
      />

      {/* Single delete confirmation */}
      <ConfirmDialog
        isOpen={deleteTarget !== null}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleDeleteSingle}
        title="Delete Tag"
        message={`Delete tag "${deleteTarget?.name ?? ''}"? This cannot be undone.`}
        confirmLabel="Delete"
        variant="danger"
      />
    </div>
  );
}

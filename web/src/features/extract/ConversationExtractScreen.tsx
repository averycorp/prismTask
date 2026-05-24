import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Calendar,
  Check,
  ClipboardPaste,
  Flag,
  FolderInput,
  Loader2,
  Sparkles,
  TriangleAlert,
} from 'lucide-react';
import { toast } from 'sonner';
import { aiApi } from '@/api/ai';
import * as firestoreTasks from '@/api/firestore/tasks';
import { androidToWebPriority } from '@/api/firestore/converters';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { useProjectStore } from '@/stores/projectStore';
import { useTaskStore } from '@/stores/taskStore';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { ProUpgradeModal } from '@/components/shared/ProUpgradeModal';
import { useProFeature } from '@/hooks/useProFeature';
import type {
  ExtractFromTextResponse,
  ExtractedTaskCandidate,
} from '@/types/extract';

const MAX_TEXT_LENGTH = 100_000;

const PRIORITY_LABELS: Record<number, string> = {
  1: 'Urgent',
  2: 'High',
  3: 'Medium',
  4: 'Low',
};

function resolveProjectId(
  suggestion: string | null | undefined,
  projects: { id: string; title: string; status: string }[],
): string | null {
  // Skip archived projects so extracted tasks never silently land in an
  // archived bucket (Android `OrganizeTab` parity). The caller passes the
  // full project list; we filter here once.
  const candidates = projects.filter((p) => p.status !== 'archived');
  if (!suggestion) return candidates[0]?.id ?? null;
  const lower = suggestion.toLowerCase();
  const match = candidates.find((p) => p.title.toLowerCase().includes(lower));
  if (match) return match.id;
  // Fall back to the first non-archived project so the task lands
  // somewhere. Users can move it from the task list if that's wrong.
  return candidates[0]?.id ?? null;
}

export function ConversationExtractScreen() {
  const navigate = useNavigate();
  const { isPro, showUpgrade, setShowUpgrade } = useProFeature();
  const projects = useProjectStore((s) => s.projects);
  const fetchAllProjects = useProjectStore((s) => s.fetchAllProjects);
  const fetchToday = useTaskStore((s) => s.fetchToday);

  const [text, setText] = useState('');
  const [extracting, setExtracting] = useState(false);
  const [creating, setCreating] = useState(false);
  const [response, setResponse] = useState<ExtractFromTextResponse | null>(null);
  const [excluded, setExcluded] = useState<Set<number>>(new Set());

  const candidates = useMemo(
    () => response?.tasks ?? [],
    [response],
  );
  const selected = useMemo(
    () => candidates.filter((_, idx) => !excluded.has(idx)),
    [candidates, excluded],
  );
  const overLimit = text.length > MAX_TEXT_LENGTH;

  const handleExtract = async () => {
    if (!isPro) {
      setShowUpgrade(true);
      return;
    }
    const trimmed = text.trim();
    if (!trimmed) return;
    if (overLimit) {
      toast.error(`Paste is too long — max ${MAX_TEXT_LENGTH} characters.`);
      return;
    }

    setExtracting(true);
    try {
      const res = await aiApi.extractFromText({ text: trimmed, source: 'web' });
      setResponse(res);
      setExcluded(new Set());
      if (res.tasks.length === 0) {
        toast.info('No tasks found in the pasted text.');
      } else {
        // Projects might be stale; refresh so resolveProjectId has the
        // freshest list when the user commits.
        fetchAllProjects();
      }
    } catch (e) {
      toast.error((e as Error).message || 'Failed to extract tasks');
    } finally {
      setExtracting(false);
    }
  };

  const toggleCandidate = (idx: number) => {
    setExcluded((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx);
      else next.add(idx);
      return next;
    });
  };

  const handleCreate = async () => {
    if (selected.length === 0) return;
    const uid = (() => {
      try {
        return getFirebaseUid();
      } catch {
        return null;
      }
    })();
    if (!uid) {
      toast.error('Not signed in.');
      return;
    }

    setCreating(true);
    try {
      let createdCount = 0;
      for (const cand of selected) {
        const projectId = resolveProjectId(cand.suggested_project, projects);
        if (!projectId) continue; // no project available — skip silently
        try {
          await firestoreTasks.createTask(uid, {
            title: cand.title,
            project_id: projectId,
            due_date: cand.suggested_due_date ?? null,
            priority: androidToWebPriority(cand.suggested_priority),
          });
          createdCount += 1;
        } catch {
          // Continue on per-task failure; report counts at the end.
        }
      }
      if (createdCount === 0) {
        toast.error('No tasks created — check you have at least one project.');
      } else {
        toast.success(
          `Created ${createdCount} task${createdCount === 1 ? '' : 's'}`,
        );
        fetchToday();
        navigate('/tasks');
      }
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="mx-auto max-w-3xl pb-16">
      <header className="mb-6">
        <h1 className="flex items-center gap-2 text-xl font-semibold text-[var(--color-text-primary)]">
          <ClipboardPaste
            className="h-5 w-5 text-[var(--color-accent)]"
            aria-hidden="true"
          />
          Extract Tasks
        </h1>
        <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
          Paste a chat transcript, meeting note, or email. The AI will pull
          out actionable tasks you can review and create.
        </p>
      </header>

      <section className="mb-4 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
        <label
          htmlFor="extract-text"
          className="mb-2 block text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]"
        >
          Paste text
        </label>
        <textarea
          id="extract-text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={10}
          maxLength={MAX_TEXT_LENGTH + 100}
          placeholder="Paste anything here — chat threads, meeting notes, emails…"
          className="w-full resize-y rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-3 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        />
        <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
          <span
            className={`text-xs ${
              overLimit
                ? 'text-red-500'
                : 'text-[var(--color-text-secondary)]'
            }`}
          >
            {text.length.toLocaleString()} / {MAX_TEXT_LENGTH.toLocaleString()}{' '}
            chars
          </span>
          <Button
            onClick={handleExtract}
            disabled={!text.trim() || overLimit || extracting}
          >
            {extracting ? (
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            ) : (
              <Sparkles className="mr-2 h-4 w-4" />
            )}
            Extract tasks
          </Button>
        </div>
      </section>

      {response && candidates.length === 0 && (
        <EmptyState
          title="No tasks found"
          description="The AI couldn't pull any actionable tasks from the text. Try a more specific paste."
        />
      )}

      {candidates.length > 0 && (
        <>
          <div className="mb-3 flex items-center justify-between text-sm text-[var(--color-text-secondary)]">
            <span>
              {selected.length} of {candidates.length} task
              {candidates.length === 1 ? '' : 's'} selected
            </span>
          </div>

          <ul className="space-y-2">
            {candidates.map((c, idx) => (
              <CandidateRow
                key={`${c.title}-${idx}`}
                candidate={c}
                isExcluded={excluded.has(idx)}
                onToggle={() => toggleCandidate(idx)}
              />
            ))}
          </ul>

          <div className="mt-6 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setResponse(null)}>
              Discard
            </Button>
            <Button
              onClick={handleCreate}
              disabled={selected.length === 0 || creating}
            >
              {creating ? (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              ) : null}
              Create {selected.length} task{selected.length === 1 ? '' : 's'}
            </Button>
          </div>

          {projects.filter((p) => p.status !== 'archived').length === 0 && (
            <p className="mt-3 flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/5 p-3 text-sm text-amber-600 dark:text-amber-400">
              <TriangleAlert
                className="mt-0.5 h-4 w-4 shrink-0"
                aria-hidden="true"
              />
              You need at least one active project before tasks can be created.
              Create or reopen a project from the Projects screen first.
            </p>
          )}
        </>
      )}

      <ProUpgradeModal
        isOpen={showUpgrade}
        onClose={() => setShowUpgrade(false)}
        featureName="Conversation Extraction"
        featureDescription="Paste a meeting or chat and let AI pull out the action items."
      />
    </div>
  );
}

function CandidateRow({
  candidate,
  isExcluded,
  onToggle,
}: {
  candidate: ExtractedTaskCandidate;
  isExcluded: boolean;
  onToggle: () => void;
}) {
  const webPri = androidToWebPriority(candidate.suggested_priority);
  return (
    <li
      className={`flex items-start gap-3 rounded-xl border p-4 transition ${
        isExcluded
          ? 'border-dashed border-[var(--color-border)] bg-transparent opacity-60'
          : 'border-[var(--color-border)] bg-[var(--color-bg-card)]'
      }`}
    >
      <div className="mt-0.5">
        <Sparkles
          className="h-4 w-4 text-[var(--color-accent)]"
          aria-hidden="true"
        />
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-[var(--color-text-primary)]">
          {candidate.title}
        </p>
        <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-[var(--color-text-secondary)]">
          {candidate.suggested_due_date && (
            <span className="inline-flex items-center gap-1 rounded-md bg-[var(--color-bg-secondary)] px-1.5 py-0.5">
              <Calendar className="h-3 w-3" aria-hidden="true" />
              {candidate.suggested_due_date}
            </span>
          )}
          {candidate.suggested_priority > 0 && (
            <span className="inline-flex items-center gap-1 rounded-md bg-[var(--color-bg-secondary)] px-1.5 py-0.5">
              <Flag className="h-3 w-3" aria-hidden="true" />
              {PRIORITY_LABELS[webPri] ?? 'None'}
            </span>
          )}
          {candidate.suggested_project && (
            <span className="inline-flex items-center gap-1 rounded-md bg-[var(--color-bg-secondary)] px-1.5 py-0.5">
              <FolderInput className="h-3 w-3" aria-hidden="true" />
              {candidate.suggested_project}
            </span>
          )}
          <span className="rounded-md bg-[var(--color-bg-secondary)] px-1.5 py-0.5">
            {Math.round(candidate.confidence * 100)}% confident
          </span>
        </div>
      </div>
      <label className="flex shrink-0 cursor-pointer items-center gap-2 text-sm text-[var(--color-text-secondary)]">
        <input
          type="checkbox"
          checked={!isExcluded}
          onChange={onToggle}
          className="h-4 w-4 cursor-pointer rounded border-[var(--color-border)] text-[var(--color-accent)]"
          aria-label={isExcluded ? 'Include this task' : 'Exclude this task'}
        />
        <span>{isExcluded ? 'Skip' : 'Create'}</span>
        {!isExcluded && (
          <Check
            className="h-3.5 w-3.5 text-[var(--color-accent)]"
            aria-hidden="true"
          />
        )}
      </label>
    </li>
  );
}

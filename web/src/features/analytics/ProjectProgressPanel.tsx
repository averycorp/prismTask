import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { format, parseISO } from 'date-fns';
import { FolderKanban, Loader2, TrendingUp } from 'lucide-react';
import { toast } from 'sonner';
import * as firestoreTasks from '@/api/firestore/tasks';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { useProjectStore } from '@/stores/projectStore';
import { computeProjectBurndown } from '@/utils/projectBurndown';
import { pickerProjects as filterPickerProjects } from '@/utils/projectFilters';
import type { ProjectProgressResponse } from '@/types/analytics';
import type { Task } from '@/types/task';

interface ProjectProgressPanelProps {
  startIso: string;
  endIso: string;
}

/**
 * Wires up project-progress / burndown without hitting the backend
 * `/analytics/project-progress` endpoint — that route takes an int
 * Postgres `project_id`, but web projects live in Firestore with
 * string IDs. We compute the same data locally (see
 * `utils/projectBurndown.ts`), which mirrors
 * `backend/app/services/analytics.py::compute_project_burndown`.
 */
export function ProjectProgressPanel({ startIso, endIso }: ProjectProgressPanelProps) {
  const projects = useProjectStore((s) => s.projects);
  const fetchAllProjects = useProjectStore((s) => s.fetchAllProjects);

  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(
    null,
  );
  const [progress, setProgress] = useState<ProjectProgressResponse | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    fetchAllProjects();
  }, [fetchAllProjects]);

  // Hide archived projects from the burndown picker but keep the
  // currently-selected one visible (mirrors Android `pickerProjects`).
  const visibleProjects = useMemo(
    () => filterPickerProjects(projects, selectedProjectId),
    [projects, selectedProjectId],
  );

  // Default to the first available non-archived project once the list loads.
  useEffect(() => {
    if (!selectedProjectId && visibleProjects.length > 0) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- form-init: pick first project as default once async list arrives
      setSelectedProjectId(visibleProjects[0].id);
    }
  }, [visibleProjects, selectedProjectId]);

  const selectedProject = useMemo(
    () => projects.find((p) => p.id === selectedProjectId) ?? null,
    [projects, selectedProjectId],
  );

  const load = useCallback(async () => {
    if (!selectedProject) return;
    setLoading(true);
    try {
      const uid = getFirebaseUid();
      // The compute needs tasks scoped to the project. Firestore lets
      // us query by projectId directly, so we don't pull the whole
      // user's task list here.
      const tasks: Task[] = await firestoreTasks.getTasksByProject(
        uid,
        selectedProject.id,
      );
      const result = computeProjectBurndown({
        project: selectedProject,
        tasks,
        startIso,
        endIso,
      });
      setProgress(result);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to compute burndown');
      setProgress(null);
    } finally {
      setLoading(false);
    }
  }, [selectedProject, startIso, endIso]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load burndown on mount and when scope changes
    load();
  }, [load]);

  const chartData = useMemo(
    () =>
      progress?.burndown.map((b) => ({
        date: format(parseISO(b.date), 'MMM d'),
        remaining: b.remaining,
        completed: b.completed_cumulative,
        added: b.added,
      })) ?? [],
    [progress],
  );

  if (visibleProjects.length === 0) {
    return (
      <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4 text-sm text-[var(--color-text-secondary)]">
        No active projects yet — create or reopen one to see burndown data.
      </section>
    );
  }

  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="flex items-center gap-2 text-sm font-semibold text-[var(--color-text-primary)]">
            <FolderKanban className="h-4 w-4" aria-hidden="true" />
            Project progress
          </h2>
          {progress && (
            <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
              {progress.completed_tasks}/{progress.total_tasks} complete ·
              &nbsp;{progress.velocity} tasks/day
              {progress.projected_completion && (
                <>
                  {' · ETA '}
                  {format(parseISO(progress.projected_completion), 'MMM d')}
                </>
              )}
              {progress.total_tasks > 0 && (
                <>
                  {' · '}
                  <span
                    className={
                      progress.is_on_track ? 'text-emerald-500' : 'text-red-500'
                    }
                  >
                    {progress.is_on_track ? 'On track' : 'At risk'}
                  </span>
                </>
              )}
            </p>
          )}
        </div>
        <select
          value={selectedProjectId ?? ''}
          onChange={(e) => setSelectedProjectId(e.target.value || null)}
          aria-label="Project"
          className="rounded-md border border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-2 py-1 text-xs text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
        >
          {visibleProjects.map((p) => (
            <option key={p.id} value={p.id}>
              {p.title}
            </option>
          ))}
        </select>
      </div>

      {loading && (
        <div className="flex items-center gap-3 py-6 text-sm text-[var(--color-text-primary)]">
          <Loader2 className="h-4 w-4 animate-spin text-[var(--color-accent)]" />
          Building burndown…
        </div>
      )}

      {!loading && progress && progress.total_tasks === 0 && (
        <p className="py-6 text-center text-sm italic text-[var(--color-text-secondary)]">
          No tasks in this project yet.
        </p>
      )}

      {!loading && progress && progress.total_tasks > 0 && (
        <div style={{ width: '100%', height: 260 }}>
          <ResponsiveContainer>
            <ComposedChart data={chartData}>
              <CartesianGrid
                strokeDasharray="3 3"
                stroke="var(--color-border)"
              />
              <XAxis
                dataKey="date"
                stroke="var(--color-text-secondary)"
                fontSize={11}
              />
              <YAxis
                stroke="var(--color-text-secondary)"
                fontSize={11}
                allowDecimals={false}
              />
              <Tooltip
                contentStyle={{
                  background: 'var(--color-bg-card)',
                  border: '1px solid var(--color-border)',
                  borderRadius: 8,
                  color: 'var(--color-text-primary)',
                }}
              />
              <Legend />
              <Bar dataKey="added" fill="var(--color-text-secondary)" name="Added" />
              <Line
                type="monotone"
                dataKey="remaining"
                stroke="var(--prism-destructive, var(--color-accent))"
                strokeWidth={2}
                dot={false}
                name="Remaining"
              />
              <Line
                type="monotone"
                dataKey="completed"
                stroke="var(--prism-success, var(--color-accent))"
                strokeWidth={2}
                dot={false}
                name="Completed (cumulative)"
              />
            </ComposedChart>
          </ResponsiveContainer>
        </div>
      )}

      {!loading && progress && !progress.is_on_track && progress.total_tasks > 0 && (
        <p className="mt-3 flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/5 p-3 text-xs text-amber-600 dark:text-amber-400">
          <TrendingUp className="mt-0.5 h-3.5 w-3.5" aria-hidden="true" />
          Projected completion is after the project's due date. Velocity is{' '}
          {progress.velocity} tasks/day — bump it or reschedule the deadline.
        </p>
      )}
    </section>
  );
}

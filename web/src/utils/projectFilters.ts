/**
 * Shared archived-project filtering helpers.
 *
 * Mirrors the Android editor convention in
 * `ui/screens/addedittask/tabs/OrganizeTab.kt`:
 *
 *     val pickerProjects = remember(projects, viewModel.projectId) {
 *         projects.filter { it.status != "ARCHIVED" || it.id == viewModel.projectId }
 *     }
 *
 * i.e. hide archived projects from pickers *unless* the row being edited
 * already points at one (don't blank out an existing assignment just because
 * the project got archived later).
 */
import type { Project } from '@/types/project';

/** Drop archived projects entirely — for "default to the first project" flows. */
export function nonArchivedProjects<T extends Pick<Project, 'status'>>(
  projects: readonly T[],
): T[] {
  return projects.filter((p) => p.status !== 'archived');
}

/**
 * Drop archived projects except the one currently assigned to the row being
 * edited. `currentId` may be `null`/`undefined`/`''` when there's no
 * assignment, in which case this collapses to {@link nonArchivedProjects}.
 */
export function pickerProjects<T extends Pick<Project, 'id' | 'status'>>(
  projects: readonly T[],
  currentId: string | null | undefined,
): T[] {
  return projects.filter(
    (p) => p.status !== 'archived' || (!!currentId && p.id === currentId),
  );
}

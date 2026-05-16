import { create } from 'zustand';
import type { Unsubscribe } from 'firebase/firestore';
import {
  subscribeToProjectTemplates,
  type ProjectTemplate,
} from '@/api/firestore/projectTemplates';

/**
 * Live cache of the current user's project templates (Android's
 * `ProjectTemplateEntity` — a scaffold for spawning a project-with-tasks
 * bundle from a fixed blueprint, *orthogonal* to the v1.4.0 Projects
 * feature). Read-only on web in this unit (Sync Sweep B, 2 of 23).
 *
 * Routes through a separate Firestore collection (`project_templates`)
 * from the existing `task_templates` listener, so the doc-id namespaces
 * don't collide with `useTemplateStore`.
 *
 * Selector usage note: read individual fields off the store
 * (`s => s.templates`) — fresh-object selectors trigger React #185.
 */
interface ProjectTemplatesState {
  templates: ProjectTemplate[];

  /** Wire Firestore real-time listener. Returns a cleanup function. */
  subscribeToTemplates: (uid: string) => Unsubscribe;

  /** Reset to defaults (used on sign-out). */
  reset: () => void;
}

export const useProjectTemplatesStore = create<ProjectTemplatesState>(
  (set) => ({
    templates: [],

    subscribeToTemplates: (uid) => {
      return subscribeToProjectTemplates(uid, (templates) => {
        set({ templates });
      });
    },

    reset: () => set({ templates: [] }),
  }),
);

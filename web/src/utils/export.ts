import { goalsApi } from '@/api/goals';
import { projectsApi } from '@/api/projects';
import { tasksApi } from '@/api/tasks';
import { habitsApi } from '@/api/habits';
import { tagsApi } from '@/api/tags';
import { getTaskTemplates } from '@/api/firestore/taskTemplates';
import { getFirebaseUid } from '@/stores/firebaseUid';
import type { Goal } from '@/types/goal';
import type { Project } from '@/types/project';
import type { Task } from '@/types/task';
import type { Tag } from '@/types/tag';

export interface PrismTaskExport {
  version: 1;
  exported_at: string;
  goals: Goal[];
  projects: Project[];
  tasks: Task[];
  tags: Tag[];
  habits: unknown[];
  habit_completions: unknown[];
  templates: unknown[];
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

function today(): string {
  return new Date().toISOString().split('T')[0];
}

export async function exportJson(
  onProgress?: (step: string) => void,
): Promise<void> {
  onProgress?.('Fetching goals...');
  const goals = await goalsApi.list();

  onProgress?.('Fetching projects...');
  const projects: Project[] = [];
  for (const goal of goals) {
    const goalProjects = await projectsApi.getByGoal(goal.id);
    projects.push(...goalProjects);
  }

  onProgress?.('Fetching tasks...');
  const tasks: Task[] = [];
  for (const project of projects) {
    const projectTasks = await tasksApi.getByProject(project.id);
    tasks.push(...projectTasks);
  }

  onProgress?.('Fetching tags...');
  const tags = await tagsApi.list();

  onProgress?.('Fetching habits...');
  let habits: unknown[] = [];
  try {
    habits = await habitsApi.list();
  } catch {
    // habits may not exist
  }

  onProgress?.('Fetching templates...');
  let templates: unknown[] = [];
  try {
    // Parity audit § B.10: templates live in Firestore now
    // (`users/{uid}/task_templates`), no longer behind the REST
    // `/templates` endpoint. The backup file format is unchanged —
    // still a raw array of template shapes — so previously exported
    // JSON files keep round-tripping through import.
    const uid = getFirebaseUid();
    templates = await getTaskTemplates(uid);
  } catch {
    // templates may not exist (unauthenticated, or zero templates)
  }

  const exportData: PrismTaskExport = {
    version: 1,
    exported_at: new Date().toISOString(),
    goals,
    projects,
    tasks,
    tags,
    habits,
    habit_completions: [],
    templates,
  };

  onProgress?.('Downloading...');
  const json = JSON.stringify(exportData, null, 2);
  const blob = new Blob([json], { type: 'application/json' });
  downloadBlob(blob, `prismtask-export-${today()}.json`);
}

function escapeCsvField(value: string): string {
  if (value.includes(',') || value.includes('"') || value.includes('\n')) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
}

export async function exportCsv(
  onProgress?: (step: string) => void,
): Promise<void> {
  onProgress?.('Fetching data...');
  const goals = await goalsApi.list();
  const projects: Project[] = [];
  const projectNames: Record<string, string> = {};
  for (const goal of goals) {
    const goalProjects = await projectsApi.getByGoal(goal.id);
    for (const p of goalProjects) {
      projects.push(p);
      projectNames[p.id] = p.title;
    }
  }

  const tasks: Task[] = [];
  for (const project of projects) {
    const projectTasks = await tasksApi.getByProject(project.id);
    tasks.push(...projectTasks);
  }

  const headers = [
    'id',
    'title',
    'description',
    'status',
    'priority',
    'due_date',
    'completed_at',
    'project_name',
    'tags',
    'parent_task_id',
    'recurrence_type',
    'created_at',
  ];

  const rows = tasks.map((t) => [
    t.id.toString(),
    escapeCsvField(t.title),
    escapeCsvField(t.description || ''),
    t.status,
    t.priority.toString(),
    t.due_date || '',
    t.completed_at || '',
    escapeCsvField(projectNames[t.project_id] || ''),
    escapeCsvField((t.tags || []).map((tag) => tag.name).join(', ')),
    t.parent_id?.toString() || '',
    (() => {
      if (!t.recurrence_json) return '';
      try {
        return JSON.parse(t.recurrence_json).type || '';
      } catch {
        return '';
      }
    })(),
    t.created_at,
  ]);

  onProgress?.('Downloading...');
  const csv = [headers.join(','), ...rows.map((r) => r.join(','))].join('\n');
  const blob = new Blob([csv], { type: 'text/csv' });
  downloadBlob(blob, `prismtask-tasks-${today()}.csv`);
}

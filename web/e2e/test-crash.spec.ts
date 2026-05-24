import { test, expect } from '@playwright/test';

test.describe('Task Editor Schedule Tab Crash Tests', () => {
  let logs: string[] = [];
  let pageErrors: string[] = [];

  test.beforeEach(async ({ page }) => {
    logs = [];
    pageErrors = [];
    page.on('console', msg => logs.push(`[${msg.type()}] ${msg.text()}`));
    page.on('pageerror', error => pageErrors.push(error.message));

    await page.goto('http://localhost:5173');
    await page.waitForURL('**/login');
    
    // Wait for the initial Firebase Auth check to finish (isLoading === false)
    await page.waitForFunction(() => {
      const authStore = (window as any).useAuthStore;
      return authStore && authStore.getState && !authStore.getState().isLoading;
    }, { timeout: 10000 });

    await page.evaluate(() => {
      const authStore = (window as any).useAuthStore;
      if (authStore && authStore.setState) {
        authStore.setState({
          firebaseUser: { uid: 'user123', email: 'test@example.com', displayName: 'Test User' },
          firebaseUid: 'user123',
          isAuthenticated: true,
          isLoading: false,
          deletionStatus: 'active',
          refreshDeletionStatus: async () => {},
          fetchUser: async () => {},
        });
      }
      const onboardingStore = (window as any).useOnboardingStore;
      if (onboardingStore && onboardingStore.setState) {
        onboardingStore.setState({
          status: 'completed',
          hydrate: async () => {},
        });
      }
    });
  });

  test.afterEach(async () => {
    if (pageErrors.length > 0) {
      console.error("FATAL PAGE ERRORS DETECTED:", pageErrors.join('\n'));
    }
  });

  test('navigate to schedule tab on new task', async ({ page }) => {
    await page.evaluate(() => {
      const taskStore = (window as any).useTaskStore;
      if (taskStore && taskStore.setState) {
        taskStore.setState({
          tasks: [],
          todayTasks: [],
          overdueTasks: [],
          upcomingTasks: [],
          fetchTasks: async () => {},
          fetchToday: async () => {},
          fetchOverdue: async () => {},
          fetchUpcoming: async () => {},
        });
      }
    });

    await page.evaluate(() => {
      (window as any).router.navigate('/tasks');
    });
    await page.waitForURL('**/tasks', { timeout: 5000 });
    await page.waitForTimeout(1000);
    
    await page.locator('button:has-text("New Task")').first().click();
    await page.waitForTimeout(500);
    
    const scheduleTab = page.locator('button[role="tab"]', { hasText: 'Schedule' });
    await scheduleTab.click();
    await page.waitForTimeout(1000);

    // Verify there are no unhandled JavaScript runtime crashes
    expect(pageErrors.length).toBe(0);
  });

  test('navigate to schedule tab on existing task with no recurrence', async ({ page }) => {
    await page.evaluate(() => {
      const taskStore = (window as any).useTaskStore;
      if (taskStore && taskStore.setState) {
        const mockTask = {
          id: 'task-no-rec',
          title: 'Task without recurrence',
          status: 'todo',
          priority: 3,
          created_at: new Date().toISOString(),
          updated_at: new Date().toISOString(),
          project_id: 'proj1',
          due_date: '',
          due_time: '',
          recurrence_json: null,
        };
        taskStore.setState({
          tasks: [mockTask],
          fetchTask: async () => {
            taskStore.setState({ selectedTask: mockTask });
            return mockTask as any;
          },
          fetchToday: async () => {},
          fetchOverdue: async () => {},
          fetchUpcoming: async () => {},
        });
      }
    });

    await page.evaluate(() => {
      (window as any).router.navigate('/tasks/task-no-rec');
    });
    await page.waitForURL('**/tasks/task-no-rec', { timeout: 5000 });
    await page.waitForTimeout(1000);

    const scheduleTab = page.locator('button[role="tab"]', { hasText: 'Schedule' });
    await scheduleTab.click();
    await page.waitForTimeout(1000);

    // Verify there are no unhandled JavaScript runtime crashes
    expect(pageErrors.length).toBe(0);
  });

  test('navigate to schedule tab on existing task with malformed recurrence', async ({ page }) => {
    await page.evaluate(() => {
      const taskStore = (window as any).useTaskStore;
      if (taskStore && taskStore.setState) {
        const mockTask = {
          id: 'task-malformed-rec',
          title: 'Task with malformed recurrence',
          status: 'todo',
          priority: 3,
          created_at: new Date().toISOString(),
          updated_at: new Date().toISOString(),
          project_id: 'proj1',
          due_date: '',
          due_time: '',
          recurrence_json: JSON.stringify({
            type: 'weekly',
            interval: 1,
            days_of_week: null // This used to trigger unhandled TypeError spreads!
          }),
        };
        taskStore.setState({
          tasks: [mockTask],
          fetchTask: async () => {
            taskStore.setState({ selectedTask: mockTask });
            return mockTask as any;
          },
          fetchToday: async () => {},
          fetchOverdue: async () => {},
          fetchUpcoming: async () => {},
        });
      }
    });

    await page.evaluate(() => {
      (window as any).router.navigate('/tasks/task-malformed-rec');
    });
    await page.waitForURL('**/tasks/task-malformed-rec', { timeout: 5000 });
    await page.waitForTimeout(1000);

    const scheduleTab = page.locator('button[role="tab"]', { hasText: 'Schedule' });
    await scheduleTab.click();
    await page.waitForTimeout(1000);

    // Verify there are no unhandled JavaScript runtime crashes
    expect(pageErrors.length).toBe(0);
  });
});

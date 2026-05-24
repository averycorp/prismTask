import { test, expect } from '@playwright/test';

test('navigate to schedule tab', async ({ page }) => {
  const logs: string[] = [];
  page.on('console', msg => logs.push(`[${msg.type()}] ${msg.text()}`));
  page.on('pageerror', error => logs.push(`[pageerror] ${error.message}`));

  await page.goto('http://localhost:5173');
  await page.waitForURL('**/login');
  
  // Wait for the initial Firebase Auth check to finish (isLoading === false)
  // so that the background auth listener does not overwrite our mock state later.
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

  // Client-side navigate so Zustand stores keep their in-memory state!
  await page.evaluate(() => {
    (window as any).router.navigate('/tasks');
  });

  // Wait for the URL transition to finish
  await page.waitForURL('**/tasks', { timeout: 5000 });
  
  // Wait for tasks to load
  await page.waitForTimeout(2000);
  
  // Click "New Task" button to open the editor
  await page.locator('button:has-text("New Task")').first().click();
  await page.waitForTimeout(1000);
  
  // Click schedule tab
  const scheduleTab = page.locator('button[role="tab"]', { hasText: 'Schedule' });
  await scheduleTab.click();
  
  await page.waitForTimeout(1000);
  
  console.log("PLAYWRIGHT LOGS:");
  console.log(logs.join('\n'));
});

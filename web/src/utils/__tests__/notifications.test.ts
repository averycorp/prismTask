import { describe, it, expect, vi, beforeEach, afterEach, Mock } from 'vitest';
import {
  isNotificationSupported,
  requestNotificationPermission,
  getNotificationPermission,
  showNotification,
  scheduleReminder,
  cancelReminder,
  cancelAllReminders
} from '../notifications';

describe('notifications utils', () => {
  let originalNotification: any;

  beforeEach(() => {
    vi.useFakeTimers();
    originalNotification = global.Notification;

    // Mock the Notification object
    global.Notification = {
      permission: 'default',
      requestPermission: vi.fn().mockResolvedValue('granted'),
    } as any;

    // Add mock constructor for the Notification object
    const NotificationConstructor = vi.fn() as any;
    NotificationConstructor.permission = 'default';
    NotificationConstructor.requestPermission = vi.fn().mockResolvedValue('granted');
    global.Notification = NotificationConstructor as any;
  });

  afterEach(() => {
    vi.useRealTimers();
    global.Notification = originalNotification;
    cancelAllReminders();
    vi.restoreAllMocks();
  });

  describe('isNotificationSupported', () => {
    it('returns true when Notification is in window', () => {
      // In jsdom environment, Notification should be present if we mock it, but we can verify our mock logic
      expect(isNotificationSupported()).toBe(true);
    });

    it('returns false when Notification is not in window', () => {
      const originalWindow = global.window;
      // Temporarily remove Notification
      delete (global.window as any).Notification;

      expect(isNotificationSupported()).toBe(false);

      global.window = originalWindow; // Restore
    });
  });

  describe('getNotificationPermission', () => {
    it('returns denied if not supported', () => {
      const originalWindow = global.window;
      delete (global.window as any).Notification;
      expect(getNotificationPermission()).toBe('denied');
      global.window = originalWindow;
    });

    it('returns the current permission', () => {
      (global.Notification as any).permission = 'granted';
      expect(getNotificationPermission()).toBe('granted');
    });
  });

  describe('requestNotificationPermission', () => {
    it('returns denied if not supported', async () => {
      const originalWindow = global.window;
      delete (global.window as any).Notification;
      expect(await requestNotificationPermission()).toBe('denied');
      global.window = originalWindow;
    });

    it('returns immediately if already granted or denied', async () => {
      (global.Notification as any).permission = 'granted';
      expect(await requestNotificationPermission()).toBe('granted');
      expect((global.Notification as any).requestPermission).not.toHaveBeenCalled();

      (global.Notification as any).permission = 'denied';
      expect(await requestNotificationPermission()).toBe('denied');
      expect((global.Notification as any).requestPermission).not.toHaveBeenCalled();
    });

    it('requests permission if default', async () => {
      (global.Notification as any).permission = 'default';
      expect(await requestNotificationPermission()).toBe('granted');
      expect((global.Notification as any).requestPermission).toHaveBeenCalled();
    });
  });

  describe('showNotification', () => {
    it('returns null if not supported', () => {
      const originalWindow = global.window;
      delete (global.window as any).Notification;
      expect(showNotification('Test')).toBeNull();
      global.window = originalWindow;
    });

    it('returns null if permission is not granted', () => {
      (global.Notification as any).permission = 'denied';
      expect(showNotification('Test')).toBeNull();
    });

    it('creates a new Notification if permission is granted', () => {
      (global.Notification as any).permission = 'granted';

      const result = showNotification('Test Title', { body: 'Test Body' });

      expect(global.Notification).toHaveBeenCalledWith('Test Title', {
        icon: '/favicon.svg',
        badge: '/favicon.svg',
        body: 'Test Body',
      });
      // the constructor returns a mock object based on our vi.fn()
    });
  });

  describe('scheduleReminder', () => {
    it('schedules a notification', () => {
      (global.Notification as any).permission = 'granted';

      const futureDate = new Date(Date.now() + 10000).toISOString();
      scheduleReminder('task1', 'Task Title', futureDate, 0);

      expect(global.Notification).not.toHaveBeenCalled();

      vi.advanceTimersByTime(10000);

      expect(global.Notification).toHaveBeenCalledWith('Task Reminder: Task Title', expect.objectContaining({
        tag: 'task-reminder-task1',
        requireInteraction: true
      }));
    });

    it('does not schedule if delay is <= 0', () => {
      (global.Notification as any).permission = 'granted';

      const pastDate = new Date(Date.now() - 1000).toISOString();
      scheduleReminder('task2', 'Past Task', pastDate, 0);

      vi.advanceTimersByTime(1000);
      expect(global.Notification).not.toHaveBeenCalled();
    });

    it('cancels existing reminder for the same task before scheduling', () => {
      (global.Notification as any).permission = 'granted';

      const futureDate = new Date(Date.now() + 10000).toISOString();

      scheduleReminder('task1', 'Initial', futureDate, 0);
      scheduleReminder('task1', 'Updated', futureDate, 0); // Should cancel 'Initial'

      vi.advanceTimersByTime(10000);

      expect(global.Notification).toHaveBeenCalledTimes(1);
      expect(global.Notification).toHaveBeenCalledWith('Task Reminder: Updated', expect.any(Object));
    });
  });

  describe('cancelReminder', () => {
    it('cancels a scheduled reminder', () => {
      (global.Notification as any).permission = 'granted';

      const futureDate = new Date(Date.now() + 10000).toISOString();
      scheduleReminder('task1', 'Task Title', futureDate, 0);

      cancelReminder('task1');

      vi.advanceTimersByTime(10000);
      expect(global.Notification).not.toHaveBeenCalled();
    });
  });

  describe('cancelAllReminders', () => {
    it('cancels all scheduled reminders', () => {
      (global.Notification as any).permission = 'granted';

      const futureDate = new Date(Date.now() + 10000).toISOString();
      scheduleReminder('task1', 'Task 1', futureDate, 0);
      scheduleReminder('task2', 'Task 2', futureDate, 0);

      cancelAllReminders();

      vi.advanceTimersByTime(10000);
      expect(global.Notification).not.toHaveBeenCalled();
    });
  });
});

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  isNotificationSupported,
  requestNotificationPermission,
  getNotificationPermission,
  showNotification,
  scheduleReminder,
  cancelReminder,
  cancelAllReminders,
} from '../notifications';

// Mock Notification API
interface MockNotificationCtor {
  (): void;
  requestPermission: ReturnType<typeof vi.fn>;
  permission: NotificationPermission;
  mockClear: () => void;
}
const MockNotification = vi.fn() as unknown as MockNotificationCtor;
MockNotification.requestPermission = vi.fn().mockResolvedValue('granted');
Object.defineProperty(MockNotification, 'permission', {
  value: 'default',
  writable: true,
});

describe('notifications utility', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal('Notification', MockNotification);
    MockNotification.mockClear();
    MockNotification.requestPermission.mockClear();
    MockNotification.permission = 'default';
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  describe('isNotificationSupported', () => {
    it('should return true if Notification is in window', () => {
      expect(isNotificationSupported()).toBe(true);
    });

    it('should return false if Notification is not in window', () => {
      // @ts-expect-error Mocking for test
      delete global.window.Notification;
      expect(isNotificationSupported()).toBe(false);
      // @ts-expect-error Mocking for test
      global.window.Notification = MockNotification;
    });
  });

  describe('requestNotificationPermission', () => {
    it('should return denied if not supported', async () => {
      // @ts-expect-error Mocking for test
      delete global.window.Notification;
      const result = await requestNotificationPermission();
      expect(result).toBe('denied');
      // @ts-expect-error Mocking for test
      global.window.Notification = MockNotification;
    });

    it('should return granted if already granted', async () => {
      MockNotification.permission = 'granted';
      const result = await requestNotificationPermission();
      expect(result).toBe('granted');
    });

    it('should return denied if already denied', async () => {
      MockNotification.permission = 'denied';
      const result = await requestNotificationPermission();
      expect(result).toBe('denied');
    });

    it('should call requestPermission if default', async () => {
      MockNotification.permission = 'default';
      const result = await requestNotificationPermission();
      expect(MockNotification.requestPermission).toHaveBeenCalled();
      expect(result).toBe('granted');
    });
  });

  describe('getNotificationPermission', () => {
    it('should return denied if not supported', () => {
      // @ts-expect-error Mocking for test
      delete global.window.Notification;
      expect(getNotificationPermission()).toBe('denied');
      // @ts-expect-error Mocking for test
      global.window.Notification = MockNotification;
    });

    it('should return current permission', () => {
      MockNotification.permission = 'granted';
      expect(getNotificationPermission()).toBe('granted');
    });
  });

  describe('showNotification', () => {
    it('should return null if not supported', () => {
      // @ts-expect-error Mocking for test
      delete global.window.Notification;
      expect(showNotification('Test')).toBeNull();
      // @ts-expect-error Mocking for test
      global.window.Notification = MockNotification;
    });

    it('should return null if permission not granted', () => {
      MockNotification.permission = 'denied';
      expect(showNotification('Test')).toBeNull();
    });

    it('should create Notification if granted', () => {
      MockNotification.permission = 'granted';
      const notif = showNotification('Test Title', { body: 'Test Body' });
      expect(MockNotification).toHaveBeenCalledWith('Test Title', {
        icon: '/favicon.svg',
        badge: '/favicon.svg',
        body: 'Test Body',
      });
      expect(notif).toBeInstanceOf(MockNotification);
    });
  });

  describe('scheduling reminders', () => {
    it('should schedule and show a notification', () => {
      MockNotification.permission = 'granted';
      const now = Date.now();
      vi.setSystemTime(now);

      const dueDate = new Date(now + 10000).toISOString(); // 10s from now
      scheduleReminder('task1', 'My Task', dueDate, 2000); // offset 2s, should fire in 8s

      vi.advanceTimersByTime(7999);
      expect(MockNotification).not.toHaveBeenCalled();

      vi.advanceTimersByTime(1);
      expect(MockNotification).toHaveBeenCalledWith('Task Reminder: My Task', expect.any(Object));
    });

    it('should not schedule if delay <= 0', () => {
      MockNotification.permission = 'granted';
      const now = Date.now();
      vi.setSystemTime(now);

      const dueDate = new Date(now + 1000).toISOString(); // 1s from now
      scheduleReminder('task2', 'My Task 2', dueDate, 2000); // offset 2s, delay is -1s

      vi.advanceTimersByTime(5000);
      expect(MockNotification).not.toHaveBeenCalled();
    });

    it('should cancel a reminder', () => {
      MockNotification.permission = 'granted';
      const now = Date.now();
      vi.setSystemTime(now);

      const dueDate = new Date(now + 10000).toISOString();
      scheduleReminder('task3', 'My Task 3', dueDate, 0);

      cancelReminder('task3');
      vi.advanceTimersByTime(10000);

      expect(MockNotification).not.toHaveBeenCalled();
    });

    it('should cancel all reminders', () => {
      MockNotification.permission = 'granted';
      const now = Date.now();
      vi.setSystemTime(now);

      const dueDate1 = new Date(now + 10000).toISOString();
      const dueDate2 = new Date(now + 15000).toISOString();
      scheduleReminder('task4', 'Task 4', dueDate1, 0);
      scheduleReminder('task5', 'Task 5', dueDate2, 0);

      cancelAllReminders();
      vi.advanceTimersByTime(20000);

      expect(MockNotification).not.toHaveBeenCalled();
    });
  });
});

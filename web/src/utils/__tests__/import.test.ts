import { describe, it, expect, vi, beforeEach, afterEach, Mock } from 'vitest';
import { validateImportFile, parseImportFile, importData } from '../import';
import { exportApi } from '@/api/export';

vi.mock('@/api/export', () => ({
  exportApi: {
    importJson: vi.fn(),
  },
}));

describe('import utils', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('validateImportFile', () => {
    it('returns null for non-objects or null', () => {
      expect(validateImportFile(null)).toBeNull();
      expect(validateImportFile(undefined)).toBeNull();
      expect(validateImportFile('string')).toBeNull();
      expect(validateImportFile(123)).toBeNull();
    });

    it('returns null if version is missing or invalid', () => {
      expect(validateImportFile({})).toBeNull();
      expect(validateImportFile({ version: '1.0' })).toBeNull();
      expect(validateImportFile({ tasks: [] })).toBeNull();
    });

    it('validates a correct empty import structure', () => {
      const data = { version: 1 };
      const preview = validateImportFile(data);
      expect(preview).toEqual({
        version: 1,
        goalCount: 0,
        projectCount: 0,
        taskCount: 0,
        tagCount: 0,
        habitCount: 0,
        templateCount: 0,
        tasks: [],
      });
    });

    it('counts entities correctly', () => {
      const data = {
        version: 2,
        goals: [{}, {}],
        projects: [{}],
        tasks: [{}],
        tags: [{}, {}, {}],
        habits: [],
        // omitting templates to test fallback
      };
      const preview = validateImportFile(data);
      expect(preview).toEqual(expect.objectContaining({
        version: 2,
        goalCount: 2,
        projectCount: 1,
        taskCount: 1,
        tagCount: 3,
        habitCount: 0,
        templateCount: 0,
      }));
    });

    it('builds task previews correctly from flat array using parent_id', () => {
      const data = {
        version: 1,
        tasks: [
          { id: 1, title: 'Parent 1' },
          { id: 2, parent_id: 1, title: 'Child 1', due_date: '2023-10-01', priority: 1, tags: ['tag1'] },
          { id: 3, parent_id: 1, title: 'Child 2' },
          { id: 4, title: 'Parent 2' },
        ],
      };

      const preview = validateImportFile(data);
      expect(preview?.tasks.length).toBe(2);

      const p1 = preview?.tasks[0];
      expect(p1?.title).toBe('Parent 1');
      expect(p1?.subtasks.length).toBe(2);

      const c1 = p1?.subtasks[0];
      expect(c1?.title).toBe('Child 1');
      expect(c1?.dueDate).toBe('2023-10-01');
      expect(c1?.priority).toBe(1);
      expect(c1?.tags).toEqual(['tag1']);

      expect(preview?.tasks[1].title).toBe('Parent 2');
      expect(preview?.tasks[1].subtasks.length).toBe(0);
    });

    it('builds task previews correctly from nested subtasks array', () => {
      const data = {
        version: 1,
        tasks: [
          {
            title: 'Parent 1',
            subtasks: [
              { title: 'Child 1', tags: ['nested'] }
            ]
          }
        ]
      };

      const preview = validateImportFile(data);
      expect(preview?.tasks.length).toBe(1);
      expect(preview?.tasks[0].title).toBe('Parent 1');
      expect(preview?.tasks[0].subtasks.length).toBe(1);
      expect(preview?.tasks[0].subtasks[0].title).toBe('Child 1');
      expect(preview?.tasks[0].subtasks[0].tags).toEqual(['nested']);
    });

    it('enforces IMPORT_PREVIEW_TASK_LIMIT', () => {
      const tasks = Array.from({ length: 15 }, (_, i) => ({ id: i, title: `Task ${i}` }));
      const data = { version: 1, tasks };

      const preview = validateImportFile(data);
      expect(preview?.taskCount).toBe(15);
      expect(preview?.tasks.length).toBe(10); // LIMIT is 10
    });

    it('handles malformed tasks resiliently', () => {
      const data = {
        version: 1,
        tasks: [
          null, // ignored
          "string task", // ignored
          { title: 123 }, // title cast ignored, defaults to ''
          { title: ' Valid ', due_date: '', priority: 'high', tags: [123, 'valid'] } // due_date blank, invalid priority, mixed tags
        ]
      };

      const preview = validateImportFile(data);
      expect(preview?.tasks.length).toBe(2); // Two objects

      expect(preview?.tasks[0].title).toBe('');

      expect(preview?.tasks[1].title).toBe('Valid');
      expect(preview?.tasks[1].dueDate).toBeNull();
      expect(preview?.tasks[1].priority).toBeNull();
      expect(preview?.tasks[1].tags).toEqual(['valid']);
    });
  });

  describe('parseImportFile', () => {
    let originalFileReader: any;
    let mockReaderInstance: any;

    beforeEach(() => {
      originalFileReader = global.FileReader;

      mockReaderInstance = {
        readAsText: vi.fn(),
        onload: null,
        onerror: null,
      };

      class MockFileReader {
        onload: any = null;
        onerror: any = null;
        readAsText = vi.fn().mockImplementation(() => {
          mockReaderInstance.onload = this.onload;
          mockReaderInstance.onerror = this.onerror;
        });
      }

      global.FileReader = MockFileReader as any;
    });

    afterEach(() => {
      global.FileReader = originalFileReader;
    });

    it('resolves with parsed data and preview on success', async () => {
      const mockFile = new File([''], 'test.json');
      const promise = parseImportFile(mockFile);

      // Simulate file load
      const validJson = JSON.stringify({ version: 1, tasks: [{ title: 'Test' }] });
      mockReaderInstance.onload({ target: { result: validJson } });

      const result = await promise;
      expect(result.data).toEqual({ version: 1, tasks: [{ title: 'Test' }] });
      expect(result.preview).toBeDefined();
      expect(result.preview.version).toBe(1);
    });

    it('rejects on invalid JSON format', async () => {
      const mockFile = new File([''], 'test.json');
      const promise = parseImportFile(mockFile);

      mockReaderInstance.onload({ target: { result: 'invalid json' } });

      await expect(promise).rejects.toThrow('Failed to parse JSON file.');
    });

    it('rejects on missing version in JSON (invalid import file format)', async () => {
      const mockFile = new File([''], 'test.json');
      const promise = parseImportFile(mockFile);

      mockReaderInstance.onload({ target: { result: JSON.stringify({ random: 'data' }) } });

      await expect(promise).rejects.toThrow('Invalid import file format. Missing "version" field.');
    });

    it('rejects on file read error', async () => {
      const mockFile = new File([''], 'test.json');
      const promise = parseImportFile(mockFile);

      mockReaderInstance.onerror();

      await expect(promise).rejects.toThrow('Failed to read file.');
    });
  });

  describe('importData', () => {
    it('calls exportApi.importJson and returns result', async () => {
      const mockResult = { goals: 1, tasks: 5 };
      (exportApi.importJson as Mock).mockResolvedValue(mockResult);

      const mockFile = new File([''], 'test.json');
      const onProgress = vi.fn();

      const result = await importData(mockFile, 'merge', onProgress);

      expect(onProgress).toHaveBeenCalledWith('Uploading and importing data...');
      expect(exportApi.importJson).toHaveBeenCalledWith(mockFile, 'merge');
      expect(result).toBe(mockResult);
    });
  });
});

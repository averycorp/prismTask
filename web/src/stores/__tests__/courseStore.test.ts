import { describe, it, expect, beforeEach, vi } from 'vitest';

const {
  subscribeCoursesMock,
  subscribeCompletionsMock,
  getCoursesMock,
  createCourseMock,
  updateCourseMock,
  deleteCourseMock,
  toggleCompletionMock,
  deleteAssignmentsForCourseMock,
  assignmentSubscribeMock,
  assignmentResetMock,
  unsubscribeCoursesMock,
  unsubscribeCompletionsMock,
  unsubscribeAssignmentsMock,
} = vi.hoisted(() => ({
  subscribeCoursesMock: vi.fn(),
  subscribeCompletionsMock: vi.fn(),
  getCoursesMock: vi.fn(),
  createCourseMock: vi.fn(),
  updateCourseMock: vi.fn(),
  deleteCourseMock: vi.fn(),
  toggleCompletionMock: vi.fn(),
  deleteAssignmentsForCourseMock: vi.fn(),
  assignmentSubscribeMock: vi.fn(),
  assignmentResetMock: vi.fn(),
  unsubscribeCoursesMock: vi.fn(),
  unsubscribeCompletionsMock: vi.fn(),
  unsubscribeAssignmentsMock: vi.fn(),
}));

vi.mock('@/api/firestore/courses', () => ({
  subscribeToCourses: subscribeCoursesMock,
  getCourses: getCoursesMock,
  createCourse: createCourseMock,
  updateCourse: updateCourseMock,
  deleteCourse: deleteCourseMock,
}));
vi.mock('@/api/firestore/courseCompletions', () => ({
  subscribeToCourseCompletions: subscribeCompletionsMock,
  toggleCourseCompletion: toggleCompletionMock,
  courseCompletionId: (courseId: string, dateMillis: number) =>
    `${courseId}_${dateMillis}`,
}));
vi.mock('@/api/firestore/assignments', () => ({
  deleteAssignmentsForCourse: deleteAssignmentsForCourseMock,
}));
vi.mock('@/stores/assignmentStore', () => ({
  useAssignmentStore: {
    getState: () => ({
      subscribe: assignmentSubscribeMock,
      reset: assignmentResetMock,
    }),
  },
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));
vi.mock('@/stores/firebaseUid', () => ({
  getFirebaseUid: () => 'uid-test',
}));

import { useCourseStore } from '@/stores/courseStore';
import type { Course, CourseCompletion } from '@/types/schoolwork';

const baseCourse: Course = {
  id: 'c1',
  name: 'CS-101',
  code: 'CS101',
  color: 0xff4caf50,
  icon: '📚',
  active: true,
  sortOrder: 0,
  createdAt: 0,
  updatedAt: 0,
  createDailyTask: false,
};

function resetStore() {
  useCourseStore.setState({
    courses: [],
    completions: [],
    isLoading: false,
    error: null,
  });
}

describe('useCourseStore', () => {
  beforeEach(() => {
    subscribeCoursesMock.mockReset();
    subscribeCompletionsMock.mockReset();
    getCoursesMock.mockReset();
    createCourseMock.mockReset();
    updateCourseMock.mockReset();
    deleteCourseMock.mockReset();
    toggleCompletionMock.mockReset();
    deleteAssignmentsForCourseMock.mockReset();
    assignmentSubscribeMock.mockReset();
    assignmentResetMock.mockReset();

    subscribeCoursesMock.mockReturnValue(unsubscribeCoursesMock);
    subscribeCompletionsMock.mockReturnValue(unsubscribeCompletionsMock);
    assignmentSubscribeMock.mockReturnValue(unsubscribeAssignmentsMock);
    resetStore();
  });

  it('starts empty', () => {
    expect(useCourseStore.getState().courses).toEqual([]);
    expect(useCourseStore.getState().completions).toEqual([]);
  });

  it('subscribe wires courses + completions + assignments', () => {
    const unsub = useCourseStore.getState().subscribe('uid-1');
    expect(subscribeCoursesMock).toHaveBeenCalledWith(
      'uid-1',
      expect.any(Function),
    );
    expect(subscribeCompletionsMock).toHaveBeenCalledWith(
      'uid-1',
      expect.any(Function),
    );
    expect(assignmentSubscribeMock).toHaveBeenCalledWith('uid-1');

    // Bundle unsubscribe tears all three down + resets assignments.
    unsub();
    expect(unsubscribeCoursesMock).toHaveBeenCalledTimes(1);
    expect(unsubscribeCompletionsMock).toHaveBeenCalledTimes(1);
    expect(unsubscribeAssignmentsMock).toHaveBeenCalledTimes(1);
    expect(assignmentResetMock).toHaveBeenCalledTimes(1);
  });

  it('fetch loads via getCourses and stores the result', async () => {
    getCoursesMock.mockResolvedValueOnce([baseCourse]);
    await useCourseStore.getState().fetch();
    expect(getCoursesMock).toHaveBeenCalledWith('uid-test');
    expect(useCourseStore.getState().courses).toEqual([baseCourse]);
    expect(useCourseStore.getState().error).toBe(null);
  });

  it('createCourse defaults sortOrder to (max + 1) and appends locally', async () => {
    useCourseStore.setState({
      courses: [
        { ...baseCourse, id: 'a', sortOrder: 0 },
        { ...baseCourse, id: 'b', sortOrder: 4 },
      ],
    });
    createCourseMock.mockImplementationOnce(async (_uid, input) => ({
      ...baseCourse,
      id: 'new',
      name: input.name,
      sortOrder: input.sortOrder,
    }));
    const created = await useCourseStore.getState().createCourse({
      name: 'Physics',
      code: 'PHYS-1',
      color: 0xff4caf50,
      icon: '🔬',
      active: true,
      createDailyTask: false,
    });
    expect(createCourseMock).toHaveBeenCalledWith(
      'uid-test',
      expect.objectContaining({ sortOrder: 5 }),
    );
    expect(created.name).toBe('Physics');
    expect(useCourseStore.getState().courses.map((c) => c.id)).toContain('new');
  });

  it('archiveCourse delegates to updateCourse with active=false', async () => {
    useCourseStore.setState({ courses: [{ ...baseCourse, id: 'c1' }] });
    updateCourseMock.mockResolvedValueOnce(undefined);
    await useCourseStore.getState().archiveCourse('c1');
    expect(updateCourseMock).toHaveBeenCalledWith('uid-test', 'c1', {
      active: false,
    });
    const c = useCourseStore.getState().courses.find((c) => c.id === 'c1');
    expect(c?.active).toBe(false);
  });

  it('unarchiveCourse delegates to updateCourse with active=true', async () => {
    useCourseStore.setState({
      courses: [{ ...baseCourse, id: 'c1', active: false }],
    });
    updateCourseMock.mockResolvedValueOnce(undefined);
    await useCourseStore.getState().unarchiveCourse('c1');
    expect(updateCourseMock).toHaveBeenCalledWith('uid-test', 'c1', {
      active: true,
    });
    const c = useCourseStore.getState().courses.find((c) => c.id === 'c1');
    expect(c?.active).toBe(true);
  });

  it('deleteCourse cascades child assignments first and removes locally', async () => {
    useCourseStore.setState({ courses: [{ ...baseCourse, id: 'c1' }] });
    deleteAssignmentsForCourseMock.mockResolvedValueOnce(undefined);
    deleteCourseMock.mockResolvedValueOnce(undefined);
    await useCourseStore.getState().deleteCourse('c1');
    expect(deleteAssignmentsForCourseMock).toHaveBeenCalledWith(
      'uid-test',
      'c1',
    );
    expect(deleteCourseMock).toHaveBeenCalledWith('uid-test', 'c1');
    expect(useCourseStore.getState().courses).toEqual([]);
  });

  it('toggleCompletion flips completed and optimistically replaces locally', async () => {
    const completion: CourseCompletion = {
      id: 'c1_0',
      courseCloudId: 'c1',
      date: 0,
      completed: true,
      completedAt: 0,
      createdAt: 0,
      updatedAt: 0,
    };
    toggleCompletionMock.mockResolvedValueOnce(completion);
    await useCourseStore.getState().toggleCompletion('c1', 0);
    expect(toggleCompletionMock).toHaveBeenCalledWith(
      'uid-test',
      'c1',
      0,
      true,
    );
    expect(useCourseStore.getState().completions).toEqual([completion]);
  });

  it('getCompletionForDate finds by composite id', () => {
    const completion: CourseCompletion = {
      id: 'c1_1000',
      courseCloudId: 'c1',
      date: 1000,
      completed: true,
      completedAt: 1000,
      createdAt: 0,
      updatedAt: 0,
    };
    useCourseStore.setState({ completions: [completion] });
    expect(useCourseStore.getState().getCompletionForDate('c1', 1000)).toEqual(
      completion,
    );
    expect(useCourseStore.getState().getCompletionForDate('c1', 999)).toBe(
      null,
    );
  });
});

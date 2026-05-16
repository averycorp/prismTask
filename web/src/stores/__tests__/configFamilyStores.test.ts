import { describe, it, expect, beforeEach, vi } from 'vitest';

/**
 * Unit-tests for the six read-only config-family Zustand stores added
 * in Sync Sweep B (unit 2 of 23). Each store wraps a Firestore
 * `subscribeTo*` listener and exposes a `reset()` cleanup. We verify:
 *
 *   - Default state is an empty list.
 *   - `subscribeTo*` forwards uid + callback to the API helper and
 *     returns the unsubscribe handle untouched.
 *   - The snapshot callback writes into the cached field.
 *   - `reset()` empties the list (sign-out path).
 *
 * Firestore is fully mocked — no emulator, no network.
 */

const {
  subscribeProfilesMock,
  subscribeSoundsMock,
  subscribeFiltersMock,
  subscribeShortcutsMock,
  subscribeHabitTemplatesMock,
  subscribeProjectTemplatesMock,
  unsubscribeMock,
} = vi.hoisted(() => ({
  subscribeProfilesMock: vi.fn(),
  subscribeSoundsMock: vi.fn(),
  subscribeFiltersMock: vi.fn(),
  subscribeShortcutsMock: vi.fn(),
  subscribeHabitTemplatesMock: vi.fn(),
  subscribeProjectTemplatesMock: vi.fn(),
  unsubscribeMock: vi.fn(),
}));

vi.mock('@/api/firestore/notificationProfiles', () => ({
  subscribeToNotificationProfiles: subscribeProfilesMock,
}));
vi.mock('@/api/firestore/customSounds', () => ({
  subscribeToCustomSounds: subscribeSoundsMock,
}));
vi.mock('@/api/firestore/savedFilters', () => ({
  subscribeToSavedFilters: subscribeFiltersMock,
}));
vi.mock('@/api/firestore/nlpShortcuts', () => ({
  subscribeToNlpShortcuts: subscribeShortcutsMock,
}));
vi.mock('@/api/firestore/habitTemplates', () => ({
  subscribeToHabitTemplates: subscribeHabitTemplatesMock,
}));
vi.mock('@/api/firestore/projectTemplates', () => ({
  subscribeToProjectTemplates: subscribeProjectTemplatesMock,
}));
vi.mock('@/lib/firebase', () => ({ firestore: { __mock: true } }));

import { useNotificationProfilesStore } from '@/stores/notificationProfilesStore';
import { useCustomSoundsStore } from '@/stores/customSoundsStore';
import { useSavedFiltersStore } from '@/stores/savedFiltersStore';
import { useNlpShortcutsStore } from '@/stores/nlpShortcutsStore';
import { useHabitTemplatesStore } from '@/stores/habitTemplatesStore';
import { useProjectTemplatesStore } from '@/stores/projectTemplatesStore';
import type { NotificationProfile } from '@/api/firestore/notificationProfiles';
import type { CustomSound } from '@/api/firestore/customSounds';
import type { SavedFilter } from '@/api/firestore/savedFilters';
import type { NlpShortcut } from '@/api/firestore/nlpShortcuts';
import type { HabitTemplate } from '@/api/firestore/habitTemplates';
import type { ProjectTemplate } from '@/api/firestore/projectTemplates';

beforeEach(() => {
  subscribeProfilesMock.mockReset();
  subscribeSoundsMock.mockReset();
  subscribeFiltersMock.mockReset();
  subscribeShortcutsMock.mockReset();
  subscribeHabitTemplatesMock.mockReset();
  subscribeProjectTemplatesMock.mockReset();
  unsubscribeMock.mockReset();
  subscribeProfilesMock.mockReturnValue(unsubscribeMock);
  subscribeSoundsMock.mockReturnValue(unsubscribeMock);
  subscribeFiltersMock.mockReturnValue(unsubscribeMock);
  subscribeShortcutsMock.mockReturnValue(unsubscribeMock);
  subscribeHabitTemplatesMock.mockReturnValue(unsubscribeMock);
  subscribeProjectTemplatesMock.mockReturnValue(unsubscribeMock);
  useNotificationProfilesStore.getState().reset();
  useCustomSoundsStore.getState().reset();
  useSavedFiltersStore.getState().reset();
  useNlpShortcutsStore.getState().reset();
  useHabitTemplatesStore.getState().reset();
  useProjectTemplatesStore.getState().reset();
});

describe('useNotificationProfilesStore', () => {
  it('starts with an empty profiles list', () => {
    expect(useNotificationProfilesStore.getState().profiles).toEqual([]);
  });

  it('subscribeToProfiles wires the Firestore listener and pipes snapshots through', () => {
    const unsub = useNotificationProfilesStore
      .getState()
      .subscribeToProfiles('uid-1');
    expect(subscribeProfilesMock).toHaveBeenCalledWith(
      'uid-1',
      expect.any(Function),
    );
    expect(unsub).toBe(unsubscribeMock);

    const cb = subscribeProfilesMock.mock.calls[0][1] as (
      profiles: NotificationProfile[],
    ) => void;
    const remote: NotificationProfile[] = [
      {
        cloud_id: 'doc-1',
        local_id: 7,
        name: 'Work',
        offsets_csv: '0,300000',
        escalation: true,
        escalation_interval_minutes: 5,
        is_built_in: false,
        urgency_tier_key: 'high',
        sound_id: 'system_default',
        sound_volume_percent: 80,
        sound_fade_in_ms: 0,
        sound_fade_out_ms: 0,
        silent: false,
        vibration_preset_key: 'single',
        vibration_intensity_key: 'medium',
        vibration_repeat_count: 1,
        vibration_continuous: false,
        custom_vibration_pattern_csv: null,
        display_mode_key: 'standard',
        lock_screen_visibility_key: 'app_name',
        accent_color_hex: null,
        badge_mode_key: 'total',
        toast_position_key: 'top_right',
        escalation_chain_json: null,
        quiet_hours_json: null,
        snooze_durations_csv: '5,15,30,60',
        re_alert_interval_minutes: 5,
        re_alert_max_attempts: 3,
        watch_sync_mode_key: 'mirror',
        watch_haptic_preset_key: 'single',
        auto_switch_rules_json: null,
        volume_override: false,
        created_at: 1_700_000_000_000,
        updated_at: 1_700_000_001_000,
      },
    ];
    cb(remote);
    expect(useNotificationProfilesStore.getState().profiles).toEqual(remote);
  });

  it('reset clears the profiles list (sign-out path)', () => {
    useNotificationProfilesStore.setState({
      profiles: [{ cloud_id: 'x' } as NotificationProfile],
    });
    useNotificationProfilesStore.getState().reset();
    expect(useNotificationProfilesStore.getState().profiles).toEqual([]);
  });
});

describe('useCustomSoundsStore', () => {
  it('starts with an empty sounds list', () => {
    expect(useCustomSoundsStore.getState().sounds).toEqual([]);
  });

  it('subscribeToSounds wires the Firestore listener and pipes snapshots through', () => {
    const unsub = useCustomSoundsStore.getState().subscribeToSounds('uid-2');
    expect(subscribeSoundsMock).toHaveBeenCalledWith(
      'uid-2',
      expect.any(Function),
    );
    expect(unsub).toBe(unsubscribeMock);

    const cb = subscribeSoundsMock.mock.calls[0][1] as (
      sounds: CustomSound[],
    ) => void;
    const remote: CustomSound[] = [
      {
        cloud_id: 'doc-2',
        local_id: 3,
        name: 'Chime',
        original_filename: 'chime.mp3',
        uri: 'file:///data/user/0/.../sounds/chime.mp3',
        format: 'mp3',
        size_bytes: 1024,
        duration_ms: 1500,
        created_at: 1_700_000_000_000,
        updated_at: 1_700_000_001_000,
      },
    ];
    cb(remote);
    expect(useCustomSoundsStore.getState().sounds).toEqual(remote);
  });

  it('reset clears the sounds list', () => {
    useCustomSoundsStore.setState({
      sounds: [{ cloud_id: 'x' } as CustomSound],
    });
    useCustomSoundsStore.getState().reset();
    expect(useCustomSoundsStore.getState().sounds).toEqual([]);
  });
});

describe('useSavedFiltersStore', () => {
  it('starts with an empty filters list', () => {
    expect(useSavedFiltersStore.getState().filters).toEqual([]);
  });

  it('subscribeToFilters wires the Firestore listener and pipes snapshots through', () => {
    const unsub = useSavedFiltersStore.getState().subscribeToFilters('uid-3');
    expect(subscribeFiltersMock).toHaveBeenCalledWith(
      'uid-3',
      expect.any(Function),
    );
    expect(unsub).toBe(unsubscribeMock);

    const cb = subscribeFiltersMock.mock.calls[0][1] as (
      filters: SavedFilter[],
    ) => void;
    const remote: SavedFilter[] = [
      {
        cloud_id: 'doc-3',
        local_id: 1,
        name: 'Today Focus',
        filter_json: '{"completed":false}',
        icon_emoji: '⭐',
        sort_order: 0,
        created_at: 1_700_000_000_000,
        updated_at: 1_700_000_001_000,
      },
    ];
    cb(remote);
    expect(useSavedFiltersStore.getState().filters).toEqual(remote);
  });

  it('reset clears the filters list', () => {
    useSavedFiltersStore.setState({
      filters: [{ cloud_id: 'x' } as SavedFilter],
    });
    useSavedFiltersStore.getState().reset();
    expect(useSavedFiltersStore.getState().filters).toEqual([]);
  });
});

describe('useNlpShortcutsStore', () => {
  it('starts with an empty shortcuts list', () => {
    expect(useNlpShortcutsStore.getState().shortcuts).toEqual([]);
  });

  it('subscribeToShortcuts wires the Firestore listener and pipes snapshots through', () => {
    const unsub = useNlpShortcutsStore
      .getState()
      .subscribeToShortcuts('uid-4');
    expect(subscribeShortcutsMock).toHaveBeenCalledWith(
      'uid-4',
      expect.any(Function),
    );
    expect(unsub).toBe(unsubscribeMock);

    const cb = subscribeShortcutsMock.mock.calls[0][1] as (
      shortcuts: NlpShortcut[],
    ) => void;
    const remote: NlpShortcut[] = [
      {
        cloud_id: 'doc-4',
        local_id: 2,
        trigger: 'std',
        expansion: 'Stand-up meeting #work',
        sort_order: 0,
        created_at: 1_700_000_000_000,
        updated_at: 1_700_000_001_000,
      },
    ];
    cb(remote);
    expect(useNlpShortcutsStore.getState().shortcuts).toEqual(remote);
  });

  it('reset clears the shortcuts list', () => {
    useNlpShortcutsStore.setState({
      shortcuts: [{ cloud_id: 'x' } as NlpShortcut],
    });
    useNlpShortcutsStore.getState().reset();
    expect(useNlpShortcutsStore.getState().shortcuts).toEqual([]);
  });
});

describe('useHabitTemplatesStore', () => {
  it('starts with an empty templates list', () => {
    expect(useHabitTemplatesStore.getState().templates).toEqual([]);
  });

  it('subscribeToTemplates wires the Firestore listener and pipes snapshots through', () => {
    const unsub = useHabitTemplatesStore
      .getState()
      .subscribeToTemplates('uid-5');
    expect(subscribeHabitTemplatesMock).toHaveBeenCalledWith(
      'uid-5',
      expect.any(Function),
    );
    expect(unsub).toBe(unsubscribeMock);

    const cb = subscribeHabitTemplatesMock.mock.calls[0][1] as (
      templates: HabitTemplate[],
    ) => void;
    const remote: HabitTemplate[] = [
      {
        cloud_id: 'doc-5',
        local_id: 4,
        name: 'Morning Stretch',
        description: null,
        icon_emoji: '🧘',
        color: null,
        category: 'Health',
        frequency: 'DAILY',
        target_count: 1,
        active_days_csv: '',
        is_built_in: false,
        usage_count: 0,
        last_used_at: null,
        created_at: 1_700_000_000_000,
        updated_at: 1_700_000_001_000,
      },
    ];
    cb(remote);
    expect(useHabitTemplatesStore.getState().templates).toEqual(remote);
  });

  it('reset clears the templates list', () => {
    useHabitTemplatesStore.setState({
      templates: [{ cloud_id: 'x' } as HabitTemplate],
    });
    useHabitTemplatesStore.getState().reset();
    expect(useHabitTemplatesStore.getState().templates).toEqual([]);
  });
});

describe('useProjectTemplatesStore', () => {
  it('starts with an empty templates list', () => {
    expect(useProjectTemplatesStore.getState().templates).toEqual([]);
  });

  it('subscribeToTemplates wires the Firestore listener and pipes snapshots through', () => {
    const unsub = useProjectTemplatesStore
      .getState()
      .subscribeToTemplates('uid-6');
    expect(subscribeProjectTemplatesMock).toHaveBeenCalledWith(
      'uid-6',
      expect.any(Function),
    );
    expect(unsub).toBe(unsubscribeMock);

    const cb = subscribeProjectTemplatesMock.mock.calls[0][1] as (
      templates: ProjectTemplate[],
    ) => void;
    const remote: ProjectTemplate[] = [
      {
        cloud_id: 'doc-6',
        local_id: 5,
        name: 'Course Setup',
        description: null,
        color: null,
        icon_emoji: null,
        category: 'School',
        task_templates_json: '[]',
        is_built_in: false,
        usage_count: 0,
        last_used_at: null,
        created_at: 1_700_000_000_000,
        updated_at: 1_700_000_001_000,
      },
    ];
    cb(remote);
    expect(useProjectTemplatesStore.getState().templates).toEqual(remote);
  });

  it('reset clears the templates list', () => {
    useProjectTemplatesStore.setState({
      templates: [{ cloud_id: 'x' } as ProjectTemplate],
    });
    useProjectTemplatesStore.getState().reset();
    expect(useProjectTemplatesStore.getState().templates).toEqual([]);
  });
});

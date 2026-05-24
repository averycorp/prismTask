import { render, screen } from '@testing-library/react';
import { OrganizeTab } from '../tabs/OrganizeTab';
import React from 'react';

describe('OrganizeTab Independent Crash', () => {
  it('should not crash when rendering OrganizeTab directly', () => {
    render(
      <OrganizeTab
        isCreate={false}
        taskId="123"
        projectId={null}
        onProjectIdChange={() => {}}
        taskMode=""
        onTaskModeChange={() => {}}
        cognitiveLoad=""
        onCognitiveLoadChange={() => {}}
        tags={[]}
        onTagsChange={() => {}}
      />
    );
  });
});

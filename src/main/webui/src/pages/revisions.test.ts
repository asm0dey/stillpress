import { expect, test } from 'vitest';
import { compare } from './Revisions.tsx';

test('compare marks a changed word only', () => {
  const c = compare({ title: 'Old title', body: 'a\nb\n' }, { title: 'New title', body: 'a\nc\n' });
  expect(c.title.filter((x) => x.added).map((x) => x.value.trim())).toEqual(['New']);
  expect(c.body.filter((x) => x.removed).map((x) => x.value)).toEqual(['b\n']);
});

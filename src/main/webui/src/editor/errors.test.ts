import { expect, test } from 'vitest';
import { saveError } from './errors.ts';

const text = (err: Parameters<typeof saveError>[0], noun: 'post' | 'block' = 'post') => saveError(err, noun).text;

test('unsafe HTML names the noun and lists the violations', () => {
  expect(saveError({ status: 400, error: 'unsafe_html', violations: ['<script> element'] }, 'block')).toEqual({
    kind: 'alert', text: 'This block contains HTML your role cannot publish:', list: ['<script> element'],
  });
  expect(text({ status: 400, error: 'unsafe_html' })).toBe('This post contains HTML your role cannot publish:');
});

test('a conflict, a refusal and the CSRF guard each say what happened', () => {
  expect(text({ status: 409 })).toMatch(/^Someone else changed this post\./);
  expect(text({ status: 409 }, 'block')).toMatch(/^Someone else changed this block\./);
  expect(text({ status: 403 })).toBe('You cannot make this change.');
  expect(text({ status: 400, error: 'csrf', message: 'Reload the page.' })).toBe('Reload the page.');
});

test('a refused field is named, known ones in words', () => {
  expect(text({ status: 400, field: 'slug' })).toBe('This slug cannot be used.');
  expect(text({ status: 400, field: 'date' })).toBe('A scheduled post needs a date in the future.');
  expect(text({ status: 400, field: 'menuOrder' })).toBe('Check the menuOrder field.');
});

test('anything else keeps the text in the tab', () => {
  expect(saveError({ status: 0 }, 'post')).toEqual({ kind: 'alert', list: undefined, text: 'Could not save. Your text stays in this tab.' });
});

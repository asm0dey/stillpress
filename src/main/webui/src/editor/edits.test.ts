import { expect, test } from 'vitest';
import { Edits } from './edits.ts';

test('blocks that serialize like the loaded body are not an edit', () => {
  const e = new Edits('<p>a</p>');
  e.blocks('<p>a</p>');
  expect([e.dirty, e.needsAutosave]).toEqual([false, false]);
  e.blocks('<p>ab</p>');
  expect([e.dirty, e.needsAutosave]).toEqual([true, true]);
});

test('an edit made while a save is in flight stays unsaved', () => {
  const e = new Edits('');
  e.edit();
  const mark = e.mark();
  e.edit(); // typed before the save answered
  e.saved(mark, '<p>a</p>');
  expect([e.dirty, e.needsAutosave]).toEqual([true, true]);
  const again = e.mark();
  e.saved(again, '<p>ab</p>');
  expect([e.dirty, e.needsAutosave]).toEqual([false, false]);
});

test('a save moves the baseline the blocks are compared with', () => {
  const e = new Edits('<p>a</p>');
  e.blocks('<p>b</p>');
  e.saved(e.mark(), '<p>b</p>');
  e.blocks('<p>b</p>');
  expect(e.dirty).toEqual(false);
});

test('an autosave clears only what it covered', () => {
  const e = new Edits('');
  e.edit();
  const mark = e.mark();
  e.edit();
  e.autosaved(mark);
  expect([e.dirty, e.needsAutosave]).toEqual([true, true]);
  e.autosaved(e.mark());
  expect([e.dirty, e.needsAutosave]).toEqual([true, false]);
});

test('the same blocks reported again during a save do not make it dirty', () => {
  const e = new Edits('a');
  e.blocks('x');
  const mark = e.mark();
  e.blocks('x');
  e.saved(mark, 'x');
  expect(e.dirty).toBe(false);
});

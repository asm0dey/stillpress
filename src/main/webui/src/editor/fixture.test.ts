/// <reference types="bun" />
import { expect, test } from 'bun:test';
import { wxrBodies } from './testdom.ts';

interface Parsed { name: string; isValid: boolean; innerBlocks: Parsed[] }
const flatten = (blocks: Parsed[]): Parsed[] => blocks.flatMap((b) => [b, ...flatten(b.innerBlocks)]);

test('every block in the sample WXR is valid Gutenberg content', async () => {
  const { registerCoreBlocks } = await import('@wordpress/block-library');
  const { parse } = await import('@wordpress/blocks');
  registerCoreBlocks();
  const bodies = (await wxrBodies()).filter((body) => body.includes('<!-- wp:'));
  expect(bodies.length).toBeGreaterThan(0);
  const blocks = bodies.flatMap((body) => flatten(parse(body) as unknown as Parsed[]));
  expect(blocks.map((b) => b.name)).toContain('core/gallery');
  expect(blocks.filter((b) => !b.isValid).map((b) => b.name)).toEqual([]);
});

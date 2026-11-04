import type { ApiError } from '../api.ts';

// kept apart from workspace.tsx so the revisions screens can use it without loading Gutenberg
export interface Message { kind: 'status' | 'alert'; text: string; list?: string[] }

type Noun = 'post' | 'block';

/** The 400 unsafe_html refusal: the role lacks unfiltered_html, nothing was saved. */
export const UNSAFE_HTML = (noun: Noun) => `This ${noun} contains HTML your role cannot publish:`;

const FIELD_ERRORS: Record<string, string> = {
  date: 'A scheduled post needs a date in the future.',
  slug: 'This slug cannot be used.',
  parentId: 'Choose another parent page.',
  authorId: 'That author no longer exists.',
};

/** What a refused save tells the user. */
export function saveError(err: ApiError, noun: Noun): Message {
  return {
    kind: 'alert',
    list: err.error === 'unsafe_html' ? err.violations : undefined,
    text: err.error === 'unsafe_html' ? UNSAFE_HTML(noun)
      : err.status === 409 ? `Someone else changed this ${noun}. Reload to see their version; your text stays in this tab.`
      : err.status === 403 ? 'You cannot make this change.'
      : err.error === 'csrf' ? err.message!
      : err.field ? FIELD_ERRORS[err.field] ?? `Check the ${err.field} field.`
      : 'Could not save. Your text stays in this tab.',
  };
}

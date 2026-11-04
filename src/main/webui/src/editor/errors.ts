import { __, sprintf } from '@wordpress/i18n';
import type { ApiError } from '../api.ts';

// kept apart from workspace.tsx so the revisions screens can use it without loading Gutenberg
export interface Message { kind: 'status' | 'alert'; text: string; list?: string[] }

type Noun = 'post' | 'block';

/** The 400 unsafe_html refusal: the role lacks unfiltered_html, nothing was saved. */
export const UNSAFE_HTML = (noun: Noun) => noun === 'post'
  ? __('This post contains HTML your role cannot publish:', 'stillpress')
  : __('This block contains HTML your role cannot publish:', 'stillpress');

const fieldErrors = (): Record<string, string> => ({
  date: __('A scheduled post needs a date in the future.', 'stillpress'),
  slug: __('This slug cannot be used.', 'stillpress'),
  parentId: __('Choose another parent page.', 'stillpress'),
  authorId: __('That author no longer exists.', 'stillpress'),
});

/** What a refused save tells the user. */
export function saveError(err: ApiError, noun: Noun): Message {
  return {
    kind: 'alert',
    list: err.error === 'unsafe_html' ? err.violations : undefined,
    text: err.error === 'unsafe_html' ? UNSAFE_HTML(noun)
      : err.status === 409 ? (noun === 'post'
        ? __('Someone else changed this post. Reload to see their version; your text stays in this tab.', 'stillpress')
        : __('Someone else changed this block. Reload to see their version; your text stays in this tab.', 'stillpress'))
      : err.status === 403 ? __('You cannot make this change.', 'stillpress')
      : err.error === 'csrf' ? err.message!
      : err.field ? fieldErrors()[err.field] ?? sprintf(__('Check the %s field.', 'stillpress'), err.field)
      : __('Could not save. Your text stays in this tab.', 'stillpress'),
  };
}

import { type FormEvent, type ReactNode, useState } from 'react';
import {
  Button, CheckboxControl, DateTimePicker, Dropdown, FormTokenField, PanelBody, SelectControl, TabPanel, TextControl,
  TextareaControl,
} from '@wordpress/components';
import { BlockInspector } from '@wordpress/block-editor';
import { getSettings } from '@wordpress/date';
import { __, sprintf } from '@wordpress/i18n';
import { formatLocal } from '../i18n/index.ts';
import type { Item, SessionUser, TermView, UserView } from '../api.ts';

/** The sidebar's part of what a save sends; tags are names, new ones are created on save. */
export interface Draft {
  slug: string; excerpt: string; visibility: 'public' | 'private'; date: string | null; dateChanged: boolean;
  categories: number[]; tags: string[]; authorId: number | null; commentStatus: 'open' | 'closed';
  parentId: number | null; menuOrder: number;
}

export const isKnownTag = (tags: TermView[], name: string) =>
  tags.some((t) => t.name.toLowerCase() === name.trim().toLowerCase());

const status = (s: string): string => ({
  draft: __('Draft', 'stillpress'), pending: __('Pending review', 'stillpress'), future: __('Scheduled', 'stillpress'),
  publish: __('Published', 'stillpress'), private: __('Private', 'stillpress'), trash: __('Trash', 'stillpress'),
} as Record<string, string>)[s] ?? s;

interface Props {
  item: Item;
  session: SessionUser;
  draft: Draft;
  onChange: (patch: Partial<Draft>) => void;
  categories: TermView[];
  onAddCategory: (name: string, parentId: number | null) => Promise<void>;
  tags: TermView[];
  users: UserView[] | null;
  pages: { id: number; title: string }[];
}

/** The post settings (status, date, slug, terms, excerpt, author, discussion, page attributes) and the block inspector. */
export default function Sidebar(props: Props) {
  return (
    <TabPanel className="sp-sidebar__tabs" tabs={[
      { name: 'item', title: props.item.type === 'page' ? __('Page', 'stillpress') : __('Post', 'stillpress') },
      { name: 'block', title: __('Block', 'stillpress') },
    ]}>
      {(tab) => (tab.name === 'block' ? <BlockInspector /> : <ItemPanel {...props} />)}
    </TabPanel>
  );
}

function ItemPanel({ item, session, draft, onChange, categories, onAddCategory, tags, users, pages }: Props) {
  const editor = session.role === 'administrator' || session.role === 'editor';
  const canPublish = session.role !== 'contributor';
  const zone = getSettings().timezone.string || 'UTC';
  return (
    <>
      <PanelBody title={__('Status', 'stillpress')} initialOpen>
        <p>{status(item.status)}</p>
        {canPublish && (
          <SelectControl __next40pxDefaultSize label={__('Visibility', 'stillpress')} value={draft.visibility}
            options={[{ label: __('Public', 'stillpress'), value: 'public' }, { label: __('Private', 'stillpress'), value: 'private' }]}
            onChange={(v) => onChange({ visibility: v as Draft['visibility'] })} />
        )}
        <div className="sp-field">
          <span className="sp-label" id="sp-date-label">{__('Publish', 'stillpress')}</span>
          <Dropdown popoverProps={{ placement: 'left-start' }}
            renderToggle={({ isOpen, onToggle }) => (
              <Button variant="tertiary" onClick={onToggle} aria-expanded={isOpen} aria-describedby="sp-date-label">
                {draft.date ? formatLocal(draft.date) : __('Immediately', 'stillpress')}
              </Button>
            )}
            renderContent={() => (
              <div className="sp-date">
                <DateTimePicker currentDate={draft.date ?? undefined} is12Hour={false} startOfWeek={getSettings().l10n.startOfWeek}
                  onChange={(d) => onChange({ date: d, dateChanged: true })} />
                <p className="hint">{sprintf(__('Time zone: %s', 'stillpress'), zone)}</p>
              </div>
            )} />
        </div>
        <TextControl __next40pxDefaultSize label={__('Slug', 'stillpress')} value={draft.slug} help={__('The last part of the address.', 'stillpress')}
          onChange={(slug) => onChange({ slug })} />
      </PanelBody>
      {item.type === 'post' && (
        <>
          <PanelBody title={__('Categories', 'stillpress')}>
            <CategoryTree categories={categories} selected={draft.categories} onChange={(c) => onChange({ categories: c })} />
            {canPublish && <AddCategory categories={categories} onAdd={onAddCategory} />}
          </PanelBody>
          <PanelBody title={__('Tags', 'stillpress')}>
            <FormTokenField __next40pxDefaultSize label={__('Add tag', 'stillpress')} value={draft.tags} suggestions={tags.map((t) => t.name)}
              // contributors cannot create tags, so they may only pick existing ones
              __experimentalValidateInput={canPublish ? undefined : (t: string) => isKnownTag(tags, t)}
              onChange={(v) => onChange({ tags: v.map((t) => (typeof t === 'string' ? t : t.value)) })} />
            {!canPublish && <p className="hint">{__('Only existing tags can be added.', 'stillpress')}</p>}
          </PanelBody>
          <PanelBody title={__('Excerpt', 'stillpress')}>
            <TextareaControl label={__('Write an excerpt (optional)', 'stillpress')} value={draft.excerpt} onChange={(excerpt) => onChange({ excerpt })} />
          </PanelBody>
        </>
      )}
      {item.type === 'page' && (
        <PanelBody title={__('Page attributes', 'stillpress')}>
          <SelectControl __next40pxDefaultSize label={__('Parent page', 'stillpress')} value={String(draft.parentId ?? '')}
            options={[{ label: __('(no parent)', 'stillpress'), value: '' },
              ...pages.filter((p) => p.id !== item.id).map((p) => ({ label: p.title || sprintf(__('(no title) #%d', 'stillpress'), p.id), value: String(p.id) }))]}
            onChange={(v) => onChange({ parentId: v ? Number(v) : null })} />
          <TextControl __next40pxDefaultSize type="number" label={__('Order', 'stillpress')} value={String(draft.menuOrder)}
            onChange={(v) => onChange({ menuOrder: Number(v) || 0 })} />
        </PanelBody>
      )}
      <PanelBody title={__('Author and discussion', 'stillpress')}>
        {editor && users ? (
          <SelectControl __next40pxDefaultSize label={__('Author', 'stillpress')} value={String(draft.authorId ?? '')}
            options={users.map((u) => ({ label: u.displayName || u.login, value: String(u.id) }))}
            onChange={(v) => onChange({ authorId: Number(v) })} />
        ) : (
          <p><span className="sp-label">{__('Author', 'stillpress')}</span> {item.author ?? '—'}</p>
        )}
        <SelectControl __next40pxDefaultSize label={__('Comments', 'stillpress')} value={draft.commentStatus}
          options={[{ label: __('Open', 'stillpress'), value: 'open' }, { label: __('Closed', 'stillpress'), value: 'closed' }]}
          onChange={(v) => onChange({ commentStatus: v as Draft['commentStatus'] })} />
      </PanelBody>
    </>
  );
}

function CategoryTree({ categories, selected, onChange }: {
  categories: TermView[]; selected: number[]; onChange: (ids: number[]) => void;
}) {
  const branch = (parent: number | null, depth: number, seen: Set<number>): ReactNode[] =>
    categories.filter((c) => c.parentId === parent && !seen.has(c.id)).flatMap((c) => {
      seen.add(c.id);
      return [
        <div key={c.id} style={{ marginInlineStart: `calc(${depth} * var(--space-4))` }}>
          <CheckboxControl label={c.name} checked={selected.includes(c.id)}
            onChange={(on) => onChange(on ? [...selected, c.id] : selected.filter((x) => x !== c.id))} />
        </div>,
        ...branch(c.id, depth + 1, seen),
      ];
    });
  return <fieldset className="sp-categories"><legend className="sp-label">{__('Categories', 'stillpress')}</legend>{branch(null, 0, new Set())}</fieldset>;
}

function AddCategory({ categories, onAdd }: { categories: TermView[]; onAdd: (name: string, parentId: number | null) => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [parent, setParent] = useState('');
  if (!open) return <Button variant="link" onClick={() => setOpen(true)}>{__('Add new category', 'stillpress')}</Button>;
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    await onAdd(name.trim(), parent ? Number(parent) : null);
    setName('');
    setOpen(false);
  }
  return (
    <form onSubmit={submit} className="sp-add-category">
      <TextControl __next40pxDefaultSize label={__('New category name', 'stillpress')} value={name} onChange={setName} />
      <SelectControl __next40pxDefaultSize label={__('Parent category', 'stillpress')} value={parent}
        options={[{ label: __('— Parent category —', 'stillpress'), value: '' }, ...categories.map((c) => ({ label: c.name, value: String(c.id) }))]}
        onChange={setParent} />
      <Button variant="secondary" type="submit">{__('Add new category', 'stillpress')}</Button>
    </form>
  );
}

import { type FormEvent, type ReactNode, useState } from 'react';
import {
  Button, CheckboxControl, DateTimePicker, Dropdown, FormTokenField, PanelBody, SelectControl, TabPanel, TextControl,
  TextareaControl,
} from '@wordpress/components';
import { BlockInspector } from '@wordpress/block-editor';
import { format, getSettings } from '@wordpress/date';
import type { Item, SessionUser, TermView, UserView } from '../api.ts';

/** The sidebar's part of what a save sends; tags are names, new ones are created on save. */
export interface Draft {
  slug: string; excerpt: string; visibility: 'public' | 'private'; date: string | null; dateChanged: boolean;
  categories: number[]; tags: string[]; authorId: number | null; commentStatus: 'open' | 'closed';
  parentId: number | null; menuOrder: number;
}

const STATUS: Record<string, string> = {
  draft: 'Draft', pending: 'Pending review', future: 'Scheduled', publish: 'Published', private: 'Private', trash: 'Trash',
};

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
      { name: 'item', title: props.item.type === 'page' ? 'Page' : 'Post' },
      { name: 'block', title: 'Block' },
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
      <PanelBody title="Status" initialOpen>
        <p>{STATUS[item.status] ?? item.status}</p>
        {canPublish && (
          <SelectControl __next40pxDefaultSize label="Visibility" value={draft.visibility}
            options={[{ label: 'Public', value: 'public' }, { label: 'Private', value: 'private' }]}
            onChange={(v) => onChange({ visibility: v as Draft['visibility'] })} />
        )}
        <div className="sp-field">
          <span className="sp-label" id="sp-date-label">Publish</span>
          <Dropdown popoverProps={{ placement: 'left-start' }}
            renderToggle={({ isOpen, onToggle }) => (
              <Button variant="tertiary" onClick={onToggle} aria-expanded={isOpen} aria-describedby="sp-date-label">
                {draft.date ? format('F j, Y g:i a', draft.date) : 'Immediately'}
              </Button>
            )}
            renderContent={() => (
              <div className="sp-date">
                <DateTimePicker currentDate={draft.date ?? undefined} is12Hour={false}
                  onChange={(d) => onChange({ date: d, dateChanged: true })} />
                <p className="hint">Time zone: {zone}</p>
              </div>
            )} />
        </div>
        <TextControl __next40pxDefaultSize label="Slug" value={draft.slug} help="The last part of the address."
          onChange={(slug) => onChange({ slug })} />
      </PanelBody>
      {item.type === 'post' && (
        <>
          <PanelBody title="Categories">
            <CategoryTree categories={categories} selected={draft.categories} onChange={(c) => onChange({ categories: c })} />
            {canPublish && <AddCategory categories={categories} onAdd={onAddCategory} />}
          </PanelBody>
          <PanelBody title="Tags">
            <FormTokenField __next40pxDefaultSize label="Add tag" value={draft.tags} suggestions={tags.map((t) => t.name)}
              onChange={(v) => onChange({ tags: v.map((t) => (typeof t === 'string' ? t : t.value)) })} />
            {!canPublish && <p className="hint">Only existing tags can be added.</p>}
          </PanelBody>
          <PanelBody title="Excerpt">
            <TextareaControl label="Write an excerpt (optional)" value={draft.excerpt} onChange={(excerpt) => onChange({ excerpt })} />
          </PanelBody>
        </>
      )}
      {item.type === 'page' && (
        <PanelBody title="Page attributes">
          <SelectControl __next40pxDefaultSize label="Parent page" value={String(draft.parentId ?? '')}
            options={[{ label: '(no parent)', value: '' },
              ...pages.filter((p) => p.id !== item.id).map((p) => ({ label: p.title || `(no title) #${p.id}`, value: String(p.id) }))]}
            onChange={(v) => onChange({ parentId: v ? Number(v) : null })} />
          <TextControl __next40pxDefaultSize type="number" label="Order" value={String(draft.menuOrder)}
            onChange={(v) => onChange({ menuOrder: Number(v) || 0 })} />
        </PanelBody>
      )}
      <PanelBody title="Author and discussion">
        {editor && users ? (
          <SelectControl __next40pxDefaultSize label="Author" value={String(draft.authorId ?? '')}
            options={users.map((u) => ({ label: u.displayName || u.login, value: String(u.id) }))}
            onChange={(v) => onChange({ authorId: Number(v) })} />
        ) : (
          <p><span className="sp-label">Author</span> {item.author ?? '—'}</p>
        )}
        <SelectControl __next40pxDefaultSize label="Comments" value={draft.commentStatus}
          options={[{ label: 'Open', value: 'open' }, { label: 'Closed', value: 'closed' }]}
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
  return <fieldset className="sp-categories"><legend className="sp-label">Categories</legend>{branch(null, 0, new Set())}</fieldset>;
}

function AddCategory({ categories, onAdd }: { categories: TermView[]; onAdd: (name: string, parentId: number | null) => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [parent, setParent] = useState('');
  if (!open) return <Button variant="link" onClick={() => setOpen(true)}>Add new category</Button>;
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    await onAdd(name.trim(), parent ? Number(parent) : null);
    setName('');
    setOpen(false);
  }
  return (
    <form onSubmit={submit} className="sp-add-category">
      <TextControl __next40pxDefaultSize label="New category name" value={name} onChange={setName} />
      <SelectControl __next40pxDefaultSize label="Parent category" value={parent}
        options={[{ label: '— Parent category —', value: '' }, ...categories.map((c) => ({ label: c.name, value: String(c.id) }))]}
        onChange={setParent} />
      <Button variant="secondary" type="submit">Add new category</Button>
    </form>
  );
}

import { type ChangeEvent, type FormEvent, useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { __, sprintf } from '@wordpress/i18n';
import { type ApiError, type SiteSettings, type TermView, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';

const presets = (): { label: string; value: string }[] => [
  { label: __('Day and name', 'stillpress'), value: '/%year%/%monthnum%/%day%/%postname%/' },
  { label: __('Month and name', 'stillpress'), value: '/%year%/%monthnum%/%postname%/' },
  { label: __('Post name', 'stillpress'), value: '/%postname%/' },
  { label: __('Numeric', 'stillpress'), value: '/archives/%post_id%/' },
];
const CUSTOM = 'custom';

const messages = (): Record<string, string> => ({
  title: __('Enter a title.', 'stillpress'),
  language: __('Use a language code such as en or ru-RU.', 'stillpress'),
  homeUrl: __('Use a full address such as https://example.org, without a path.', 'stillpress'),
  timezone: __('Use a time zone such as Europe/Berlin.', 'stillpress'),
  postsPerPage: __('Use a number from 1 to 100.', 'stillpress'),
  postsPerRss: __('Use a number from 1 to 100.', 'stillpress'),
  permalinkStructure: __('Start and end with a slash, and include %postname% or %post_id%.', 'stillpress'),
  categoryBase: __('Use one word or slug.', 'stillpress'),
  tagBase: __('Use one word or slug, different from the category base.', 'stillpress'),
  defaultCategory: __('Choose one of the categories.', 'stillpress'),
  defaultCommentStatus: __('Choose open or closed.', 'stillpress'),
});

export default function Settings() {
  const [form, setForm] = useState<SiteSettings | null>(null);
  const [categories, setCategories] = useState<TermView[]>([]);
  const [preset, setPreset] = useState(CUSTOM);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  // the saved title, which the reset confirmation must match, not the one being edited
  const [siteTitle, setSiteTitle] = useState('');

  useEffect(() => {
    api<SiteSettings>('/settings').then((s) => {
      setForm(s);
      setSiteTitle(s.title);
      setPreset(presets().find((p) => p.value === s.permalinkStructure)?.value ?? CUSTOM);
    }, () => setMessage(__('Could not load the settings.', 'stillpress')));
    api<TermView[]>('/terms?taxonomy=category').then(setCategories, () => {});
  }, []);

  if (!form) {
    return <Shell>{message && <p role="alert" className="error">{message}</p>}</Shell>;
  }

  const text = (k: keyof SiteSettings) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm({ ...form, [k]: e.target.value });
  const count = (k: 'postsPerPage' | 'postsPerRss') => (e: ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [k]: Number(e.target.value) });

  async function save(e: FormEvent) {
    e.preventDefault();
    setMessage(null);
    setErrors({});
    setSaved(false);
    try {
      const s = await api<SiteSettings>('/settings', { method: 'PUT', body: form });
      setForm(s);
      setSiteTitle(s.title);
      setSaved(true);
    } catch (err) {
      const f = err as ApiError;
      if (f.field) setErrors({ [f.field]: messages()[f.field] ?? __('Check this field.', 'stillpress') });
      else setMessage(f.error === 'csrf' ? f.message! : __('Could not save the settings.', 'stillpress'));
    }
  }

  return (
    <Shell>
      <h1>{__('Settings', 'stillpress')}</h1>
      <form onSubmit={save}>
        <Field label={__('Site title', 'stillpress')} name="title" required value={form.title} onChange={text('title')} error={errors.title} />
        <Field label={__('Tagline', 'stillpress')} name="description" value={form.description} onChange={text('description')} />
        <Field label={__('Language', 'stillpress')} name="language" required value={form.language} onChange={text('language')} error={errors.language} />
        <Field label={__('Site address', 'stillpress')} name="homeUrl" required value={form.homeUrl} onChange={text('homeUrl')} error={errors.homeUrl}
          hint={__('Changing it republishes the whole site.', 'stillpress')} />
        <Field label={__('Time zone', 'stillpress')} name="timezone" required value={form.timezone} onChange={text('timezone')} error={errors.timezone} />
        <Field label={__('Posts per page', 'stillpress')} name="postsPerPage" type="number" min={1} max={100} required value={form.postsPerPage}
          onChange={count('postsPerPage')} error={errors.postsPerPage} />
        <Field label={__('Posts per feed', 'stillpress')} name="postsPerRss" type="number" min={1} max={100} required value={form.postsPerRss}
          onChange={count('postsPerRss')} error={errors.postsPerRss} />
        <fieldset>
          <legend>{__('Permalinks', 'stillpress')}</legend>
          <div className="field">
            <label htmlFor="preset">{__('Structure', 'stillpress')}</label>
            <select id="preset" value={preset} onChange={(e) => {
              setPreset(e.target.value);
              if (e.target.value !== CUSTOM) setForm({ ...form, permalinkStructure: e.target.value });
            }}>
              {presets().map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
              <option value={CUSTOM}>{__('Custom', 'stillpress')}</option>
            </select>
          </div>
          <Field label={__('Permalink structure', 'stillpress')} name="permalinkStructure" required value={form.permalinkStructure}
            onChange={(e) => { setPreset(CUSTOM); text('permalinkStructure')(e); }} error={errors.permalinkStructure}
            hint={__('A new structure applies only to posts published afterwards. Existing permalinks never change.', 'stillpress')} />
          <Field label={__('Category base', 'stillpress')} name="categoryBase" required value={form.categoryBase} onChange={text('categoryBase')}
            error={errors.categoryBase} hint={__('Changing a base redirects the old archive addresses and republishes the site.', 'stillpress')} />
          <Field label={__('Tag base', 'stillpress')} name="tagBase" required value={form.tagBase} onChange={text('tagBase')} error={errors.tagBase} />
        </fieldset>
        <fieldset>
          <legend>{__('Writing', 'stillpress')}</legend>
          <div className="field">
            <label htmlFor="default-category">{__('Default post category', 'stillpress')}</label>
            <select id="default-category" value={form.defaultCategory ?? ''} aria-invalid={errors.defaultCategory ? 'true' : undefined}
              aria-describedby={errors.defaultCategory ? 'default-category-error' : 'default-category-hint'}
              onChange={(e) => setForm({ ...form, defaultCategory: e.target.value ? Number(e.target.value) : null })}>
              <option value="">{__('Created on the first post (Uncategorized)', 'stillpress')}</option>
              {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
            {errors.defaultCategory
              ? <p id="default-category-error" className="error">{errors.defaultCategory}</p>
              : <p id="default-category-hint" className="hint">{__('Posts published without a category go here.', 'stillpress')}</p>}
          </div>
          <div className="field">
            <label htmlFor="comments">{__('Comments on new items', 'stillpress')}</label>
            <select id="comments" value={form.defaultCommentStatus}
              onChange={(e) => setForm({ ...form, defaultCommentStatus: e.target.value as SiteSettings['defaultCommentStatus'] })}>
              <option value="open">{__('Open', 'stillpress')}</option>
              <option value="closed">{__('Closed', 'stillpress')}</option>
            </select>
          </div>
        </fieldset>
        {message && <p role="alert" className="error">{message}</p>}
        {saved && <p role="status">{__('Saved. Changes that affect every page are being published.', 'stillpress')}</p>}
        <button type="submit" className="primary">{__('Save settings', 'stillpress')}</button>
      </form>
      <ResetSite title={siteTitle} />
    </Shell>
  );
}

const resetMessages = (): Record<string, string> => ({
  invalid_credentials: __('The password is wrong.', 'stillpress'),
  rate_limited: __('Too many wrong passwords. Wait a few minutes and try again.', 'stillpress'),
  import_running: __('An import is running. Reset after it ends.', 'stillpress'),
  files: __('The site was reset, but some files could not be removed. See the server log.', 'stillpress'),
});

function ResetSite({ title }: { title: string }) {
  const navigate = useNavigate();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function reset(e: FormEvent) {
    e.preventDefault();
    setMessage(null);
    setBusy(true);
    try {
      await api('/reset', { method: 'POST', body: { password, confirm } });
      navigate('/');
    } catch (err) {
      const f = err as ApiError;
      setMessage(f.field === 'confirm' ? __('Type the site title exactly.', 'stillpress')
        : f.error === 'csrf' ? f.message! : resetMessages()[f.error ?? ''] ?? __('Could not reset the site.', 'stillpress'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="danger" aria-labelledby="reset-heading">
      <hr />
      <h2 id="reset-heading">{__('Reset site', 'stillpress')}</h2>
      <p>
        {__('Deletes all posts, pages, media, users other than you, categories, redirects and settings. This cannot be undone; copy the data directory first if you may want it back.', 'stillpress')}
      </p>
      <form onSubmit={reset}>
        <Field label={__('Your password', 'stillpress')} name="reset-password" type="password" autoComplete="current-password" required
          value={password} onChange={(e) => setPassword(e.target.value)} />
        <Field label={sprintf(__('Type the site title (%s) to confirm', 'stillpress'), title)} name="reset-confirm" required autoComplete="off"
          value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        {message && <p role="alert" className="error">{message}</p>}
        <button type="submit" className="danger" disabled={busy || !password || confirm !== title}>{__('Reset site', 'stillpress')}</button>
      </form>
    </section>
  );
}

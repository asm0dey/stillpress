import { type ChangeEvent, type FormEvent, useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { type ApiError, type SiteSettings, type TermView, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';

const PRESETS: { label: string; value: string }[] = [
  { label: 'Day and name', value: '/%year%/%monthnum%/%day%/%postname%/' },
  { label: 'Month and name', value: '/%year%/%monthnum%/%postname%/' },
  { label: 'Post name', value: '/%postname%/' },
  { label: 'Numeric', value: '/archives/%post_id%/' },
];
const CUSTOM = 'custom';

const MESSAGES: Record<string, string> = {
  title: 'Enter a title.',
  language: 'Use a language code such as en or ru-RU.',
  homeUrl: 'Use a full address such as https://example.org, without a path.',
  timezone: 'Use a time zone such as Europe/Berlin.',
  postsPerPage: 'Use a number from 1 to 100.',
  postsPerRss: 'Use a number from 1 to 100.',
  permalinkStructure: 'Start and end with a slash, and include %postname% or %post_id%.',
  categoryBase: 'Use one word or slug.',
  tagBase: 'Use one word or slug, different from the category base.',
  defaultCategory: 'Choose one of the categories.',
  defaultCommentStatus: 'Choose open or closed.',
};

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
      setPreset(PRESETS.find((p) => p.value === s.permalinkStructure)?.value ?? CUSTOM);
    }, () => setMessage('Could not load the settings.'));
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
      if (f.field) setErrors({ [f.field]: MESSAGES[f.field] ?? 'Check this field.' });
      else setMessage(f.error === 'csrf' ? f.message! : 'Could not save the settings.');
    }
  }

  return (
    <Shell>
      <h1>Settings</h1>
      <form onSubmit={save}>
        <Field label="Site title" name="title" required value={form.title} onChange={text('title')} error={errors.title} />
        <Field label="Tagline" name="description" value={form.description} onChange={text('description')} />
        <Field label="Language" name="language" required value={form.language} onChange={text('language')} error={errors.language} />
        <Field label="Site address" name="homeUrl" required value={form.homeUrl} onChange={text('homeUrl')} error={errors.homeUrl}
          hint="Changing it republishes the whole site." />
        <Field label="Time zone" name="timezone" required value={form.timezone} onChange={text('timezone')} error={errors.timezone} />
        <Field label="Posts per page" name="postsPerPage" type="number" min={1} max={100} required value={form.postsPerPage}
          onChange={count('postsPerPage')} error={errors.postsPerPage} />
        <Field label="Posts per feed" name="postsPerRss" type="number" min={1} max={100} required value={form.postsPerRss}
          onChange={count('postsPerRss')} error={errors.postsPerRss} />
        <fieldset>
          <legend>Permalinks</legend>
          <div className="field">
            <label htmlFor="preset">Structure</label>
            <select id="preset" value={preset} onChange={(e) => {
              setPreset(e.target.value);
              if (e.target.value !== CUSTOM) setForm({ ...form, permalinkStructure: e.target.value });
            }}>
              {PRESETS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
              <option value={CUSTOM}>Custom</option>
            </select>
          </div>
          <Field label="Permalink structure" name="permalinkStructure" required value={form.permalinkStructure}
            onChange={(e) => { setPreset(CUSTOM); text('permalinkStructure')(e); }} error={errors.permalinkStructure}
            hint="A new structure applies only to posts published afterwards. Existing permalinks never change." />
          <Field label="Category base" name="categoryBase" required value={form.categoryBase} onChange={text('categoryBase')}
            error={errors.categoryBase} hint="Changing a base redirects the old archive addresses and republishes the site." />
          <Field label="Tag base" name="tagBase" required value={form.tagBase} onChange={text('tagBase')} error={errors.tagBase} />
        </fieldset>
        <fieldset>
          <legend>Writing</legend>
          <div className="field">
            <label htmlFor="default-category">Default post category</label>
            <select id="default-category" value={form.defaultCategory ?? ''} aria-invalid={errors.defaultCategory ? 'true' : undefined}
              aria-describedby={errors.defaultCategory ? 'default-category-error' : 'default-category-hint'}
              onChange={(e) => setForm({ ...form, defaultCategory: e.target.value ? Number(e.target.value) : null })}>
              <option value="">Created on the first post (Uncategorized)</option>
              {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
            {errors.defaultCategory
              ? <p id="default-category-error" className="error">{errors.defaultCategory}</p>
              : <p id="default-category-hint" className="hint">Posts published without a category go here.</p>}
          </div>
          <div className="field">
            <label htmlFor="comments">Comments on new items</label>
            <select id="comments" value={form.defaultCommentStatus}
              onChange={(e) => setForm({ ...form, defaultCommentStatus: e.target.value as SiteSettings['defaultCommentStatus'] })}>
              <option value="open">Open</option>
              <option value="closed">Closed</option>
            </select>
          </div>
        </fieldset>
        {message && <p role="alert" className="error">{message}</p>}
        {saved && <p role="status">Saved. Changes that affect every page are being published.</p>}
        <button type="submit" className="primary">Save settings</button>
      </form>
      <ResetSite title={siteTitle} />
    </Shell>
  );
}

const RESET_MESSAGES: Record<string, string> = {
  invalid_credentials: 'The password is wrong.',
  rate_limited: 'Too many wrong passwords. Wait a few minutes and try again.',
  import_running: 'An import is running. Reset after it ends.',
  files: 'The site was reset, but some files could not be removed. See the server log.',
};

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
      setMessage(f.field === 'confirm' ? 'Type the site title exactly.'
        : f.error === 'csrf' ? f.message! : RESET_MESSAGES[f.error ?? ''] ?? 'Could not reset the site.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="danger" aria-labelledby="reset-heading">
      <hr />
      <h2 id="reset-heading">Reset site</h2>
      <p>
        Deletes all posts, pages, media, users other than you, categories, redirects and settings. This cannot be
        undone; copy the data directory first if you may want it back.
      </p>
      <form onSubmit={reset}>
        <Field label="Your password" name="reset-password" type="password" autoComplete="current-password" required
          value={password} onChange={(e) => setPassword(e.target.value)} />
        <Field label={`Type the site title (${title}) to confirm`} name="reset-confirm" required autoComplete="off"
          value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        {message && <p role="alert" className="error">{message}</p>}
        <button type="submit" className="danger" disabled={busy || !password || confirm !== title}>Reset site</button>
      </form>
    </section>
  );
}

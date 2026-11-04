import { type ComponentProps, useId } from 'react';

/** A labelled input whose error text is tied to it with aria-describedby. */
type FieldProps = Omit<ComponentProps<'input'>, 'id'> & { label: string; error?: string; hint?: string };

export function Field({ label, error, hint, ...input }: FieldProps) {
  const id = useId();
  const described = [hint && id + '-hint', error && id + '-error'].filter(Boolean).join(' ') || undefined;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} aria-invalid={error ? 'true' : undefined} aria-describedby={described} {...input} />
      {hint && <p id={id + '-hint'} className="hint">{hint}</p>}
      {error && <p id={id + '-error'} className="error">{error}</p>}
    </div>
  );
}

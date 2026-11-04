import { type ComponentProps, useEffect, useId, useRef } from 'react';

/** A labelled input whose error text is tied to it with aria-describedby. */
type FieldProps = Omit<ComponentProps<'input'>, 'id'> & { label: string; error?: string; hint?: string };

/**
 * The error is announced (role="alert"), and when it appears the field takes focus if it is its form's first invalid
 * one, so a failed submit lands on the field to fix.
 */
export function Field({ label, error, hint, ...input }: FieldProps) {
  const id = useId();
  const ref = useRef<HTMLInputElement>(null);
  const described = [hint && id + '-hint', error && id + '-error'].filter(Boolean).join(' ') || undefined;
  useEffect(() => {
    const el = ref.current;
    if (error && el && (el.form ?? document).querySelector('[aria-invalid="true"]') === el) el.focus();
  }, [error]);
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input ref={ref} id={id} aria-invalid={error ? 'true' : undefined} aria-describedby={described} {...input} />
      {hint && <p id={id + '-hint'} className="hint">{hint}</p>}
      {error && <p id={id + '-error'} className="error" role="alert">{error}</p>}
    </div>
  );
}

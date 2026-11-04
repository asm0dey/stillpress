/**
 * Unsaved-edit bookkeeping for the editor. Every edit bumps a counter; a save or autosave remembers the count it
 * started from, so an edit made while the request is in flight stays unsaved. Block changes count only when the
 * serialized blocks differ from what was last loaded or saved: blocks that set attributes on their own when they
 * mount (an embed's preview) do not make a freshly opened post dirty.
 */
export class Edits {
  private count = 0;
  private savedAt = 0;
  private autosavedAt = 0;
  private last: string;

  constructor(private baseline: string) {
    this.last = baseline;
  }

  /** A title, sidebar or otherwise certain edit. */
  edit(): void {
    this.count++;
  }

  /**
   * The blocks changed; counts as an edit only when they serialize differently from the baseline and from the last
   * call: Gutenberg reports a change again (onInput, then onChange) when it becomes persistent.
   */
  blocks(serialized: string): void {
    if (serialized !== this.baseline && serialized !== this.last) this.count++;
    this.last = serialized;
  }

  get dirty(): boolean {
    return this.count !== this.savedAt;
  }

  get needsAutosave(): boolean {
    return this.count !== this.autosavedAt;
  }

  /** The mark a save or autosave passes back when it succeeds. */
  mark(): number {
    return this.count;
  }

  saved(mark: number, serialized: string): void {
    this.savedAt = mark;
    this.autosavedAt = Math.max(this.autosavedAt, mark);
    this.baseline = serialized;
    this.last = serialized;
  }

  autosaved(mark: number): void {
    this.autosavedAt = Math.max(this.autosavedAt, mark);
  }
}

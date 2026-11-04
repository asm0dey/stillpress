// @wordpress/block-library ships no type declarations
declare module '@wordpress/block-library' {
  export function registerCoreBlocks(): void;
}

// tinymce 4 ships no type declarations; only what classic.ts calls
declare module 'tinymce' {
  export interface TinyEditor {
    getContent(): string;
    remove(): void;
  }
  export interface TinyMCE {
    init(settings: Record<string, unknown>): Promise<TinyEditor[]>;
    get(id: string): TinyEditor | null;
    EditorManager: { overrideDefaults(settings: Record<string, unknown>): void };
  }
  const tinymce: TinyMCE;
  export default tinymce;
}
declare module 'tinymce/*';

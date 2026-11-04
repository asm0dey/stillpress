// @wordpress/block-library ships no type declarations
declare module '@wordpress/block-library' {
  export function registerCoreBlocks(): void;
}

// tinymce's theme, model, icon, skin and plugin modules only register themselves
declare module 'tinymce/*';

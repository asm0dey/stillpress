import { configDefaults, defineConfig, mergeConfig } from 'vitest/config';
import base from './vite.config.ts';

// Stryker's runs: the strings scan reads the source text, which the instrumentation rewrites
export default mergeConfig(base, defineConfig({
  test: { exclude: [...configDefaults.exclude, 'src/i18n/strings.test.ts'] },
}));

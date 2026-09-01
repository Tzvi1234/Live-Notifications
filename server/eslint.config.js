import js from '@eslint/js';
import tseslint from 'typescript-eslint';

/**
 * Flat config (ESLint 9+). `npm run lint` == `eslint .`.
 *
 * The type-aware rules are enabled one by one rather than via `recommendedTypeChecked`:
 * the handful below catch the mistakes this codebase can actually make, without the
 * `no-unsafe-*` noise that every `JSON.parse` of a provider payload would generate.
 */
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'coverage/**'] },

  js.configs.recommended,
  ...tseslint.configs.recommended,

  {
    files: ['src/**/*.ts'],
    languageOptions: {
      parserOptions: {
        // tsconfig.test.json, not tsconfig.json: the build project deliberately excludes
        // test files, and the type-aware rules need every linted file to be in a program.
        project: ['./tsconfig.test.json'],
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // The poller, the store and the FCM fan-out are async top to bottom. An unawaited
      // promise there is a dropped tick or a dropped batch that never surfaces as an error.
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': 'error',
      '@typescript-eslint/await-thenable': 'error',
      // `in-try-catch` only: a `return promise` inside a try block settles after the catch
      // has gone out of scope, so the handler never sees the rejection.
      '@typescript-eslint/return-await': ['error', 'in-try-catch'],

      // verbatimModuleSyntax emits any non-`type` import as a real runtime import, so a
      // type-only import written without the keyword becomes a needless module load.
      '@typescript-eslint/consistent-type-imports': [
        'error',
        { prefer: 'type-imports', fixStyle: 'separate-type-imports' },
      ],

      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],

      // src/logger.ts owns stdout and writes one JSON object per line; a stray console call
      // would break that format for Render's log parser.
      'no-console': 'error',
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      'prefer-const': 'error',
      'no-var': 'error',
    },
  },

  {
    files: ['src/**/*.test.ts', 'src/__tests__/**/*.ts'],
    rules: {
      // `test()` from node:test returns a promise that the runner owns; awaiting each call
      // at the top level of a suite is wrong, so the rule only produces noise here.
      '@typescript-eslint/no-floating-promises': 'off',
    },
  },

  // No TypeScript program covers this config file itself.
  {
    files: ['**/*.js'],
    ...tseslint.configs.disableTypeChecked,
  },
);

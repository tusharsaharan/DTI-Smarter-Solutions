import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Unlike the Emoji property, this does not mistake ordinary digits, # or * for emoji.
const emoji = /\p{Extended_Pictographic}|\p{Emoji_Presentation}|[\uFE0F\u20E3]/u;
const textExtensions = new Set([
  '.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.kt', '.kts', '.java', '.py',
  '.json', '.md', '.mdx', '.xml', '.svg', '.css', '.html', '.txt', '.yml', '.yaml', '.toml',
  '.gradle', '.properties', '.sh', '.ps1', '.bat',
]);
const pinnedVocabulary = 'apps/mobile/modules/attune-native/android/src/main/assets/notification_semantics/vocab.txt';

export function emojiLines(source) {
  return source.split(/\r?\n/).flatMap((line, index) => emoji.test(line) ? [index + 1] : []);
}

export function shouldCheck(path) {
  // Vocabulary line order is part of the encoder's token-ID contract, not UI decoration.
  if (path === pinnedVocabulary || /(^|\/)package-lock\.json$/.test(path)) return false;
  return textExtensions.has(extname(path));
}

function main() {
  const root = fileURLToPath(new URL('../', import.meta.url));
  const files = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], {
    cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024,
  }).split('\0').filter(Boolean);
  const failures = [];
  let checked = 0;
  for (const path of new Set(files)) {
    if (!shouldCheck(path)) continue;
    let source;
    try {
      source = readFileSync(resolve(root, path), 'utf8');
    } catch (error) {
      if (error.code === 'ENOENT') continue; // A tracked file may be pending deletion.
      throw error;
    }
    checked += 1;
    for (const line of emojiLines(source)) failures.push(`${path}:${line}`);
  }
  if (failures.length) {
    console.error(`Authored emoji found in ${failures.length} line(s):\n${failures.join('\n')}`);
    console.error('Use vector icons or plain text. Escape Unicode input fixtures instead of weakening input coverage.');
    process.exitCode = 1;
  } else {
    console.log(`No authored emoji in ${checked} text files (pinned model vocabulary and dependency lockfiles excluded).`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();

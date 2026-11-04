import assert from 'node:assert/strict';
import { test } from 'node:test';
import { emojiLines, shouldCheck } from './check-no-emoji.mjs';

test('detects default emoji, text-presentation symbols, flags and joined sequences', () => {
  for (const points of [[0x1f602], [0x1f3db], [0x2764], [0x1f1ee, 0x1f1f3], [0x1f469, 0x200d, 0x1f4bb]]) {
    assert.deepEqual(emojiLines(`Heading\n${String.fromCodePoint(...points)}\nBody`), [2]);
  }
});

test('detects emoji variation selectors and keycap sequences', () => {
  assert.deepEqual(emojiLines(`icon${String.fromCodePoint(0xfe0f)}`), [1]);
  assert.deepEqual(emojiLines(`1${String.fromCodePoint(0xfe0f, 0x20e3)}`), [1]);
});

test('allows regular typography, multilingual content and escaped input fixtures', () => {
  assert.deepEqual(emojiLines('01 # * 60%\r\nSense → Understand · हिन्दी 中文\n\\uD83D\\uDE00'), []);
});

test('checks all authored layers, including preview data, documentation and Kotlin', () => {
  for (const path of ['README.md', 'src/state/preview.ts', 'src/ui/Button.tsx', 'native/Service.kt', 'models/prepare.py', 'assets/icon.svg']) {
    assert.equal(shouldCheck(path), true, path);
  }
});

test('leaves binaries, lockfiles and only the exact pinned tokenizer vocabulary alone', () => {
  for (const path of ['frontend-files.zip', 'assets/encoder.onnx', 'assets/icon.png', 'package-lock.json',
    'apps/mobile/modules/attune-native/android/src/main/assets/notification_semantics/vocab.txt']) {
    assert.equal(shouldCheck(path), false, path);
  }
  assert.equal(shouldCheck('src/vocab.txt'), true);
});

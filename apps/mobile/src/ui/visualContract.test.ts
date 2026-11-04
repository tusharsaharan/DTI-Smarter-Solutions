import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { colors, control, featureOrder, fonts, type } from '@attune/design-tokens';

const c = colors.light;
const rgb = (hex: string) => [1, 3, 5].map((start) => parseInt(hex.slice(start, start + 2), 16));
const over = (fg: string, bg: string) => {
  const alpha = fg.length === 9 ? parseInt(fg.slice(7), 16) / 255 : 1;
  return rgb(fg).map((value, i) => value * alpha + rgb(bg)[i] * (1 - alpha));
};
const luminance = (channels: number[]) => channels.map((v) => {
  const channel = v / 255;
  return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
}).reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
const contrast = (fg: string, bg: string, backing = c.bg) => {
  const background = over(bg, backing);
  const alpha = fg.length === 9 ? parseInt(fg.slice(7), 16) / 255 : 1;
  const foreground = rgb(fg).map((v, i) => v * alpha + background[i] * (1 - alpha));
  const first = luminance(foreground);
  const second = luminance(background);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
};

describe('Attune light editorial visual contract', () => {
  it('keeps app, splash and system appearance in agreement', () => {
    const { expo } = JSON.parse(readFileSync('app.json', 'utf8'));
    expect(Object.keys(colors)).toEqual(['light']);
    expect(expo.userInterfaceStyle).toBe('light');
    expect(expo.backgroundColor).toBe(c.bg);
    expect(expo.android.adaptiveIcon.backgroundColor).toBe(c.bg);
    expect(expo.plugins.find((plugin: unknown[]) => Array.isArray(plugin) && plugin[0] === 'expo-splash-screen')[1].backgroundColor).toBe(c.bg);
    expect(expo.plugins).toContain('expo-system-ui');
  });

  it('uses Fahkwang for display, Inter for controls and mono for counters', () => {
    expect(type.display.fontFamily).toBe(fonts.displayLight);
    expect(type.bodyMedium.fontFamily).toBe('Inter_500Medium');
    expect(type.stat.fontFamily).toBe('JetBrainsMono_500Medium');
    const loader = readFileSync('src/app/_layout.tsx', 'utf8');
    expect(loader).toContain(fonts.displayLight);
    expect(loader).toContain(fonts.display);
    expect(loader).toContain('<StatusBar style="dark"');
  });

  it('bundles only the six font weights used by the app', () => {
    const loader = readFileSync('src/app/_layout.tsx', 'utf8');
    expect(loader).not.toMatch(/from '@expo-google-fonts\/[^/']+';/);
    expect([...loader.matchAll(/from '@expo-google-fonts\/[^/']+\/[^']+';/g)]).toHaveLength(6);
  });

  it('keeps every small text tone readable on all neutral and feature surfaces', () => {
    const surfaces = [c.bg, c.surface, c.surfaceRaised, c.surfacePressed, ...featureOrder.map((key) => c.feature[key].soft)];
    for (const bg of surfaces) {
      for (const fg of [c.text, c.textSecondary, c.textTertiary, c.danger, c.success, ...featureOrder.map((key) => c.feature[key].text)]) {
        expect(contrast(fg, bg), `${fg} on ${bg}`).toBeGreaterThanOrEqual(4.5);
      }
    }
    expect(contrast(c.onAccent, c.accent)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c.textInverse, c.accent)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c.text, c.surfacePressed)).toBeGreaterThanOrEqual(3);
    expect(contrast(c.onAccent, c.text)).toBeGreaterThanOrEqual(3);
  });

  it('keeps meaningful chart strokes and focus indicators above 3:1', () => {
    for (const bg of [c.bg, c.surface, c.surfaceRaised]) {
      for (const fg of [c.focus, ...Object.values(c.chart)]) expect(contrast(fg, bg)).toBeGreaterThanOrEqual(3);
    }
    expect(control.touchTarget).toBeGreaterThanOrEqual(48);
    expect(control.buttonHeight).toBeGreaterThanOrEqual(control.touchTarget);
    const keyboard = readFileSync('src/app/feature/keyboard.tsx', 'utf8');
    expect(keyboard).toContain('backgroundColor: m.color');
    expect(keyboard).not.toContain('opacity: m.o');
    const focus = readFileSync('src/app/feature/focus.tsx', 'utf8');
    expect(focus.match(/<Ring[^>]+>/)?.[0]).toContain('color={c.chart.primary}');
  });

  it('keeps feature identities in labels rather than competing neon palettes', () => {
    const first = c.feature[featureOrder[0]];
    for (const key of featureOrder) expect(c.feature[key]).toEqual(first);
  });

  it('does not author emoji in app screens and shared controls', () => {
    const check = (directory: string) => {
      for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const path = join(directory, entry.name);
        if (entry.isDirectory()) check(path);
        else if (entry.name.endsWith('.tsx')) expect(readFileSync(path, 'utf8'), path).not.toMatch(/\p{Emoji_Presentation}/u);
      }
    };
    for (const directory of ['src/app', 'src/components', 'src/ui']) check(directory);
  });
});

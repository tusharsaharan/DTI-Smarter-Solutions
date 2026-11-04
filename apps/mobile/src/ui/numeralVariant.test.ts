import { describe, expect, it } from 'vitest';
import { control, space, type } from '@attune/design-tokens';
import { numeralVariant, numeralWidth, structuredInputMinWidth } from './numeralVariant';

describe('readable mono counters', () => {
  it('retains display size for short counters', () => {
    expect(numeralVariant('123', 224, 1)).toBe('statLarge');
  });
  it('reduces long localized counters on narrow phones without truncating the value', () => {
    expect(numeralVariant('100,000', 224, 1)).toBe('stat');
    expect(numeralVariant('100,000', 224, 1.4)).toBe('statCompact');
    expect(numeralVariant('100,000', 560, 1.4)).toBe('statLarge');
  });
  it('handles long numbers and large font settings with a readable wrapping fallback', () => {
    expect(numeralVariant('9,007,199,254,740,991', 224, 1.4)).toBe('numeral');
    expect(numeralVariant('100,000', 224, 2)).toBe(numeralVariant('100,000', 224, 1.4));
  });
  it('lets compact summaries cap their hierarchy without enlarging small counts', () => {
    expect(numeralVariant('123', 224, 1, 'stat')).toBe('stat');
    expect(numeralVariant('100,000', 224, 1.4, 'stat')).toBe('statCompact');
    expect(numeralVariant('100,000', 560, NaN, 'stat')).toBe('stat');
  });
  it('budgets a full date or time plus input padding at the supported font scales', () => {
    for (const scale of [1, 1.4, 2, NaN]) {
      for (const value of ['09:45', '2026-11-03']) {
        const width = structuredInputMinWidth(value.length, scale);
        expect(width - space[4] * 2 - control.hairline * 2).toBeGreaterThanOrEqual(numeralWidth(value, scale, 'numeral'));
        expect(width).toBeGreaterThanOrEqual(space[10] * 2);
      }
    }
    const dateWidth = structuredInputMinWidth(10, 1.4);
    expect(dateWidth * 2 + space[3]).toBeGreaterThan(320 - space[6] * 2);
    expect(numeralWidth('100,000', 1.4)).toBeGreaterThan(128);
    expect(type.numeral.fontFamily).toBe('JetBrainsMono_500Medium');
  });
});

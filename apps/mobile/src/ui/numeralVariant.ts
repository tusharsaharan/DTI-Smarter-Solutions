import { control, space, type, type TypeVariant } from '@attune/design-tokens';

const effectiveScale = (fontScale: number) => Number.isFinite(fontScale) ? Math.min(Math.max(fontScale, 1), 1.4) : 1;

export function numeralWidth(value: string, fontScale: number, variant: 'statCompact' | 'numeral' = 'statCompact') {
  return Math.ceil(value.length * type[variant].fontSize * 0.65 * effectiveScale(fontScale));
}

/** Includes input padding and borders, so a full time or ISO date stays visible. */
export function structuredInputMinWidth(length: number, fontScale: number) {
  return Math.max(space[10] * 2, numeralWidth('0'.repeat(length), fontScale, 'numeral') + space[4] * 2 + control.hairline * 2);
}

/** Conservative mono glyph measure; keep whole values readable rather than clipping digits. */
export function numeralVariant(value: string, availableWidth: number, fontScale: number, maximum: 'statLarge' | 'stat' = 'statLarge'): TypeVariant {
  const scale = effectiveScale(fontScale);
  const variants = maximum === 'stat' ? ['stat', 'statCompact'] as const : ['statLarge', 'stat', 'statCompact'] as const;
  for (const variant of variants) {
    if (value.length * type[variant].fontSize * 0.65 * scale <= availableWidth) return variant;
  }
  // Truly large values may wrap at the regular numeral size. Never ellipsize the value.
  return 'numeral';
}

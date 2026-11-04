/**
 * Attune design tokens — single source of truth for the app (RN) and the keyboard theme (Kotlin, via tokens.json).
 * Never use raw colours/sizes in UI code; import from here.
 */

export type FeatureKey = 'notify' | 'keyboard' | 'focus' | 'attention' | 'volume';

/** Kairos-inspired warm neutrals. Feature names, not colour, carry identity. */
const sage = { base: '#A9C58F', soft: '#A9C58F29', deep: '#6E8A55', onLight: '#49613C' } as const;

export const palette = {
  ink: {
    0: '#173733',
    50: '#173733',
    100: '#23231F',
    150: '#314B43',
    200: '#49613C',
    300: '#4F6460',
    400: '#586B64',
    500: '#4F6460',
    600: '#52645E',
    700: '#7E8F8A',
    800: '#C9CEC2',
    900: '#DEDFD6',
    950: '#E7E6E2',
    1000: '#FBFAF7',
  },
  sage,
  // Compatibility aliases for existing screens. They deliberately share one hue.
  ember: sage,
  iris: sage,
  mint: sage,
  sky: sage,
  honey: sage,
  danger: '#9C433A',
  success: '#49613C',
} as const;

const featurePrimitive = {
  notify: palette.ember,
  keyboard: palette.iris,
  focus: palette.mint,
  attention: palette.sky,
  volume: palette.honey,
} as const;

export interface ThemeColors {
  bg: string;
  bgElevated: string;
  surface: string;
  surfaceRaised: string;
  surfacePressed: string;
  border: string;
  borderStrong: string;
  text: string;
  textSecondary: string;
  textTertiary: string;
  textInverse: string;
  accent: string;
  onAccent: string;
  danger: string;
  success: string;
  scrim: string;
  decorative: string;
  focus: string;
  chart: { primary: string; secondary: string; tertiary: string };
  feature: Record<FeatureKey, { base: string; soft: string; text: string }>;
}

export const colors: { light: ThemeColors } = {
  light: {
    bg: palette.ink[950],
    bgElevated: '#F0EFEB',
    surface: '#F0EFEB',
    surfaceRaised: palette.ink[1000],
    surfacePressed: '#DFE1D8',
    border: '#17373314',
    borderStrong: '#17373338',
    text: palette.ink[50],
    textSecondary: palette.ink[500],
    textTertiary: palette.ink[600],
    textInverse: palette.ink[950],
    accent: palette.ink[100],
    onAccent: '#D8E6C2',
    danger: palette.danger,
    success: palette.success,
    scrim: '#17373366',
    decorative: palette.ink[700],
    focus: palette.sage.onLight,
    chart: { primary: palette.sage.onLight, secondary: palette.sage.deep, tertiary: palette.ink[600] },
    feature: mapFeatures((p) => ({ base: p.base, soft: p.soft, text: p.onLight })),
  },
};

function mapFeatures(
  fn: (p: (typeof featurePrimitive)[FeatureKey]) => { base: string; soft: string; text: string },
): ThemeColors['feature'] {
  return {
    notify: fn(featurePrimitive.notify),
    keyboard: fn(featurePrimitive.keyboard),
    focus: fn(featurePrimitive.focus),
    attention: fn(featurePrimitive.attention),
    volume: fn(featurePrimitive.volume),
  };
}

/** Font family names as registered by the app's font loader. */
export const fonts = {
  display: 'Fahkwang_600SemiBold',
  displayBold: 'Fahkwang_600SemiBold',
  displayLight: 'Fahkwang_300Light',
  body: 'Inter_400Regular',
  bodyMedium: 'Inter_500Medium',
  bodySemibold: 'Inter_600SemiBold',
  mono: 'JetBrainsMono_500Medium',
} as const;

/** Type scale: [fontSize, lineHeight, letterSpacing]. Display sizes are tight-tracked. */
export const type = {
  hero: { fontFamily: fonts.displayLight, fontSize: 56, lineHeight: 64, letterSpacing: -1.8 },
  heroCompact: { fontFamily: fonts.displayLight, fontSize: 46, lineHeight: 54, letterSpacing: -1.4 },
  display: { fontFamily: fonts.displayLight, fontSize: 38, lineHeight: 46, letterSpacing: -1.1 },
  story: { fontFamily: fonts.displayLight, fontSize: 30, lineHeight: 39, letterSpacing: -0.6 },
  title1: { fontFamily: fonts.display, fontSize: 28, lineHeight: 36, letterSpacing: -0.7 },
  title2: { fontFamily: fonts.display, fontSize: 22, lineHeight: 30, letterSpacing: -0.4 },
  title3: { fontFamily: fonts.bodySemibold, fontSize: 18, lineHeight: 25, letterSpacing: -0.2 },
  indexTitle: { fontFamily: fonts.displayLight, fontSize: 26, lineHeight: 34, letterSpacing: -0.5 },
  body: { fontFamily: fonts.body, fontSize: 16, lineHeight: 24, letterSpacing: -0.1 },
  bodyMedium: { fontFamily: fonts.bodyMedium, fontSize: 16, lineHeight: 24, letterSpacing: -0.1 },
  callout: { fontFamily: fonts.body, fontSize: 14, lineHeight: 20, letterSpacing: 0 },
  calloutMedium: { fontFamily: fonts.bodyMedium, fontSize: 14, lineHeight: 20, letterSpacing: 0 },
  caption: { fontFamily: fonts.bodyMedium, fontSize: 12, lineHeight: 18, letterSpacing: 0.1 },
  overline: { fontFamily: fonts.bodyMedium, fontSize: 11, lineHeight: 16, letterSpacing: 1.4 },
  numeral: { fontFamily: fonts.mono, fontSize: 14, lineHeight: 18, letterSpacing: -0.2 },
  stat: { fontFamily: fonts.mono, fontSize: 40, lineHeight: 48, letterSpacing: -1.5 },
  statCompact: { fontFamily: fonts.mono, fontSize: 28, lineHeight: 36, letterSpacing: -0.7 },
  statLarge: { fontFamily: fonts.mono, fontSize: 64, lineHeight: 76, letterSpacing: -2.5 },
} as const;

export type TypeVariant = keyof typeof type;

/** 4pt spacing grid. */
export const space = { 0: 0, 1: 4, 2: 8, 3: 12, 4: 16, 5: 20, 6: 24, 7: 32, 8: 40, 9: 48, 10: 64, 11: 80 } as const;

export const radius = { xs: 8, sm: 12, md: 18, lg: 28, xl: 32, pill: 999 } as const;

/** Page gutters / layout constants. */
export const layout = {
  gutter: 24, cardPadding: 24, tabBarHeight: 64, maxContent: 560,
  compactWidth: 380, headerHeight: 56, headerCollapse: 64,
  sectionGap: 24, introTop: 40, introBottom: 32,
} as const;

/** Native controls and decorative geometry, shared rather than screen-specific literals. */
export const control = {
  touchTarget: 48, buttonHeight: 56, icon: 20, iconSmall: 18, iconLarge: 24,
  stroke: 1.8, hairline: 1, focusWidth: 2, dot: 6, hitSlop: 12,
  switch: { width: 52, height: 32, knob: 24, inset: 4 },
  tab: { width: 320, inset: 6, itemHeight: 50, icon: 18 },
  feature: { icon: 40, storyIcon: 56, wideHeight: 172, indexWidth: 28 },
  intro: { pathWidth: 100, pathHeight: 44, pathSegments: 120, dot: 7, trackWidth: 1, pageDot: 6, activeDot: 28, inactiveOpacity: 0.35 },
  chart: {
    stroke: 2, secondaryStroke: 1.5, ringStroke: 8, fillOpacity: 0.08,
    stackHeight: 8, compactStackHeight: 6,
    hourly: { height: 96, compactHeight: 88, gap: 3, radius: 3, minHeight: 4, emptyHeight: 3 },
    curve: { height: 110, padding: 8, stroke: 3, markerRadius: 4, haloRadius: 6, currentRadius: 5 },
  },
  editorial: { maskPadding: 4, titleTravel: 24, ruleHeight: 8, arrowRotation: 45, indexWidth: 28 },
} as const;

/** Motion: Reanimated spring configs + timing durations (ms). */
export const motion = {
  spring: {
    snappy: { damping: 22, stiffness: 320, mass: 0.8 },
    smooth: { damping: 26, stiffness: 180, mass: 1 },
    gentle: { damping: 30, stiffness: 110, mass: 1 },
    bouncy: { damping: 26, stiffness: 220, mass: 0.9 },
  },
  duration: { instant: 90, fast: 160, base: 240, slow: 420, ambient: 2400 },
  pressScale: 0.985,
  rowPressScale: 0.995,
  stagger: 30,
  maxStagger: 120,
  figureEightDuration: 12000,
  maxAmbientFrame: 64,
  hoverOpacity: 0.94,
  editorialDuration: 320,
} as const;

/** Feature metadata shared by app + keyboard + notifications. Ideas follow the DTI deck strictly. */
export const features: Record<
  FeatureKey,
  { index: number; name: string; short: string; tagline: string; persona: string; team: string }
> = {
  notify: {
    index: 1,
    name: 'Priority Inbox',
    short: 'Inbox',
    tagline: 'The one message that matters, never buried again.',
    persona: 'Varun',
    team: 'Aman · Prince',
  },
  keyboard: {
    index: 2,
    name: 'Attune Keyboard',
    short: 'Keyboard',
    tagline: 'Type the way you speak. Hinglish included.',
    persona: 'Aryan',
    team: 'Riya · Shreya',
  },
  focus: {
    index: 3,
    name: 'Smart Focus',
    short: 'Focus',
    tagline: 'Do Not Disturb that knows your moment, not just the clock.',
    persona: 'Lakshya',
    team: 'Rahul · Yash',
  },
  attention: {
    index: 4,
    name: 'True Screen Time',
    short: 'Attention',
    tagline: 'Count the time you actually looked, not the time the screen was on.',
    persona: 'Vivek',
    team: 'Tushar · Rakesh',
  },
  volume: {
    index: 5,
    name: 'Ambient Volume',
    short: 'Volume',
    tagline: 'Always audible, never the loudest phone in the library.',
    persona: 'Shivam',
    team: 'Omprakash · Varshith',
  },
};

export const featureOrder: FeatureKey[] = ['notify', 'keyboard', 'focus', 'attention', 'volume'];

export const tokens = { palette, colors, fonts, type, space, radius, layout, control, motion, features, featureOrder };
export default tokens;

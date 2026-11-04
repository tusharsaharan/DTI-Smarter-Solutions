import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { tokens, type ThemeColors, type FeatureKey } from '@attune/design-tokens';

export * from '@attune/design-tokens';

export interface Theme {
  scheme: 'light';
  c: ThemeColors;
  feature: (key: FeatureKey) => ThemeColors['feature'][FeatureKey];
}

const ThemeContext = createContext<Theme | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const value = useMemo<Theme>(() => {
    const c = tokens.colors.light;
    return { scheme: 'light', c, feature: (key) => c.feature[key] };
  }, []);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): Theme {
  const t = useContext(ThemeContext);
  if (!t) throw new Error('useTheme must be used inside <ThemeProvider>');
  return t;
}

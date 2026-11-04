import { useEffect, useState } from 'react';
import { AccessibilityInfo } from 'react-native';
import { useReducedMotion } from 'react-native-reanimated';

/** Reanimated's initial preference plus live Android accessibility changes. */
export function useMotionPreference() {
  const initial = useReducedMotion();
  const [reduced, setReduced] = useState(initial);

  useEffect(() => {
    let live = true;
    let changed = false;
    const subscription = AccessibilityInfo.addEventListener('reduceMotionChanged', (value) => {
      changed = true;
      setReduced(value);
    });
    AccessibilityInfo.isReduceMotionEnabled().then((value) => {
      if (live && !changed) setReduced(value);
    }).catch(() => {});
    return () => {
      live = false;
      subscription.remove();
    };
  }, []);

  return reduced;
}

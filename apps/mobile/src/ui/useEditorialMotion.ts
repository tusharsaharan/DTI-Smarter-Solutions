import { useEffect, useState } from 'react';
import { AppState, Platform } from 'react-native';
import { useIsFocused } from 'expo-router';
import { cancelAnimation, Easing, useSharedValue, withTiming } from 'react-native-reanimated';
import { motion } from '@/theme';
import { useMotionPreference } from './useMotionPreference';
import { startEditorialEntrance } from './editorialMotion';

/** Finite choreography only. Never starts a frame loop or survives route/background exit. */
export function useEditorialMotion() {
  const focused = useIsFocused();
  const reduced = useMotionPreference();
  const [foreground, setForeground] = useState(AppState.currentState === 'active');
  const progress = useSharedValue(1);
  const active = focused && foreground;

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => setForeground(state === 'active'));
    const blur = Platform.OS === 'android' ? AppState.addEventListener('blur', () => setForeground(false)) : null;
    const focus = Platform.OS === 'android' ? AppState.addEventListener('focus', () => setForeground(AppState.currentState === 'active')) : null;
    return () => { subscription.remove(); blur?.remove(); focus?.remove(); };
  }, []);

  useEffect(() => {
    return startEditorialEntrance(progress, { focused, foreground, reduced }, {
      cancel: () => cancelAnimation(progress),
      animate: () => withTiming(1, { duration: motion.editorialDuration, easing: Easing.out(Easing.cubic) }),
    });
  }, [focused, foreground, reduced, progress]);

  return { progress, active, reduced };
}

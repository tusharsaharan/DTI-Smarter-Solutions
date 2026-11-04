import { describe, expect, it } from 'vitest';
import { appDisplayName } from './appDisplayName';

describe('app display names', () => {
  it('presents known notification and usage packages without changing their identifiers', () => {
    expect(appDisplayName('com.whatsapp')).toBe('WhatsApp');
    expect(appDisplayName('com.whatsapp.w4b')).toBe('WhatsApp Business');
    expect(appDisplayName('org.telegram.messenger')).toBe('Telegram');
    expect(appDisplayName('com.google.android.apps.maps')).toBe('Google Maps');
  });

  it('preserves supplied labels and unknown identifiers rather than inventing a name', () => {
    for (const value of ['Attune', 'वॉट्सऐप', 'com.example.student_portal', 'constructor', 'toString']) {
      expect(appDisplayName(value)).toBe(value);
    }
    expect(appDisplayName(' WhatsApp ')).toBe('WhatsApp');
    expect(appDisplayName(' ')).toBe('App');
  });
});

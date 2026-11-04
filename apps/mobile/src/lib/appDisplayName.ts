/** Presentation aliases only. Package identifiers remain unchanged in native state. */
const names: Record<string, string> = {
  'com.whatsapp': 'WhatsApp',
  'com.whatsapp.w4b': 'WhatsApp Business',
  'com.instagram.android': 'Instagram',
  'org.telegram.messenger': 'Telegram',
  'com.myntra.android': 'Myntra',
  'com.google.android.gm': 'Gmail',
  'com.google.android.youtube': 'YouTube',
  'com.google.android.apps.maps': 'Google Maps',
  'com.android.deskclock': 'Clock',
  'com.google.android.deskclock': 'Clock',
  'com.microsoft.teams': 'Microsoft Teams',
};

export function appDisplayName(labelOrPackage: string): string {
  const value = labelOrPackage.trim();
  // Do not guess an unknown app's name from its package suffix.
  return Object.hasOwn(names, value) ? names[value] : value || 'App';
}

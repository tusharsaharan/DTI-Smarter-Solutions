const { withAppBuildGradle } = require('expo/config-plugins');

/** Keep java.time timetable/expiry handling available on Android 7 (API 24/25). */
module.exports = function withFocusDesugaring(config) {
  return withAppBuildGradle(config, (result) => {
    if (result.modResults.language !== 'groovy') throw new Error('Focus desugaring expects the Expo Groovy Android template.');
    const marker = '// Attune Focus: java.time on API 24/25';
    if (!result.modResults.contents.includes(marker)) {
      result.modResults.contents += `\n${marker}\nandroid {\n    compileOptions { coreLibraryDesugaringEnabled true }\n}\ndependencies {\n    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.3'\n}\n`;
    }
    return result;
  });
};

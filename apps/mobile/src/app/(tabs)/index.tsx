import { Redirect } from 'expo-router';
import { useAttune } from '@/state/store';
import { greeting } from '@/lib/format';
import { Screen } from '@/ui/Screen';
import { Reveal } from '@/ui/Reveal';
import { ContextCard } from '@/components/ContextCard';
import { FeatureIndex } from '@/components/FeatureIndex';
import { TAB_BAR_SPACE } from '@/components/TabBar';

const dateLine = () => new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });

export default function Today() {
  const hydrated = useAttune((s) => s.hydrated);
  const onboarded = useAttune((s) => s.onboarded);

  if (hydrated && !onboarded) return <Redirect href="/onboarding" />;

  return (
    <Screen title={greeting()} eyebrow={dateLine()} bottomInset={TAB_BAR_SPACE}>
      <Reveal index={0}>
        <ContextCard />
      </Reveal>

      <FeatureIndex />
    </Screen>
  );
}

import type { ReactNode } from 'react';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';

/** The prototype's wizard footer: Back on the left, the next step (label and arrow) on the right. */
export function WizardNav({
  onBack,
  next,
  disabled = false,
  pending = false,
  onNext,
  extra,
}: {
  onBack?: () => void;
  next?: ReactNode;
  disabled?: boolean;
  pending?: boolean;
  onNext?: () => void;
  extra?: ReactNode;
}) {
  return (
    <div className="row between mt-24">
      {onBack ? (
        <Button icon="arrowLeft" onClick={onBack}>
          Back
        </Button>
      ) : (
        <span />
      )}
      <div className="row gap-8">
        {extra}
        {next !== undefined && (
          <Button variant="primary" disabled={disabled} pending={pending} onClick={onNext}>
            {next}
            <Icon name="arrowRight" />
          </Button>
        )}
      </div>
    </div>
  );
}

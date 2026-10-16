import { useRouteError } from 'react-router';
import { ErrorState } from '@/components/feedback/EmptyState';

/** A page that failed to render or load: the error state with Retry (reloads the page). */
export function RouteErrorBoundary() {
  const error = useRouteError();
  console.error(error);
  return (
    <div className="content">
      <ErrorState
        what="this page"
        onRetry={() => {
          window.location.reload();
        }}
      />
    </div>
  );
}

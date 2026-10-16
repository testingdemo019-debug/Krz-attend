import { useEffect, useLayoutEffect } from 'react';

const setTitle = (title: string): void => {
  document.title = `${title} · KRZ Attend`;
};

/** "Dashboard · KRZ Attend", as the prototype sets document.title on every route (RouteGuard: the route's title). */
export function useRouteTitle(title: string): void {
  // A layout effect runs before every page's usePageTitle effect, so a page's own title (an employee's name) wins.
  useLayoutEffect(() => {
    setTitle(title);
  }, [title]);
}

/** A page's own title in place of its route's (the prototype's page.title). */
export function usePageTitle(title: string | undefined): void {
  useEffect(() => {
    if (title) setTitle(title);
  }, [title]);
}

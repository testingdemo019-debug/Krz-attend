/**
 * Every route (F8), lazy-loaded per feature, with the prototype's permission per page. Browser history (no "#"); the
 * prototype's query parameters are kept (?filter=, ?day=, ?item=, ?tab=, ?id=, ?employee=).
 */
import type { ComponentType } from 'react';
import { createBrowserRouter, Navigate, type RouteObject } from 'react-router';
import { AccessPage } from '@/features/auth/AccessPage';
import { SignInPage } from '@/features/auth/SignInPage';
import { SETTINGS_PAGES, settingsPageAllowed } from '@/lib/nav';
import { AuthGate } from './AuthGate';
import { RouteErrorBoundary } from './RouteErrorBoundary';
import { NotFound, RouteGuard, type RouteRule } from './RouteGuard';

/** Settings opens for anyone who may open one of its pages (the prototype's SETTINGS_PAGES.some(settingsPageAllowed)). */
const anySettingsPage: RouteRule = has => SETTINGS_PAGES.some(p => settingsPageAllowed(p, has));

/** A guarded, lazily loaded page. */
function page(
  path: string,
  title: string,
  rule: RouteRule | undefined,
  load: () => Promise<ComponentType>,
): RouteObject {
  return {
    path,
    lazy: async () => {
      const Page = await load();
      return {
        element: (
          <RouteGuard title={title} {...(rule === undefined ? {} : { rule })}>
            <Page />
          </RouteGuard>
        ),
      };
    },
  };
}

export const router = createBrowserRouter([
  { path: '/signin', element: <SignInPage /> },
  { path: '/access', element: <AccessPage /> },
  {
    path: '/',
    element: <AuthGate />,
    errorElement: <RouteErrorBoundary />,
    // Nothing while the first lazy page loads (the shell renders around it once /me is in).
    HydrateFallback: () => null,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      page('dashboard', 'Dashboard', undefined, () =>
        import('@/features/dashboard/DashboardPage').then(m => m.DashboardPage),
      ),
      page('live', 'Live View', 'live.view', () => import('@/features/live/LiveViewPage').then(m => m.LiveViewPage)),
      page('review', 'Review Queue', 'review.resolve', () =>
        import('@/features/review/ReviewQueuePage').then(m => m.ReviewQueuePage),
      ),
      page('events', 'Events', 'events.view', () => import('@/features/events/EventsPage').then(m => m.EventsPage)),
      page('attendance/today', 'Today', 'attendance.view.all', () =>
        import('@/features/attendance/TodayPage').then(m => m.TodayPage),
      ),
      page('attendance/log', 'Attendance log', 'attendance.view.all', () =>
        import('@/features/attendance/AttendanceLogPage').then(m => m.AttendanceLogPage),
      ),
      page('employees', 'Employees', 'people.view', () =>
        import('@/features/employees/EmployeesPage').then(m => m.EmployeesPage),
      ),
      page('enrol', 'Enrolment', 'people.enrol', () =>
        import('@/features/enrolment/EnrolmentPage').then(m => m.EnrolmentPage),
      ),
      page('employees/:id', 'Employee', 'people.view', () =>
        import('@/features/employees/EmployeeProfilePage').then(m => m.EmployeeProfilePage),
      ),
      page('org/locations', 'Locations', 'settings.attendance', () =>
        import('@/features/organisation/LocationsPage').then(m => m.LocationsPage),
      ),
      page('org/departments', 'Departments', 'settings.attendance', () =>
        import('@/features/organisation/DepartmentsPage').then(m => m.DepartmentsPage),
      ),
      page('org/shifts', 'Shifts', 'settings.attendance', () =>
        import('@/features/organisation/ShiftsPage').then(m => m.ShiftsPage),
      ),
      page('org/allocations', 'Shift allocation', 'settings.attendance', () =>
        import('@/features/organisation/AllocationsPage').then(m => m.AllocationsPage),
      ),
      page('org/holidays', 'Holidays', 'settings.attendance', () =>
        import('@/features/organisation/HolidaysPage').then(m => m.HolidaysPage),
      ),
      page('reports/coverage', 'Enrolment coverage', 'reports.view', () =>
        import('@/features/reports/CoveragePage').then(m => m.CoveragePage),
      ),
      page('reports/recognition', 'Recognition quality', 'reports.view', () =>
        import('@/features/reports/RecognitionPage').then(m => m.RecognitionPage),
      ),
      page('settings', 'Settings', anySettingsPage, () =>
        import('@/features/settings/SettingsRoute').then(m => m.SettingsIndex),
      ),
      page('settings/:page', 'Settings', anySettingsPage, () =>
        import('@/features/settings/SettingsRoute').then(m => m.SettingsRoute),
      ),
      { path: '*', element: <NotFound /> },
    ],
  },
]);

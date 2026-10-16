/**
 * The navigation model (the prototype's navModel and SETTINGS_PAGES), built from permissions only. Used by the
 * sidebar, the global search ("Pages") and the role editor's navigation preview. Organisation → Locations is new
 * (F6.13, F8).
 */
import type { IconName } from '@/components/icons/Icon';
import type { PermissionKey } from './permissions';

export interface SettingsPage {
  key: string;
  label: string;
  icon: IconName;
  permissions: readonly PermissionKey[];
}

export const SETTINGS_PAGES: readonly SettingsPage[] = [
  { key: 'general', label: 'General', icon: 'building', permissions: ['settings.attendance'] },
  { key: 'users', label: 'Users & roles', icon: 'shield', permissions: ['settings.roles'] },
  { key: 'signin', label: 'Microsoft sign-in', icon: 'key', permissions: ['settings.integrations'] },
  { key: 'cameras', label: 'Cameras', icon: 'cctv', permissions: ['settings.cameras'] },
  { key: 'recognition', label: 'Recognition', icon: 'sliders', permissions: ['settings.recognition'] },
  { key: 'rules', label: 'Attendance rules', icon: 'clock', permissions: ['settings.attendance'] },
  { key: 'enrolment', label: 'Enrolment & quality', icon: 'scanface', permissions: ['settings.recognition'] },
  { key: 'notifications', label: 'Notifications', icon: 'bell', permissions: ['settings.attendance'] },
  { key: 'hrms', label: 'KRZ HRMS', icon: 'link', permissions: ['settings.integrations'] },
  { key: 'privacy', label: 'Privacy & retention', icon: 'lock', permissions: ['settings.privacy'] },
  { key: 'audit', label: 'Audit log', icon: 'history', permissions: ['audit.view'] },
  { key: 'system', label: 'System', icon: 'server', permissions: ['settings.integrations'] },
];

type Has = (permission: PermissionKey) => boolean;

export function settingsPageAllowed(page: SettingsPage, has: Has): boolean {
  return page.permissions.some(has);
}

/** Which badge a nav item shows: open review items (urgent, red) or pending access requests (neutral). */
export type NavBadge = 'review' | 'accessRequests';

export interface NavItem {
  to: string;
  label: string;
  icon: IconName;
  /** Signed-in users always pass when missing. */
  allowed: (has: Has) => boolean;
  badge?: NavBadge;
}

export interface NavSection {
  section: string;
  items: readonly NavItem[];
}

const perm =
  (permission: PermissionKey) =>
  (has: Has): boolean =>
    has(permission);

export const NAV: readonly NavSection[] = [
  { section: 'Overview', items: [{ to: '/dashboard', label: 'Dashboard', icon: 'dashboard', allowed: () => true }] },
  {
    section: 'Monitoring',
    items: [
      { to: '/live', label: 'Live View', icon: 'video', allowed: perm('live.view') },
      { to: '/review', label: 'Review Queue', icon: 'review', allowed: perm('review.resolve'), badge: 'review' },
      { to: '/events', label: 'Events', icon: 'list', allowed: perm('events.view') },
    ],
  },
  {
    section: 'Attendance',
    items: [
      { to: '/attendance/today', label: 'Today', icon: 'clock', allowed: perm('attendance.view.all') },
      { to: '/attendance/log', label: 'Attendance log', icon: 'calendar', allowed: perm('attendance.view.all') },
    ],
  },
  {
    section: 'People',
    items: [
      { to: '/employees', label: 'Employees', icon: 'users', allowed: perm('people.view') },
      { to: '/enrol', label: 'Enrolment', icon: 'scanface', allowed: perm('people.enrol') },
    ],
  },
  {
    section: 'Organisation',
    items: [
      { to: '/org/locations', label: 'Locations', icon: 'pin', allowed: perm('settings.attendance') },
      { to: '/org/departments', label: 'Departments', icon: 'building', allowed: perm('settings.attendance') },
      { to: '/org/shifts', label: 'Shifts', icon: 'timer', allowed: perm('settings.attendance') },
      { to: '/org/allocations', label: 'Shift allocation', icon: 'route', allowed: perm('settings.attendance') },
      { to: '/org/holidays', label: 'Holidays', icon: 'sun', allowed: perm('settings.attendance') },
    ],
  },
  {
    section: 'Reports',
    items: [
      { to: '/reports/coverage', label: 'Enrolment coverage', icon: 'report', allowed: perm('reports.view') },
      { to: '/reports/recognition', label: 'Recognition quality', icon: 'activity', allowed: perm('reports.view') },
    ],
  },
  {
    section: 'Administration',
    items: [
      {
        to: '/settings',
        label: 'Settings',
        icon: 'settings',
        allowed: has => SETTINGS_PAGES.some(page => settingsPageAllowed(page, has)),
        badge: 'accessRequests',
      },
    ],
  },
];

/** The sections the user sees, each with only its allowed items; empty sections are left out. */
export function navFor(has: Has): NavSection[] {
  return NAV.map(s => ({ section: s.section, items: s.items.filter(i => i.allowed(has)) })).filter(s => s.items.length);
}

/** The role editor's preview: what a role with these permissions would see (the prototype's navigationPreview). */
export function navigationPreview(permissions: readonly PermissionKey[]): string[] {
  const has: Has = p => permissions.includes(p);
  const organisation = has('settings.attendance') ? ['Organisation'] : [];
  return [
    'Dashboard',
    ...(has('live.view') ? ['Live View'] : []),
    ...(has('review.resolve') ? ['Review Queue'] : []),
    ...(has('events.view') ? ['Events'] : []),
    ...(has('attendance.view.all') ? ['Today', 'Attendance log'] : []),
    ...(has('people.view') ? ['Employees'] : []),
    ...(has('people.enrol') ? ['Enrolment'] : []),
    ...organisation,
    ...(has('reports.view') ? ['Reports'] : []),
    ...(SETTINGS_PAGES.some(page => settingsPageAllowed(page, has)) ? ['Settings'] : []),
  ];
}

/** Whether a nav item is the current page (the prototype's updateNavActive). */
export function isActive(to: string, pathname: string): boolean {
  return pathname === to || pathname.startsWith(`${to}/`);
}

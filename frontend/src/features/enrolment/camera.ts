/**
 * The enrolment camera (F11: getUserMedia only on this page, only after a click, tracks stopped on leave) and the
 * prototype's camera error states.
 */
import type { IconName } from '@/components/icons/Icon';

export type CameraError = 'denied' | 'nocamera' | 'inuse' | 'insecure' | 'lowfps' | 'multi' | 'landscape' | 'generic';

/** The prototype's captureErrorHTML texts (without its simulated-camera way out, F5). */
export const CAMERA_ERRORS: Record<CameraError, readonly [IconName, string, string]> = {
  denied: [
    'lock',
    'Camera permission denied',
    'The browser blocked camera access. Click the camera icon in the address bar (or open site settings), allow the camera for this site, then try again. On a tablet, check Settings → Browser → Camera.',
  ],
  nocamera: ['camera', 'No camera found', 'Connect a webcam or use a device with a front camera, then try again.'],
  inuse: [
    'video',
    'Camera in use by another app',
    'Close Teams, Zoom or any other app using the camera, then try again.',
  ],
  insecure: [
    'lock',
    'Camera needs a secure connection',
    'Open the app over https:// or from http://localhost. Browsers block cameras on insecure pages.',
  ],
  lowfps: [
    'activity',
    'Low frame rate',
    'The camera is delivering fewer than 8 frames per second, so guidance may lag. Close other apps or move to better lighting, then try again.',
  ],
  multi: [
    'users',
    'More than one face in view',
    'Ensure only one face is in view. Ask others to step out of the frame.',
  ],
  landscape: ['rotate', 'Rotate your device', 'Hold the phone or tablet upright (portrait) to enrol.'],
  generic: ['alertCircle', 'Couldn’t start the camera', 'Something went wrong starting the camera. Try again.'],
};

/** Browsers leave navigator.mediaDevices out on insecure pages. */
function mediaDevices(): MediaDevices | undefined {
  return (navigator as { mediaDevices?: MediaDevices }).mediaDevices;
}

export function cameraAvailable(): boolean {
  return window.isSecureContext && typeof mediaDevices()?.getUserMedia === 'function';
}

/** The front camera (or the chosen one), 1280×720 ideally; the error kind when it can't start. */
export async function openCamera(deviceId: string): Promise<{ stream: MediaStream } | { error: CameraError }> {
  if (!cameraAvailable()) return { error: 'insecure' };
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: {
        ...(deviceId ? { deviceId: { exact: deviceId } } : {}),
        facingMode: 'user',
        width: { ideal: 1280 },
        height: { ideal: 720 },
      },
      audio: false,
    });
    return { stream };
  } catch (e) {
    const name = e instanceof DOMException ? e.name : '';
    if (name === 'NotAllowedError' || name === 'SecurityError') return { error: 'denied' };
    if (name === 'NotFoundError' || name === 'OverconstrainedError') return { error: 'nocamera' };
    if (name === 'NotReadableError') return { error: 'inuse' };
    return { error: 'generic' };
  }
}

export function stopCamera(stream: MediaStream | null): void {
  stream?.getTracks().forEach(track => {
    track.stop();
  });
}

/** The cameras to choose from, when there is more than one. */
export async function videoInputs(): Promise<MediaDeviceInfo[]> {
  if (typeof mediaDevices()?.enumerateDevices !== 'function') return [];
  try {
    const devices = (await navigator.mediaDevices.enumerateDevices()).filter(d => d.kind === 'videoinput');
    return devices.length > 1 ? devices : [];
  } catch {
    return [];
  }
}

/** The whole camera frame as a JPEG, as the server checks it (not mirrored). */
export function frameJpeg(video: HTMLVideoElement): Promise<Blob | null> {
  const canvas = document.createElement('canvas');
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  canvas.getContext('2d')?.drawImage(video, 0, 0);
  return new Promise(resolve => {
    canvas.toBlob(resolve, 'image/jpeg', 0.92);
  });
}

/**
 * The prototype's capture preview: a 240×240 square around the face, mirrored as the person saw it. Without a face
 * box, the middle of the frame.
 */
export function facePreview(
  video: HTMLVideoElement,
  box: { x0: number; x1: number; y0: number; y1: number } | null,
): string {
  const c = document.createElement('canvas');
  c.width = 240;
  c.height = 240;
  const x = c.getContext('2d');
  if (!x || !video.videoWidth) return '';
  const b = box ?? { x0: 0.35, x1: 0.65, y0: 0.2, y1: 0.7 };
  const w = (b.x1 - b.x0) * video.videoWidth * 1.7;
  const cx = (1 - (b.x0 + b.x1) / 2) * video.videoWidth;
  const cy = ((b.y0 + b.y1) / 2) * video.videoHeight;
  x.translate(240, 0);
  x.scale(-1, 1);
  x.drawImage(video, cx - w / 2, cy - w / 2, w, w, 0, 0, 240, 240);
  return c.toDataURL('image/jpeg', 0.85);
}

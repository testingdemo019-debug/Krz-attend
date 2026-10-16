/**
 * The prototype's ensureLandmarker, self-hosted (F1): MediaPipe Face Landmarker from /mediapipe/ (copied there by
 * scripts/mediapipe.mjs), GPU first then CPU, given up after 15 s. Loaded only when the enrolment camera starts, and
 * only ever to guide the person — the server checks every capture again.
 */
import type { FaceLandmarker } from '@mediapipe/tasks-vision';

export type Mode = 'VIDEO' | 'IMAGE';

const BASE = '/mediapipe';
const loading = new Map<Mode, Promise<FaceLandmarker>>();

async function create(mode: Mode): Promise<FaceLandmarker> {
  const vision = await import('@mediapipe/tasks-vision');
  const fileset = await vision.FilesetResolver.forVisionTasks(`${BASE}/wasm`);
  const make = (delegate: 'GPU' | 'CPU') =>
    vision.FaceLandmarker.createFromOptions(fileset, {
      baseOptions: { modelAssetPath: `${BASE}/face_landmarker.task`, delegate },
      runningMode: mode,
      numFaces: 3,
      outputFaceBlendshapes: true,
    });
  try {
    return await make('GPU');
  } catch {
    return await make('CPU');
  }
}

/** The landmarker for video frames or still photos; rejects when it can't load in 15 s. */
export function landmarker(mode: Mode): Promise<FaceLandmarker> {
  let pending = loading.get(mode);
  if (!pending) {
    pending = Promise.race([
      create(mode),
      new Promise<never>((_, reject) => {
        setTimeout(() => {
          reject(new Error('Face guidance took too long to load'));
        }, 15_000);
      }),
    ]);
    // A failed load may be retried with the next camera start.
    pending.catch(() => loading.delete(mode));
    loading.set(mode, pending);
  }
  return pending;
}

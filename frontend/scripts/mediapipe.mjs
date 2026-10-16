/**
 * Self-hosts MediaPipe (F1, no CDNs): copies the WASM files of the pinned @mediapipe/tasks-vision and the face
 * landmarker model into public/mediapipe/. The model is downloaded once from Google's MediaPipe model storage and
 * must match its SHA-256. Runs before `dev` and `build`; files already in place are kept.
 */
import { createHash } from 'node:crypto';
import { copyFile, mkdir, readdir, readFile, rename, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const MODEL_URL =
  'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task';
const MODEL_SHA256 = '64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'node_modules', '@mediapipe', 'tasks-vision', 'wasm');
const target = join(root, 'public', 'mediapipe');
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

await mkdir(join(target, 'wasm'), { recursive: true });
for (const name of await readdir(source)) {
  await copyFile(join(source, name), join(target, 'wasm', name));
}

const model = join(target, 'face_landmarker.task');
if (existsSync(model) && sha256(await readFile(model)) === MODEL_SHA256) {
  console.log('mediapipe: up to date');
} else {
  const response = await fetch(MODEL_URL);
  if (!response.ok) throw new Error(`mediapipe: model download failed (HTTP ${response.status})`);
  const bytes = Buffer.from(await response.arrayBuffer());
  const actual = sha256(bytes);
  if (actual !== MODEL_SHA256) throw new Error(`mediapipe: model checksum ${actual} is not ${MODEL_SHA256}`);
  await writeFile(`${model}.part`, bytes);
  await rename(`${model}.part`, model);
  console.log('mediapipe: model downloaded and verified');
}

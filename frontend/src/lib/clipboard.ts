import { toast } from '@/components/feedback/toast';

/** The prototype's copy action: copies the text and says whether it worked. */
export async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
    toast('Copied to clipboard', { type: 'success' });
  } catch {
    toast('Copy failed — select and copy manually', { type: 'warn' });
  }
}

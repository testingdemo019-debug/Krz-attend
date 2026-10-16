/** The sign-in form's checks, in the prototype's words (the server checks the domain again). */
import { z } from 'zod';

export function signInSchema(domain: string) {
  return z.object({
    email: z
      .string()
      .trim()
      .toLowerCase()
      .min(1, 'Enter your work email.')
      .regex(/^[^\s@]+@[^\s@]+\.[^\s@]+$/, `Enter a valid email address, like name@${domain}.`)
      .refine(
        value => !domain || value.endsWith(`@${domain}`),
        `Use your KRZ Technologies work account (name@${domain}).`,
      ),
  });
}

export type SignInInput = z.input<ReturnType<typeof signInSchema>>;
export type SignInValues = z.output<ReturnType<typeof signInSchema>>;

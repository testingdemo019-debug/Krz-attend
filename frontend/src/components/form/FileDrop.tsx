/**
 * The prototype's dropzone: click, Enter or Space opens the file chooser, files can be dropped on it, and it lights
 * up while a file is dragged over. The content (icon, text, chosen file) is the caller's.
 */
import { useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { cx } from '@/lib/cx';
import './FileDrop.module.css';

export function FileDrop({
  label,
  accept,
  multiple = false,
  onFiles,
  className,
  style,
  children,
}: {
  /** The accessible name ("Upload photos"). */
  label: string;
  accept: string;
  multiple?: boolean;
  onFiles: (files: File[]) => void;
  className?: string;
  style?: CSSProperties;
  children: ReactNode;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  return (
    <>
      <div
        className={cx('dropzone', over && 'over', className)}
        style={style}
        tabIndex={0}
        role="button"
        aria-label={label}
        onClick={event => {
          if (!(event.target as Element).closest('a,button')) input.current?.click();
        }}
        onKeyDown={event => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            input.current?.click();
          }
        }}
        onDragOver={event => {
          event.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => {
          setOver(false);
        }}
        onDrop={event => {
          event.preventDefault();
          setOver(false);
          onFiles(Array.from(event.dataTransfer.files));
        }}
      >
        {children}
      </div>
      <input
        ref={input}
        type="file"
        className="hidden"
        accept={accept}
        multiple={multiple}
        tabIndex={-1}
        aria-hidden="true"
        onChange={event => {
          onFiles(Array.from(event.target.files ?? []));
          event.target.value = '';
        }}
      />
    </>
  );
}

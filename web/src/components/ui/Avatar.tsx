import { User } from 'lucide-react';

type AvatarSize = 'sm' | 'md' | 'lg';

interface AvatarProps {
  src?: string | null;
  name?: string | null;
  size?: AvatarSize;
  className?: string;
}

const sizeStyles: Record<AvatarSize, { container: string; text: string; icon: string }> = {
  sm: { container: 'h-6 w-6', text: 'text-[10px]', icon: 'h-3 w-3' },
  md: { container: 'h-8 w-8', text: 'text-xs', icon: 'h-4 w-4' },
  lg: { container: 'h-10 w-10', text: 'text-sm', icon: 'h-5 w-5' },
};

function getInitials(name: string): string {
  return name
    .split(' ')
    .map((n) => n[0])
    .filter(Boolean)
    .slice(0, 2)
    .join('')
    .toUpperCase();
}

export function Avatar({ src, name, size = 'md', className = '' }: AvatarProps) {
  const styles = sizeStyles[size];

  if (src) {
    return (
      <img
        src={src}
        alt={name || 'Avatar'}
        loading="lazy"
        decoding="async"
        className={`rounded-full object-cover ${styles.container} ${className}`}
      />
    );
  }

  if (name) {
    return (
      <div
        className={`flex items-center justify-center rounded-full bg-[var(--color-accent)] font-medium text-white ${styles.container} ${styles.text} ${className}`}
      >
        {getInitials(name)}
      </div>
    );
  }

  return (
    <div
      className={`flex items-center justify-center rounded-full bg-[var(--color-bg-secondary)] text-[var(--color-text-secondary)] ${styles.container} ${className}`}
    >
      <User className={styles.icon} />
    </div>
  );
}

/**
 * Иконки Hiddenjem — тот же набор Lucide, что и в концепте, в одном начертании (штрих 1,75).
 *
 * Иконка всегда декоративна: смысл несёт подпись рядом, поэтому `aria-hidden` стоит здесь, а не
 * вспоминается в каждом месте вызова.
 */
import {
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  Bell,
  BookOpen,
  Bookmark,
  Building2,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Clock,
  Database,
  Download,
  ExternalLink,
  EyeOff,
  FileText,
  Flag,
  Info,
  Keyboard,
  Layers,
  Menu,
  Minus,
  Plus,
  Radar,
  RefreshCw,
  Search,
  SlidersHorizontal,
  TrendingUp,
  TriangleAlert,
  X,
  type LucideIcon,
} from 'lucide-react';

const ICONS = {
  radar: Radar,
  plus: Plus,
  file: FileText,
  search: Search,
  x: X,
  check: Check,
  'chevron-right': ChevronRight,
  'chevron-left': ChevronLeft,
  'chevron-down': ChevronDown,
  'arrow-right': ArrowRight,
  'arrow-left': ArrowLeft,
  'arrow-up': ArrowUp,
  'arrow-down': ArrowDown,
  external: ExternalLink,
  clock: Clock,
  bell: Bell,
  download: Download,
  bookmark: Bookmark,
  info: Info,
  alert: TriangleAlert,
  'eye-off': EyeOff,
  refresh: RefreshCw,
  sliders: SlidersHorizontal,
  keyboard: Keyboard,
  book: BookOpen,
  menu: Menu,
  trending: TrendingUp,
  building: Building2,
  layers: Layers,
  flag: Flag,
  minus: Minus,
  database: Database,
} satisfies Record<string, LucideIcon>;

export type HjIconName = keyof typeof ICONS | 'logo';

export function HjIcon({
  name,
  size = 20,
  className,
}: {
  name: HjIconName;
  size?: number;
  className?: string;
}): React.ReactElement {
  const cls = className ? `i ${className}` : 'i';
  if (name === 'logo') {
    // Огранённый камень — «hidden gem»: то, что продукт ищет, пока рынок этого не видит.
    return (
      <svg
        className={cls}
        width={size}
        height={size}
        viewBox="0 0 24 24"
        aria-hidden="true"
        focusable="false"
      >
        <path d="M6 3h12l4 6-10 13L2 9Z" />
        <path d="M11 3 8 9l4 13 4-13-3-6" />
        <path d="M2 9h20" />
      </svg>
    );
  }
  const Component = ICONS[name];
  return (
    <Component
      className={cls}
      size={size}
      strokeWidth={1.75}
      aria-hidden="true"
      focusable="false"
    />
  );
}

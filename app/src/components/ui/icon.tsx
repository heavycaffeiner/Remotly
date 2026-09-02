import * as React from 'react';
import { cssInterop } from 'nativewind';
import {
  ArrowDown,
  ArrowDownUp,
  Image as ImageIcon,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  Bot,
  Check,
  ChevronRight,
  Circle,
  CircleAlert,
  CircleCheck,
  CircleStop,
  ClipboardCopy,
  Clock,
  Cog,
  Copy,
  EllipsisVertical,
  Eye,
  EyeOff,
  File,
  FileDown,
  Folder,
  FolderOpen,
  HardDrive,
  Info,
  Keyboard,
  LassoSelect,
  LayoutDashboard,
  Link2Off,
  Minus,
  Network,
  Pencil,
  Plus,
  QrCode,
  RefreshCw,
  RotateCcw,
  ScanQrCode,
  Search,
  Server,
  ServerOff,
  Settings,
  Terminal,
  Trash2,
  TriangleAlert,
  Unplug,
  X,
  type LucideIcon,
} from 'lucide-react-native';

/**
 * The app's icon set.
 *
 * A closed map rather than a free-form string, so a typo is a type error and
 * the bundle only carries glyphs that are actually used.
 */
const ICONS = {
  'arrow-down': ArrowDown,
  'arrow-down-up': ArrowDownUp,
  image: ImageIcon,
  'arrow-left': ArrowLeft,
  'arrow-right': ArrowRight,
  'arrow-up': ArrowUp,
  bot: Bot,
  check: Check,
  'chevron-right': ChevronRight,
  circle: Circle,
  'circle-alert': CircleAlert,
  'circle-check': CircleCheck,
  'circle-stop': CircleStop,
  clipboard: ClipboardCopy,
  clock: Clock,
  cog: Cog,
  copy: Copy,
  eye: Eye,
  'eye-off': EyeOff,
  file: File,
  'file-down': FileDown,
  folder: Folder,
  'folder-open': FolderOpen,
  'hard-drive': HardDrive,
  info: Info,
  keyboard: Keyboard,
  'layout-dashboard': LayoutDashboard,
  'link-off': Link2Off,
  minus: Minus,
  more: EllipsisVertical,
  network: Network,
  pencil: Pencil,
  plus: Plus,
  'qr-code': QrCode,
  refresh: RefreshCw,
  'rotate-ccw': RotateCcw,
  scan: ScanQrCode,
  search: Search,
  select: LassoSelect,
  server: Server,
  'server-off': ServerOff,
  settings: Settings,
  terminal: Terminal,
  trash: Trash2,
  'triangle-alert': TriangleAlert,
  unplug: Unplug,
  x: X,
} satisfies Record<string, LucideIcon>;

export type IconName = keyof typeof ICONS;

// Lucide takes its color through a prop, so `className` has to be mapped onto
// it for Tailwind color classes to reach the glyph.
for (const component of Object.values(ICONS)) {
  cssInterop(component, {
    className: { target: 'style', nativeStyleToProp: { color: true } },
  });
}

interface IconProps {
  name: IconName;
  size?: number;
  /** Tailwind text color class. Colors come from the theme, never a hex. */
  className?: string;
}

/**
 * An icon glyph.
 *
 * Decorative: an icon never carries meaning alone here, so it is hidden from
 * assistive technology and the adjacent text does the work. Controls that show
 * only an icon carry their own accessibilityLabel.
 */
function Icon({ name, size = 20, className }: IconProps): React.ReactElement {
  const Glyph = ICONS[name];
  return (
    <Glyph
      size={size}
      className={className ?? 'text-foreground'}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    />
  );
}

export { Icon };

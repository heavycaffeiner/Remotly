// The icon names the app uses, drawn from Material Community Icons.
//
// Paper's Icon renders these from the MaterialCommunityIcons font. The set is a
// closed union so a typo in an icon prop is a compile error, not a tofu box.
// Add a name here only when the app actually uses it.

export type IconName =
  // Arrows and chevrons
  | 'arrow-down'
  | 'arrow-up'
  | 'arrow-left'
  | 'arrow-right'
  | 'arrow-up-down'
  | 'chevron-up'
  | 'chevron-down'
  | 'chevron-right'
  // Actions
  | 'plus'
  | 'minus'
  | 'close'
  | 'check'
  | 'dots-vertical'
  | 'menu'
  | 'refresh'
  | 'rotate-left'
  | 'play'
  | 'pause'
  | 'stop'
  | 'swap-horizontal'
  | 'open-in-new'
  // Editing
  | 'pencil'
  | 'delete'
  | 'content-copy'
  | 'clipboard'
  | 'select-all'
  // Search and display
  | 'magnify'
  | 'eye'
  | 'eye-off'
  | 'view-dashboard'
  // Files
  | 'folder'
  | 'folder-open'
  | 'file'
  | 'file-download'
  | 'download'
  | 'upload'
  | 'image'
  | 'harddisk'
  // Status
  | 'circle-outline'
  | 'alert-circle'
  | 'check-circle'
  | 'stop-circle'
  | 'alert'
  | 'information'
  | 'clock'
  | 'history'
  | 'timer'
  // System and connectivity
  | 'server'
  | 'server-off'
  | 'console'
  | 'web'
  | 'link-off'
  | 'key'
  | 'logout'
  | 'power-plug-off'
  | 'shield-key'
  | 'cog'
  | 'keyboard'
  | 'robot'
  | 'account'
  | 'home'
  // Scanning
  | 'qrcode'
  | 'qrcode-scan';

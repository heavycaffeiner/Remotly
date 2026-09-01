Bundled terminal font assets.

JetBrains Mono Nerd Font 3.5.1
  SIL Open Font License 1.1, see OFL-JetBrainsMono.txt
  https://github.com/ryanoasis/nerd-fonts
  Upstream JetBrains Mono: https://github.com/JetBrains/JetBrainsMono
  The four styled faces are subset to Latin, Greek, Cyrillic, punctuation,
  arrows, math, box drawing, and block elements.
  nerd_symbols.ttf carries the Nerd Font symbol ranges once, shared by every
  style, because those outlines do not differ between styles. It comes from
  the non-Mono build, whose icons keep their natural width; the renderer gives
  a wide symbol two columns rather than compressing it into one.

Noto Sans Mono CJK Sans2.004
  SIL Open Font License 1.1, see OFL-NotoSansCJK.txt
  https://github.com/notofonts/noto-cjk
  Korean, Japanese, and Simplified Chinese, Regular only, subset to jamo,
  kana, bopomofo, Han (URO, Extension A, compatibility ideographs), and
  fullwidth forms, with hinting removed. The three share a cmap but draw Han
  differently, so the active face follows the user's language rather than the
  codepoint. Bold is synthesized by the renderer.
  Han beyond Extension A falls back to the platform font.

Regenerate with app/android/terminal-native/fonts/prepare-fonts.sh.

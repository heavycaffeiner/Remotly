#ifndef REMOTLY_TERMINAL_THEME_H
#define REMOTLY_TERMINAL_THEME_H

#include <ghostty/vt.h>
#include <stdint.h>

static GhosttyColorRgb remotly_rgb(uint32_t value) {
  return (GhosttyColorRgb){(uint8_t)(value >> 16), (uint8_t)(value >> 8), (uint8_t)value};
}

static bool remotly_apply_theme(GhosttyTerminal terminal, const uint32_t colors[19]) {
  GhosttyColorRgb background = remotly_rgb(colors[0]);
  GhosttyColorRgb foreground = remotly_rgb(colors[1]);
  GhosttyColorRgb cursor = remotly_rgb(colors[2]);
  GhosttyColorRgb palette[256];
  ghostty_color_palette_default(palette);
  for (size_t i = 0; i < 16; i++) palette[i] = remotly_rgb(colors[i + 3]);
  return ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &background) == GHOSTTY_SUCCESS &&
      ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &foreground) == GHOSTTY_SUCCESS &&
      ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cursor) == GHOSTTY_SUCCESS &&
      ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, palette) == GHOSTTY_SUCCESS;
}

static bool remotly_color_scheme(GhosttyTerminal terminal, void *userdata, GhosttyColorScheme *out) {
  (void)userdata;
  GhosttyColorRgb background;
  if (ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND, &background) != GHOSTTY_SUCCESS)
    return false;
  *out = ghostty_color_perceived_luminance(&background) > 0.5 ? GHOSTTY_COLOR_SCHEME_LIGHT : GHOSTTY_COLOR_SCHEME_DARK;
  return true;
}

#endif

#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "terminal_theme.h"

static char reply[4096];
static size_t reply_len;
static void write_pty(GhosttyTerminal terminal, void *userdata, const uint8_t *data, size_t len) {
  (void)terminal; (void)userdata;
  assert(reply_len + len < sizeof(reply));
  memcpy(reply + reply_len, data, len);
  reply_len += len;
  reply[reply_len] = 0;
}
static void write_vt(GhosttyTerminal terminal, const char *text) {
  ghostty_terminal_vt_write(terminal, (const uint8_t *)text, strlen(text));
}
static bool same(GhosttyColorRgb a, GhosttyColorRgb b) { return a.r == b.r && a.g == b.g && a.b == b.b; }

int main(void) {
  GhosttyTerminal terminal = NULL;
  GhosttyRenderState render = NULL;
  assert(ghostty_terminal_new(NULL, &terminal, 20, 4) == GHOSTTY_SUCCESS);
  assert(ghostty_render_state_new(NULL, &render) == GHOSTTY_SUCCESS);
  assert(ghostty_render_state_update(render, terminal) == GHOSTTY_SUCCESS);
  GhosttyRenderStateColors original = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  assert(ghostty_render_state_get(render, GHOSTTY_RENDER_STATE_DATA_COLORS, &original) == GHOSTTY_SUCCESS);
  assert(same(original.background, remotly_rgb(0x000000)));
  assert(same(original.foreground, remotly_rgb(0xffffff)));
  const uint32_t classic[] = {0x1d1f21,0xcc6666,0xb5bd68,0xf0c674,0x81a2be,0xb294bb,0x8abeb7,0xc5c8c6,
      0x666666,0xd54e53,0xb9ca4a,0xe7c547,0x7aa6da,0xc397d8,0x70c0b1,0xeaeaea};
  for (size_t i = 0; i < 16; i++) assert(same(original.palette[i], remotly_rgb(classic[i])));
  ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY, (const void *)write_pty);
  ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_COLOR_SCHEME, (const void *)remotly_color_scheme);
  write_vt(terminal, "one\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix\x1b[?1000h");
  size_t total_before = 0;
  ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_TOTAL_ROWS, &total_before);
  write_vt(terminal, "\x1b]10;#112233\x07\x1b]12;#abcdef\x07\x1b]4;0;#123456\x07");
  uint32_t theme[19] = {0xfffdf6e3, 0xff586e75, 0xff556677};
  for (size_t i = 3; i < 19; i++) theme[i] = 0xff334455 + (uint32_t)i;
  assert(remotly_apply_theme(terminal, theme));
  GhosttyColorRgb rgb, palette[256];
  assert(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND, &rgb) == GHOSTTY_SUCCESS);
  assert(same(rgb, remotly_rgb(0x112233)));
  ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE, palette);
  assert(same(palette[0], remotly_rgb(0x123456)));
  assert(same(palette[15], remotly_rgb(theme[18])));
  for (size_t i = 16; i < 256; i++) assert(same(palette[i], original.palette[i]));
  reply_len = 0;
  write_vt(terminal, "\x1b]10;?\x07\x1b]11;?\x07\x1b]12;?\x07\x1b]4;0;?\x07\x1b[?996n");
  assert(strstr(reply, "rgb:1111/2222/3333"));
  assert(strstr(reply, "rgb:fdfd/f6f6/e3e3"));
  assert(strstr(reply, "rgb:abab/cdcd/efef"));
  assert(strstr(reply, "rgb:1212/3434/5656"));
  assert(strstr(reply, "\x1b[?997;2n"));
  ghostty_render_state_update(render, terminal);
  GhosttyRenderStateColors effective = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  ghostty_render_state_get(render, GHOSTTY_RENDER_STATE_DATA_COLORS, &effective);
  assert(effective.cursor_has_value && same(effective.cursor, remotly_rgb(0xabcdef)));
  write_vt(terminal, "\x1b]110\x07\x1b]112\x07\x1b]104;0\x07");
  ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND, &rgb);
  assert(same(rgb, remotly_rgb(theme[1])));
  ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLOR_CURSOR, &rgb);
  assert(same(rgb, remotly_rgb(theme[2])));
  ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE, palette);
  assert(same(palette[0], remotly_rgb(theme[3])));
  size_t total_after = 0;
  ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_TOTAL_ROWS, &total_after);
  assert(total_before == total_after);
  uint16_t cols = 0, rows = 0;
  bool tracking = false;
  ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLS, &cols);
  ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
  ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &tracking);
  assert(cols == 20 && rows == 4 && tracking);
  write_vt(terminal, "\r\x1b[38;2;10;20;30mX\x1b[0m한\x1b[7mI\x1b[0m");
  ghostty_render_state_update(render, terminal);
  GhosttyRenderStateRowIterator iterator = NULL;
  GhosttyRenderStateRowCells cells = NULL;
  ghostty_render_state_row_iterator_new(NULL, &iterator);
  ghostty_render_state_row_cells_new(NULL, &cells);
  ghostty_render_state_get(render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &iterator);
  for (int y = 0; y < 4; y++) assert(ghostty_render_state_row_iterator_next(iterator));
  ghostty_render_state_row_get(iterator, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &cells);
  ghostty_render_state_row_cells_select(cells, 0);
  ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &rgb);
  assert(same(rgb, (GhosttyColorRgb){10,20,30}));
  ghostty_render_state_row_cells_select(cells, 1);
  GhosttyCell raw = 0;
  GhosttyCellWide wide;
  ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw);
  ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
  assert(wide == GHOSTTY_CELL_WIDE_WIDE);
  ghostty_render_state_row_cells_select(cells, 3);
  GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
  ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
  assert(style.inverse);
  ghostty_render_state_row_cells_free(cells);
  ghostty_render_state_row_iterator_free(iterator);
  ghostty_render_state_free(render);
  ghostty_terminal_free(terminal);
  puts("theme tests passed");
  return 0;
}

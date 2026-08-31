// Host test for the terminal frame serialization path used by the Android
// renderer (M1-09). Validates that the render-state row/cell iteration yields
// the correct codepoints, colors, style flags, and wide-cell markers, so the
// Kotlin Canvas renderer can be trusted without a device.
#include <ghostty/vt.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_failures = 0;
static int g_checks = 0;
#define CHECK(cond, msg)                                 \
  do {                                                   \
    g_checks++;                                          \
    if (!(cond)) {                                       \
      g_failures++;                                      \
      fprintf(stderr, "FAIL %d: %s\n", __LINE__, msg);   \
    }                                                    \
  } while (0)

static GhosttyColorRgb resolve(GhosttyStyleColor c,
                               const GhosttyRenderStateColors *pal,
                               GhosttyColorRgb fallback) {
  switch (c.tag) {
    case GHOSTTY_STYLE_COLOR_RGB: return c.value.rgb;
    case GHOSTTY_STYLE_COLOR_PALETTE: return pal->palette[c.value.palette];
    default: return fallback;
  }
}

int main(void) {
  GhosttyTerminal t;
  CHECK(ghostty_terminal_new(NULL, &t, 40, 5) == GHOSTTY_SUCCESS, "new term");

  const char *content =
      "Hello, \033[1;32mworld\033[0m!\r\n" "plain line two\r\n"
      "\033[4munderlined\033[0m text\r\n"
      "wide: \xed\x95\x9c\xed\x95\x9c end\r\n";  // two CJK (wide) + ascii
  ghostty_terminal_vt_write(t, (const uint8_t *)content, strlen(content));

  GhosttyRenderState rs;
  CHECK(ghostty_render_state_new(NULL, &rs) == GHOSTTY_SUCCESS, "new rs");
  CHECK(ghostty_render_state_update(rs, t) == GHOSTTY_SUCCESS, "rs update");

  GhosttyRenderStateColors colors =
      GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  CHECK(ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_COLORS,
                                 &colors) == GHOSTTY_SUCCESS,
        "get colors");

  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
  CHECK(cols == 40 && rows == 5, "dims");

  GhosttyRenderStateRowIterator it;
  CHECK(ghostty_render_state_row_iterator_new(NULL, &it) == GHOSTTY_SUCCESS,
        "new it");
  CHECK(ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                                 &it) == GHOSTTY_SUCCESS,
        "bind it");
  GhosttyRenderStateRowCells cells;
  CHECK(ghostty_render_state_row_cells_new(NULL, &cells) == GHOSTTY_SUCCESS,
        "new cells");

  // Collect the first row's non-space text to verify "world" styling.
  int found_world_bold_green = 0;
  int found_underline = 0;
  int wide_heads = 0, wide_tails = 0;
  // The Kotlin reader addresses cells as y * cols + x, so a row that yields
  // any other number of cells shifts every row after it. The serializer pads
  // to cols for exactly this reason; this records what the iterator actually
  // produces so a change in that behavior is visible here.
  int short_rows = 0;
  uint16_t y = 0;
  while (ghostty_render_state_row_iterator_next(it)) {
    CHECK(ghostty_render_state_row_get(it, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                                       &cells) == GHOSTTY_SUCCESS,
          "row cells");
    uint16_t x = 0;
    while (ghostty_render_state_row_cells_next(cells)) {
      uint8_t utf8[64];
      GhosttyBuffer gb = {.ptr = utf8, .cap = sizeof(utf8), .len = 0};
      GhosttyResult gr = ghostty_render_state_row_cells_get(
          cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &gb);
      CHECK(gr == GHOSTTY_SUCCESS, "grapheme utf8");

      GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
      ghostty_render_state_row_cells_get(
          cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);

      GhosttyCell raw = 0;
      ghostty_render_state_row_cells_get(
          cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw);
      GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
      ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);

      if (wide == GHOSTTY_CELL_WIDE_WIDE) wide_heads++;
      if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL) wide_tails++;

      if (gb.len > 0) {
        // "Hello, world!" -> 'w' is at (x=7,y=0), bold, green (palette 2).
        if (y == 0 && x == 7 && style.bold &&
            style.fg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE &&
            style.fg_color.value.palette == 2 && gb.len == 1 &&
            utf8[0] == 'w')
          found_world_bold_green = 1;
        // "underlined text" -> 'u' is at (x=0,y=2), underlined.
        if (y == 2 && x == 0 && style.underline && gb.len == 1 &&
            utf8[0] == 'u')
          found_underline = 1;
      }
      x++;
    }
    if (x != cols) short_rows++;
    y++;
  }
  CHECK(y == rows, "iterated all rows");
  CHECK(short_rows == 0, "every row yields exactly cols cells");
  CHECK(found_world_bold_green, "world is bold+green");
  CHECK(found_underline, "underlined detected");
  // "한한" = two wide chars -> 2 heads and 2 spacer tails.
  CHECK(wide_heads == 2, "two wide heads");
  CHECK(wide_tails == 2, "two wide tails");

  ghostty_render_state_row_cells_free(cells);
  ghostty_render_state_row_iterator_free(it);
  ghostty_render_state_free(rs);
  ghostty_terminal_free(t);

  printf("%d checks, %d failures\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}

// Output after a scroll region is released, and after a resize.
//
// An agent UI draws a panel by setting a scroll region (DECSTBM), filling it,
// then releasing the region and continuing below. If the release or the
// bookkeeping around it is mishandled, later writes land back inside the panel
// and overwrite it, which reads as the panel being clipped and the text that
// followed it disappearing.
//
// Resizing while a region is set is the same hazard: the region is expressed in
// rows, and a terminal that keeps a stale region writes into the wrong band.

#include <assert.h>
#include <stdio.h>
#include <string.h>

#include <ghostty/vt.h>

static int failures = 0;

static void ws(GhosttyTerminal t, const char *s) {
  ghostty_terminal_vt_write(t, (const uint8_t *)s, strlen(s));
}

/** Reads one viewport row as text. */
static void read_row(GhosttyTerminal t, GhosttyRenderState rs,
                     GhosttyRenderStateRowIterator it, uint16_t want,
                     char *out, size_t cap) {
  out[0] = '\0';
  if (ghostty_render_state_update(rs, t) != GHOSTTY_SUCCESS) return;
  if (ghostty_render_state_get(rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                               &it) != GHOSTTY_SUCCESS)
    return;
  uint16_t y = 0;
  size_t at = 0;
  while (ghostty_render_state_row_iterator_next(it)) {
    if (y == want) {
      GhosttyRenderStateRowCells cells;
      if (ghostty_render_state_row_cells_new(NULL, &cells) != GHOSTTY_SUCCESS)
        return;
      if (ghostty_render_state_row_get(it, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                                       &cells) == GHOSTTY_SUCCESS) {
        while (ghostty_render_state_row_cells_next(cells) && at + 8 < cap) {
          uint8_t utf8[16];
          GhosttyBuffer gb = {.ptr = utf8, .cap = sizeof(utf8), .len = 0};
          if (ghostty_render_state_row_cells_get(
                  cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8,
                  &gb) == GHOSTTY_SUCCESS &&
              gb.len > 0 && gb.len < 8) {
            memcpy(out + at, utf8, gb.len);
            at += gb.len;
          } else {
            out[at++] = ' ';
          }
        }
      }
      ghostty_render_state_row_cells_free(cells);
      break;
    }
    y++;
  }
  // Trim the trailing blanks so a comparison is about content, not padding.
  while (at > 0 && out[at - 1] == ' ') at--;
  out[at] = '\0';
}

static void expect_row(GhosttyTerminal t, GhosttyRenderState rs,
                       GhosttyRenderStateRowIterator it, uint16_t row,
                       const char *want, const char *what) {
  char got[256];
  read_row(t, rs, it, row, got, sizeof(got));
  if (strcmp(got, want) == 0) {
    printf("  ok   %-38s row %u\n", what, row);
  } else {
    printf("  FAIL %-38s row %u got \"%s\" want \"%s\"\n", what, row, got, want);
    failures++;
  }
}

int main(void) {
  GhosttyTerminal t;
  GhosttyRenderState rs;
  GhosttyRenderStateRowIterator it;
  assert(ghostty_terminal_new(NULL, &t, 40, 10) == GHOSTTY_SUCCESS);
  assert(ghostty_render_state_new(NULL, &rs) == GHOSTTY_SUCCESS);
  assert(ghostty_render_state_row_iterator_new(NULL, &it) == GHOSTTY_SUCCESS);

  printf("output after a scroll region is released\n");

  // A panel occupying rows 1-4, then the region is released and more is
  // printed. The panel must survive and the new text must land below it.
  ws(t, "\x1b[2J\x1b[1;1H");
  ws(t, "PANEL-TOP\r\n");
  ws(t, "\x1b[2;5r");          // region rows 2-5
  ws(t, "\x1b[2;1Hinside-a\r\n");
  ws(t, "inside-b");
  ws(t, "\x1b[r");             // release the region
  ws(t, "\x1b[7;1Hafter-release");

  expect_row(t, rs, it, 0, "PANEL-TOP", "panel survives the region");
  expect_row(t, rs, it, 6, "after-release", "text lands below the panel");

  printf("a full-width write after the region\n");
  ws(t, "\x1b[2J\x1b[1;1H");
  ws(t, "\x1b[1;3r");
  ws(t, "\x1b[1;1Hone\r\ntwo\r\nthree");
  ws(t, "\x1b[r");
  ws(t, "\x1b[5;1Hbelow");
  expect_row(t, rs, it, 4, "below", "absolute move outside the old region");

  printf("resize while a region is set\n");
  // The region is in rows. Growing the screen must not leave a write aimed at
  // the band the region used to cover.
  ws(t, "\x1b[2J\x1b[1;1H");
  ws(t, "\x1b[2;4r");
  ws(t, "\x1b[2;1Hkeep-me");
  ghostty_terminal_resize(t, 40, 20, 10, 20);
  ws(t, "\x1b[r");
  ws(t, "\x1b[10;1Hafter-resize");
  expect_row(t, rs, it, 9, "after-resize", "write after a resize lands right");

  printf("scrolling inside a region leaves the rest alone\n");
  ws(t, "\x1b[2J\x1b[1;1H");
  ghostty_terminal_resize(t, 40, 10, 10, 20);
  ws(t, "\x1b[1;1Hheader");
  ws(t, "\x1b[3;6r");
  // Fill past the region so it scrolls, which is what a filling panel does.
  for (int i = 1; i <= 8; i++) {
    char line[32];
    snprintf(line, sizeof(line), "\x1b[6;1Hrow%d\r\n", i);
    ws(t, line);
  }
  ws(t, "\x1b[r");
  expect_row(t, rs, it, 0, "header", "content above the region is untouched");

  ghostty_render_state_row_iterator_free(it);
  ghostty_render_state_free(rs);
  ghostty_terminal_free(t);

  if (failures > 0) {
    printf("\n%d failure(s)\n", failures);
    return 1;
  }
  printf("\nall ok\n");
  return 0;
}

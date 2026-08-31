// Does a frame read twice in a row report the same screen?
//
// The Android renderer serializes a fresh frame on every repaint from one
// long-lived render state and one reused row iterator. If a second read
// against unchanged content does not match the first, the view draws a stale
// screen and only catches up when something writes again, which is felt as
// rendering running a beat behind and as scrollback that will not go away.
#include <ghostty/vt.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_failures = 0;
static int g_checks = 0;
#define CHECK(cond, msg)                               \
  do {                                                 \
    g_checks++;                                        \
    if (!(cond)) {                                     \
      g_failures++;                                    \
      fprintf(stderr, "FAIL %d: %s\n", __LINE__, msg); \
    }                                                  \
  } while (0)

// Mirrors the serializer in remotly_terminal.c closely enough to expose the
// same iterator behavior: one render state and one row iterator, reused.
typedef struct {
  GhosttyTerminal t;
  GhosttyRenderState rs;
  GhosttyRenderStateRowIterator it;
  GhosttyRenderStateRowCells cells;
} Term;

// Serializes the visible grid to `out` as "text-per-cell", returning length.
static size_t snapshot(Term *m, char *out, size_t cap) {
  if (ghostty_render_state_update(m->rs, m->t) != GHOSTTY_SUCCESS) return 0;

  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(m->rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(m->rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);

  if (ghostty_render_state_get(m->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                               &m->it) != GHOSTTY_SUCCESS)
    return 0;

  size_t o = 0;
  uint16_t y = 0;
  while (y < rows && ghostty_render_state_row_iterator_next(m->it)) {
    if (ghostty_render_state_row_get(
            m->it, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
            &m->cells) != GHOSTTY_SUCCESS) {
      y++;
      continue;
    }
    uint16_t x = 0;
    while (x < cols && ghostty_render_state_row_cells_next(m->cells)) {
      uint8_t utf8[64];
      GhosttyBuffer gb = {.ptr = utf8, .cap = sizeof(utf8), .len = 0};
      if (ghostty_render_state_row_cells_get(
              m->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8,
              &gb) != GHOSTTY_SUCCESS)
        gb.len = 0;
      if (gb.len == 0) {
        if (o + 1 < cap) out[o++] = ' ';
      } else {
        for (size_t i = 0; i < gb.len && o + 1 < cap; i++) out[o++] = utf8[i];
      }
      x++;
    }
    if (o + 1 < cap) out[o++] = '\n';
    y++;
  }
  out[o < cap ? o : cap - 1] = 0;
  return o;
}

int main(void) {
  Term m;
  memset(&m, 0, sizeof(m));
  CHECK(ghostty_terminal_new(NULL, &m.t, 20, 4) == GHOSTTY_SUCCESS, "new term");
  CHECK(ghostty_render_state_new(NULL, &m.rs) == GHOSTTY_SUCCESS, "new rs");
  CHECK(ghostty_render_state_row_iterator_new(NULL, &m.it) == GHOSTTY_SUCCESS,
        "new it");
  CHECK(ghostty_render_state_row_cells_new(NULL, &m.cells) == GHOSTTY_SUCCESS,
        "new cells");

  const char *first = "alpha\r\nbravo\r\n";
  ghostty_terminal_vt_write(m.t, (const uint8_t *)first, strlen(first));

  static char a[8192], b[8192], c[8192];
  size_t la = snapshot(&m, a, sizeof(a));
  size_t lb = snapshot(&m, b, sizeof(b));

  CHECK(la > 0, "first snapshot is non-empty");
  CHECK(la == lb && memcmp(a, b, la) == 0,
        "two reads of unchanged content agree");
  if (la != lb || memcmp(a, b, la) != 0) {
    fprintf(stderr, "--- read 1 (%zu bytes)\n%s\n--- read 2 (%zu bytes)\n%s\n",
            la, a, lb, b);
  }

  // Write more, then read twice again. The first read after a write must
  // already carry it: that is exactly the "one beat behind" complaint.
  const char *second = "charlie\r\n";
  ghostty_terminal_vt_write(m.t, (const uint8_t *)second, strlen(second));
  size_t lc = snapshot(&m, c, sizeof(c));
  CHECK(lc > 0 && strstr(c, "charlie") != NULL,
        "the first read after a write already shows it");
  if (strstr(c, "charlie") == NULL) {
    fprintf(stderr, "--- read after write\n%s\n", c);
  }

  ghostty_render_state_row_cells_free(m.cells);
  ghostty_render_state_row_iterator_free(m.it);
  ghostty_render_state_free(m.rs);
  ghostty_terminal_free(m.t);

  printf("%d checks, %d failures\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}

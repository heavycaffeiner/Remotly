// The bottom-anchored overlay idiom.
//
// pico keeps a live block on the last rows of the screen and pushes history up
// by printing a newline at the last row, then repaints the block with absolute
// cursor moves. Nothing here is specific to pico: it is what any bottom-docked
// TUI on the primary screen does.
//
// This checks the two things that idiom needs from the terminal:
//   1. a newline on the last row scrolls, rather than staying put
//   2. after scrolling, an absolute move to a bottom row lands on that row
//
// Build and run through run-overlay-test.sh.

#include <assert.h>
#include <stdio.h>
#include <string.h>

#include <ghostty/vt.h>

#define ROWS 24
#define COLS 80

static int failures = 0;

static void write_str(GhosttyTerminal t, const char *s) {
  ghostty_terminal_vt_write(t, (const uint8_t *)s, strlen(s));
}

static void check(const char *what, int ok) {
  printf(ok ? "  ok   %s\n" : "  FAIL %s\n", what);
  if (!ok) failures++;
}

static uint16_t cursor_y(GhosttyTerminal t) {
  uint16_t y = 0;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &y);
  return y;
}

int main(void) {
  GhosttyTerminal t;
  assert(ghostty_terminal_new(NULL, &t, COLS, ROWS) == GHOSTTY_SUCCESS);

  // Fill the screen so the scroll has something to push into scrollback.
  for (int i = 1; i <= ROWS; i++) {
    char line[64];
    snprintf(line, sizeof(line), "line %d\r\n", i);
    if (i < ROWS) write_str(t, line);
  }

  printf("newline at the last row\n");
  // Absolute move to the last row (1-based in CUP), then a newline.
  write_str(t, "\x1b[24;1H");
  check("cursor is on the last row", cursor_y(t) == ROWS - 1);
  write_str(t, "\r\n");
  // The screen scrolled, so the cursor stays on the last row rather than
  // moving past it.
  check("a newline there scrolls and stays", cursor_y(t) == ROWS - 1);

  printf("absolute moves into the block\n");
  write_str(t, "\x1b[22;1H");
  check("row 22 is reachable", cursor_y(t) == 21);
  write_str(t, "\x1b[23;1H");
  check("row 23 is reachable", cursor_y(t) == 22);
  write_str(t, "\x1b[24;1H");
  check("row 24 is reachable", cursor_y(t) == 23);

  printf("the viewport stays on the active area\n");
  // What the overlay repaint looks like: move, clear, draw, three rows.
  for (int i = 0; i < 40; i++) {
    write_str(t, "\x1b[24;1H\r\n");
    write_str(t, "\x1b[22;1H\x1b[2Kblock row 1");
    write_str(t, "\x1b[23;1H\x1b[2Kblock row 2");
    write_str(t, "\x1b[24;1H\x1b[2Kblock row 3");
  }
  GhosttyTerminalScrollbar bar;
  int got = ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar) ==
            GHOSTTY_SUCCESS;
  check("scrollbar readable", got);
  if (got) {
    // offset + len == total means the viewport is at the bottom, which is
    // where a terminal that is following output must be.
    printf("       total=%llu offset=%llu len=%llu\n",
           (unsigned long long)bar.total, (unsigned long long)bar.offset,
           (unsigned long long)bar.len);
    check("viewport is at the active area", bar.offset + bar.len == bar.total);
  }

  ghostty_terminal_free(t);

  if (failures > 0) {
    printf("\n%d failure(s)\n", failures);
    return 1;
  }
  printf("\nall ok\n");
  return 0;
}

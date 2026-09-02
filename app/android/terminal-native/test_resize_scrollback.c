// What a resize does to the viewport when there is scrollback above it.
//
// A full-screen application (Claude Code, Codex, Pi) draws on the primary
// screen and leaves its history in the scrollback. The reported symptom is
// that a keyboard resize sends the view to the top of that history and then
// scrolls back down, which would mean the viewport is not staying on the
// active area across a resize.
//
// This measures it rather than assuming: it writes more content than fits,
// notes where the viewport sits, resizes, and looks again.
#include <ghostty/vt.h>

#include <stdint.h>
#include <stdio.h>
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

// Reads the scrollbar geometry the view uses to place its thumb: total rows,
// the viewport's offset into them, and how many are visible.
static void scrollbar(GhosttyTerminal t, uint64_t *total, uint64_t *offset,
                      uint64_t *visible) {
  GhosttyTerminalScrollbar sb;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb);
  *total = sb.total;
  *offset = sb.offset;
  *visible = sb.len;
}

int main(void) {
  GhosttyTerminal t;
  CHECK(ghostty_terminal_new(NULL, &t, 80, 24) == GHOSTTY_SUCCESS, "new term");
  size_t cap = 4u * 1024 * 1024;
  ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &cap);

  // Well past one screen, so there is real history above the viewport.
  char line[64];
  for (int i = 0; i < 200; i++) {
    int n = snprintf(line, sizeof(line), "line %d\r\n", i);
    ghostty_terminal_vt_write(t, (const uint8_t *)line, (size_t)n);
  }

  uint64_t total = 0, offset = 0, visible = 0;
  scrollbar(t, &total, &offset, &visible);
  printf("before resize: total=%llu offset=%llu visible=%llu\n",
         (unsigned long long)total, (unsigned long long)offset,
         (unsigned long long)visible);
  // The viewport is at the bottom: the offset is as far down as it goes.
  CHECK(offset + visible == total, "viewport sits at the bottom before resize");

  // The keyboard opening is a height change, which is what the app reports.
  CHECK(ghostty_terminal_resize(t, 80, 12, 10, 20) == GHOSTTY_SUCCESS, "shrink");
  scrollbar(t, &total, &offset, &visible);
  printf("after shrink:  total=%llu offset=%llu visible=%llu\n",
         (unsigned long long)total, (unsigned long long)offset,
         (unsigned long long)visible);
  CHECK(offset + visible == total, "viewport stays at the bottom after shrink");

  // And closing it again.
  CHECK(ghostty_terminal_resize(t, 80, 24, 10, 20) == GHOSTTY_SUCCESS, "grow");
  scrollbar(t, &total, &offset, &visible);
  printf("after grow:    total=%llu offset=%llu visible=%llu\n",
         (unsigned long long)total, (unsigned long long)offset,
         (unsigned long long)visible);
  CHECK(offset + visible == total, "viewport stays at the bottom after grow");

  ghostty_terminal_free(t);

  // The case the shell scrollback bug actually needs: the cursor parked above
  // the last row, which is where a program that redraws its own output leaves
  // it. The shrink then has no trailing blanks to drop and pushes rows the
  // screen was showing into the scrollback; the grow pads with blanks instead
  // of pulling them back. The viewport still reports itself at the bottom, so
  // the count is what has to be checked, not the offset.
  CHECK(ghostty_terminal_new(NULL, &t, 80, 24) == GHOSTTY_SUCCESS, "new term 2");
  ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &cap);
  for (int i = 0; i < 200; i++) {
    int n = snprintf(line, sizeof(line), "line %d\r\n", i);
    ghostty_terminal_vt_write(t, (const uint8_t *)line, (size_t)n);
  }
  const char *park = "\x1b[10;1H";
  ghostty_terminal_vt_write(t, (const uint8_t *)park, strlen(park));

  uint64_t parked_total = 0;
  scrollbar(t, &parked_total, &offset, &visible);
  uint64_t parked_visible = visible;

  ghostty_terminal_resize(t, 80, 12, 10, 20);
  ghostty_terminal_resize(t, 80, 24, 10, 20);
  scrollbar(t, &total, &offset, &visible);
  printf("parked cursor: total %llu -> %llu (grew by %lld)\n",
         (unsigned long long)parked_total, (unsigned long long)total,
         (long long)total - (long long)parked_total);

  // The rows are re-partitioned, never destroyed: everything written is still
  // reachable. If this ever fails, content is being lost rather than moved.
  CHECK(total >= parked_total, "no content is destroyed by the resize pair");

  // The app corrects the view by scrolling back over the padding, which is
  // exactly how much the total grew while the height returned to its old
  // value. This is the arithmetic TerminalView.compensateStrandedRows relies
  // on; if the library stops stranding rows, the delta is simply zero and the
  // correction becomes a no-op.
  CHECK(visible == parked_visible, "height came back to what it was");
  uint64_t stranded = total - parked_total;
  if (stranded > 0) {
    GhosttyTerminalScrollViewport up = {.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA};
    up.value.delta = -(intptr_t)stranded;
    ghostty_terminal_scroll_viewport(t, up);
    uint64_t t2 = 0, o2 = 0, v2 = 0;
    scrollbar(t, &t2, &o2, &v2);
    printf("after correction: offset=%llu (was %llu before the resize)\n",
           (unsigned long long)o2, (unsigned long long)(parked_total - parked_visible));
    CHECK(o2 + parked_visible == parked_total,
          "correction restores the pre-resize view");
  }

  ghostty_terminal_free(t);
  printf("%d checks, %d failures\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}

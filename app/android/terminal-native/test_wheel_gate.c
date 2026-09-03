// Which way a scroll gesture goes when an application is reading the mouse.
//
// The terminal has to serve two things at once. A full-screen application
// scrolls by receiving wheel reports, and a shell's scrollback scrolls by
// moving the local viewport. Sending every gesture to the application trapped
// the user: once the viewport sat at the bottom there was no gesture left to
// reach the history with, and only reopening the session cleared it.
//
// The rule the view applies:
//   report to the application   when it tracks the mouse, the viewport is at
//                               the bottom, and either the gesture is downward
//                               or there is no history above
//   move the viewport           otherwise
//
// This checks the terminal state that rule reads, against the real library:
// that the alternate screen keeps no history (so a full-screen application is
// never robbed of its wheel), and that the primary screen does (so the user
// can always get back into it).
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

typedef struct {
  uint64_t total, offset, visible;
} Bar;

static Bar bar(GhosttyTerminal t) {
  GhosttyTerminalScrollbar sb;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb);
  Bar b = {sb.total, sb.offset, sb.len};
  return b;
}

// The two predicates the view computes from that state.
static int at_bottom(Bar b) {
  if (b.total <= b.visible) return 1;
  return b.offset + b.visible >= b.total;
}
static int has_scrollback(Bar b) { return b.total > b.visible; }

// The routing decision itself, mirroring scrollOrReportWheel.
static int reports_to_app(Bar b, int rows, int tracking) {
  if (!tracking) return 0;
  if (!at_bottom(b)) return 0;
  if (rows > 0 && has_scrollback(b)) return 0;
  return 1;
}

static void w(GhosttyTerminal t, const char *s) {
  ghostty_terminal_vt_write(t, (const uint8_t *)s, strlen(s));
}

int main(void) {
  GhosttyTerminal t;
  CHECK(ghostty_terminal_new(NULL, &t, 80, 24) == GHOSTTY_SUCCESS, "new term");
  size_t cap = 8u * 1024 * 1024;
  ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &cap);

  char line[80];
  for (int i = 0; i < 500; i++) {
    int n = snprintf(line, sizeof(line), "history %04d\r\n", i);
    ghostty_terminal_vt_write(t, (const uint8_t *)line, (size_t)n);
  }

  // --- primary screen, history above, viewport at the bottom ---
  Bar b = bar(t);
  printf("primary: total=%llu offset=%llu visible=%llu\n",
         (unsigned long long)b.total, (unsigned long long)b.offset,
         (unsigned long long)b.visible);
  CHECK(at_bottom(b), "starts at the bottom");
  CHECK(has_scrollback(b), "primary screen keeps history");

  // Scrolling up is what the user could not do before: it must move the view.
  CHECK(!reports_to_app(b, 10, 1), "scroll up at the bottom moves the viewport");
  // Downward still belongs to the application: it has nothing to reveal here.
  CHECK(reports_to_app(b, -10, 1), "scroll down at the bottom reports to the app");
  // With no application tracking, everything moves the viewport.
  CHECK(!reports_to_app(b, 10, 0), "untracked scroll up moves the viewport");
  CHECK(!reports_to_app(b, -10, 0), "untracked scroll down moves the viewport");

  // --- away from the bottom, every gesture is the viewport's ---
  GhosttyTerminalScrollViewport up = {.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA};
  up.value.delta = -10;
  ghostty_terminal_scroll_viewport(t, up);
  Bar scrolled = bar(t);
  CHECK(!at_bottom(scrolled), "scrolling up leaves the bottom");
  CHECK(!reports_to_app(scrolled, 10, 1), "in history, up moves the viewport");
  CHECK(!reports_to_app(scrolled, -10, 1), "in history, down moves the viewport");

  GhosttyTerminalScrollViewport bottom = {.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM};
  ghostty_terminal_scroll_viewport(t, bottom);

  // --- alternate screen: a full-screen application owns the wheel ---
  w(t, "\x1b[?1049h");
  w(t, "a full-screen application draws here\r\n");
  Bar alt = bar(t);
  printf("alt:     total=%llu offset=%llu visible=%llu\n",
         (unsigned long long)alt.total, (unsigned long long)alt.offset,
         (unsigned long long)alt.visible);
  CHECK(!has_scrollback(alt), "the alternate screen keeps no history");
  CHECK(at_bottom(alt), "the alternate screen is pinned");
  // Both directions reach the application, which is the only thing that can
  // scroll its display.
  CHECK(reports_to_app(alt, 10, 1), "alt screen up reports to the app");
  CHECK(reports_to_app(alt, -10, 1), "alt screen down reports to the app");

  // And the pin is real: moving the viewport there changes nothing, so the
  // fallback path cannot scroll behind the application's display.
  ghostty_terminal_scroll_viewport(t, up);
  Bar after = bar(t);
  CHECK(after.offset == alt.offset && after.total == alt.total,
        "the alternate screen viewport does not move");

  ghostty_terminal_free(t);
  printf("%d checks, %d failures\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}

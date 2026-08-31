// Mouse reporting, and the guarantee that it stays silent unless asked for.
//
// A tap is only sent to the application when the application turned mouse
// tracking on. A plain shell never does, so a tap there must produce no bytes
// at all: anything else would land in the command line as garbage.
//
// The encoder takes its mode and wire format from the terminal, so this checks
// the same path the app uses rather than a parallel implementation.

#include <assert.h>
#include <stdio.h>
#include <string.h>

#include <ghostty/vt.h>

static int failures = 0;

static void ws(GhosttyTerminal t, const char *s) {
  ghostty_terminal_vt_write(t, (const uint8_t *)s, strlen(s));
}

/** Encodes one event, returning the byte count and filling out. */
static size_t encode(GhosttyTerminal t, GhosttyMouseEncoder enc,
                     GhosttyMouseAction action, int button, uint16_t col,
                     uint16_t row, char *out, size_t cap) {
  ghostty_mouse_encoder_setopt_from_terminal(enc, t);

  // The geometry is the caller's, not the terminal's: without it the encoder
  // cannot map a pixel position onto a cell.
  GhosttyMouseEncoderSize size = {0};
  size.size = sizeof(size);
  size.cell_width = 10;
  size.cell_height = 20;
  size.screen_width = 80 * 10;
  size.screen_height = 24 * 20;
  ghostty_mouse_encoder_setopt(enc, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);

  GhosttyMouseEvent ev;
  if (ghostty_mouse_event_new(NULL, &ev) != GHOSTTY_SUCCESS) return 0;
  ghostty_mouse_event_set_action(ev, action);
  if (button >= 0) {
    ghostty_mouse_event_set_button(ev, (GhosttyMouseButton)button);
  } else {
    ghostty_mouse_event_clear_button(ev);
  }
  ghostty_mouse_event_set_mods(ev, 0);

  // Cell centres, as the app reports them.
  GhosttyMousePosition pos;
  pos.x = (float)col * 10.0f + 5.0f;
  pos.y = (float)row * 20.0f + 10.0f;
  ghostty_mouse_event_set_position(ev, pos);

  size_t written = 0;
  GhosttyResult r =
      ghostty_mouse_encoder_encode(enc, ev, out, cap, &written);
  ghostty_mouse_event_free(ev);
  if (r != GHOSTTY_SUCCESS) return 0;
  out[written] = '\0';
  return written;
}

static void expect_silent_action(const char *what, const char *setup,
                                 GhosttyMouseAction action, int button) {
  GhosttyTerminal t;
  GhosttyMouseEncoder enc;
  assert(ghostty_terminal_new(NULL, &t, 80, 24) == GHOSTTY_SUCCESS);
  assert(ghostty_mouse_encoder_new(NULL, &enc) == GHOSTTY_SUCCESS);
  if (setup != NULL) ws(t, setup);

  char buf[128];
  size_t n = encode(t, enc, action, button, 4, 2, buf, sizeof(buf));
  if (n == 0) {
    printf("  ok   %-34s no bytes\n", what);
  } else {
    printf("  FAIL %-34s emitted %zu bytes\n", what, n);
    failures++;
  }
  ghostty_mouse_encoder_free(enc);
  ghostty_terminal_free(t);
}

/** A press that must produce nothing, which is the untracked case. */
static void expect_silent(const char *what, const char *setup) {
  expect_silent_action(what, setup, GHOSTTY_MOUSE_ACTION_PRESS,
                       GHOSTTY_MOUSE_BUTTON_LEFT);
}

static void expect_contains(const char *what, const char *setup,
                            GhosttyMouseAction action, int button,
                            const char *needle) {
  GhosttyTerminal t;
  GhosttyMouseEncoder enc;
  assert(ghostty_terminal_new(NULL, &t, 80, 24) == GHOSTTY_SUCCESS);
  assert(ghostty_mouse_encoder_new(NULL, &enc) == GHOSTTY_SUCCESS);
  ws(t, setup);

  char buf[128];
  size_t n = encode(t, enc, action, button, 4, 2, buf, sizeof(buf));
  if (n > 0 && strstr(buf, needle) != NULL) {
    printf("  ok   %-34s %s\n", what, needle);
  } else {
    printf("  FAIL %-34s got \"%s\" want \"%s\"\n", what, n ? buf : "", needle);
    failures++;
  }
  ghostty_mouse_encoder_free(enc);
  ghostty_terminal_free(t);
}

int main(void) {
  printf("mouse reporting stays off until requested\n");
  expect_silent("no tracking at all", NULL);
  expect_silent("tracking disabled again", "\x1b[?1000h\x1b[?1000l");

  printf("mouse reporting once enabled\n");
  // X10 encodes the cell as a byte at 32 + 1 + index: column 4 is ' ' + 5.
  expect_contains("normal tracking, press", "\x1b[?1000h",
                  GHOSTTY_MOUSE_ACTION_PRESS, GHOSTTY_MOUSE_BUTTON_LEFT,
                  "\x1b[M");

  // SGR carries the cell as decimal text, so the coordinates are readable.
  expect_contains("sgr press reports the cell", "\x1b[?1000h\x1b[?1006h",
                  GHOSTTY_MOUSE_ACTION_PRESS, GHOSTTY_MOUSE_BUTTON_LEFT,
                  "\x1b[<0;5;3M");
  expect_contains("sgr release is distinguishable", "\x1b[?1000h\x1b[?1006h",
                  GHOSTTY_MOUSE_ACTION_RELEASE, GHOSTTY_MOUSE_BUTTON_LEFT,
                  "\x1b[<0;5;3m");
  expect_contains("sgr right button", "\x1b[?1000h\x1b[?1006h",
                  GHOSTTY_MOUSE_ACTION_PRESS, GHOSTTY_MOUSE_BUTTON_RIGHT,
                  "\x1b[<2;5;3M");

  // Normal tracking reports buttons only. Motion needs 1002 or 1003, so a
  // finger dragged over a plain 1000 application stays quiet.
  expect_silent_action("motion under button tracking", "\x1b[?1000h\x1b[?1006h",
                       GHOSTTY_MOUSE_ACTION_MOTION, -1);

  printf("motion once the application asks for it\n");
  expect_contains("any-event tracking reports motion", "\x1b[?1003h\x1b[?1006h",
                  GHOSTTY_MOUSE_ACTION_MOTION, -1, "\x1b[<");

  if (failures > 0) {
    printf("\n%d failure(s)\n", failures);
    return 1;
  }
  printf("\nall ok\n");
  return 0;
}

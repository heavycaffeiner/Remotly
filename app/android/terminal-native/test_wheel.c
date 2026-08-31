// What a scroll wheel has to encode for an application that tracks the mouse.
//
// A full-screen program (OpenCode, Claude Code, less, vim) draws on the
// alternate screen and has no scrollback of its own: scrolling it means
// sending wheel reports, which SGR encodes as buttons 4 (up) and 5 (down)
// with a press action. The terminal view only ever moved its own viewport, so
// those programs never saw a scroll at all.
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

static size_t encode_wheel(GhosttyTerminal t, GhosttyMouseEncoder enc,
                           int button, char *out, size_t cap) {
  ghostty_mouse_encoder_setopt_from_terminal(enc, t);

  GhosttyMouseEncoderSize size = {0};
  size.size = sizeof(size);
  size.cell_width = 10;
  size.cell_height = 20;
  size.screen_width = 800;
  size.screen_height = 480;
  ghostty_mouse_encoder_setopt(enc, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);

  GhosttyMouseEvent ev;
  if (ghostty_mouse_event_new(NULL, &ev) != GHOSTTY_SUCCESS) return 0;
  ghostty_mouse_event_set_action(ev, GHOSTTY_MOUSE_ACTION_PRESS);
  ghostty_mouse_event_set_button(ev, (GhosttyMouseButton)button);
  ghostty_mouse_event_set_mods(ev, 0);

  GhosttyMousePosition pos;
  pos.x = 45.0f;
  pos.y = 50.0f;
  ghostty_mouse_event_set_position(ev, pos);

  size_t n = 0;
  GhosttyResult r = ghostty_mouse_encoder_encode(enc, ev, out, cap, &n);
  ghostty_mouse_event_free(ev);
  if (r != GHOSTTY_SUCCESS) return 0;
  return n;
}

int main(void) {
  GhosttyTerminal t;
  CHECK(ghostty_terminal_new(NULL, &t, 80, 24) == GHOSTTY_SUCCESS, "new term");
  GhosttyMouseEncoder enc;
  CHECK(ghostty_mouse_encoder_new(NULL, &enc) == GHOSTTY_SUCCESS, "new enc");

  // An application that asked for mouse tracking, the way a TUI does.
  const char *on = "\x1b[?1000h\x1b[?1006h";
  ghostty_terminal_vt_write(t, (const uint8_t *)on, strlen(on));

  char buf[128];
  size_t n = encode_wheel(t, enc, 4, buf, sizeof(buf));
  buf[n < sizeof(buf) ? n : sizeof(buf) - 1] = 0;
  CHECK(n > 0, "wheel up produces bytes under tracking");
  // SGR reports the wheel as button 64 (4 with the wheel bit set).
  CHECK(strstr(buf, "64") != NULL, "wheel up encodes as button 64");
  printf("wheel up:   %s\n", buf + 1);

  n = encode_wheel(t, enc, 5, buf, sizeof(buf));
  buf[n < sizeof(buf) ? n : sizeof(buf) - 1] = 0;
  CHECK(n > 0, "wheel down produces bytes under tracking");
  CHECK(strstr(buf, "65") != NULL, "wheel down encodes as button 65");
  printf("wheel down: %s\n", buf + 1);

  // With no tracking mode set, a wheel report must produce nothing: an
  // ordinary shell scrolls its own scrollback instead.
  GhosttyTerminal plain;
  CHECK(ghostty_terminal_new(NULL, &plain, 80, 24) == GHOSTTY_SUCCESS,
        "new plain term");
  n = encode_wheel(plain, enc, 4, buf, sizeof(buf));
  CHECK(n == 0, "wheel is silent without tracking");

  ghostty_mouse_encoder_free(enc);
  ghostty_terminal_free(plain);
  ghostty_terminal_free(t);

  printf("%d checks, %d failures\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}

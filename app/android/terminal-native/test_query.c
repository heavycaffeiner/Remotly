// What a terminal writes back to the pty when an application queries it.
//
// A shell's startup and a prompt framework (starship, powerlevel10k, oh-my-zsh)
// probe the terminal with device-attribute and colour queries. The reply is
// written to the pty, and a terminal that generates the reply but never sends
// it leaves the application waiting; the query bytes it already echoed then sit
// on screen as stray characters.
#include <ghostty/vt.h>
#include <stdio.h>
#include <string.h>

static char captured[512];
static size_t captured_len;

static void on_write(GhosttyTerminal t, void *ud, const uint8_t *d, size_t n) {
  (void)t; (void)ud;
  if (captured_len + n < sizeof(captured)) {
    memcpy(captured + captured_len, d, n);
    captured_len += n;
  }
}

static int g_failures = 0;
static int g_checks = 0;

static void probe(const char *label, const char *seq) {
  GhosttyTerminal t;
  if (ghostty_terminal_new(NULL, &t, 80, 24) != GHOSTTY_SUCCESS) return;
  ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_USERDATA, NULL);
  ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_WRITE_PTY, (const void *)on_write);
  captured_len = 0;
  ghostty_terminal_vt_write(t, (const uint8_t *)seq, strlen(seq));
  captured[captured_len] = 0;
  printf("%-28s -> %zu bytes: ", label, captured_len);
  for (size_t i = 0; i < captured_len; i++) {
    unsigned char c = captured[i];
    if (c == 0x1b) printf("ESC");
    else if (c < 0x20) printf("\\x%02x", c);
    else putchar(c);
  }
  printf("\n");
  g_checks++;
  if (captured_len == 0) {
    g_failures++;
    fprintf(stderr, "FAIL: %s produced no reply\n", label);
  }
  ghostty_terminal_free(t);
}

int main(void) {
  probe("primary device attrs (DA1)", "\x1b[c");
  probe("secondary device attrs (DA2)", "\x1b[>c");
  probe("device status report", "\x1b[5n");
  probe("cursor position report", "\x1b[6n");
  probe("XTVERSION", "\x1b[>0q");
  // A colour query needs a palette the vt layer does not carry on its own, so
  // it legitimately answers nothing here. Everything above must reply.
  printf("%d checks, %d failures\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}

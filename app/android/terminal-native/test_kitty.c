// Key encoding under the Kitty keyboard protocol.
//
// An application that pushes CSI > 1 u (pico does, unconditionally) expects
// every key as a CSI u sequence, including ordinary printable characters.
// Committed text used to bypass the encoder and write raw UTF-8, which such an
// application never parses as a key: it saw no input at all.
//
// Build and run through run-kitty-test.sh.

#include <assert.h>
#include <stdio.h>
#include <string.h>

#include <ghostty/vt.h>

static int failures = 0;

static void expect_bytes(const char *what, const char *got, size_t got_len,
                         const char *want) {
  size_t want_len = strlen(want);
  if (got_len == want_len && memcmp(got, want, want_len) == 0) {
    printf("  ok   %s\n", what);
    return;
  }
  printf("  FAIL %s\n", what);
  printf("       want:");
  for (size_t i = 0; i < want_len; i++) printf(" %02x", (unsigned char)want[i]);
  printf("\n       got: ");
  for (size_t i = 0; i < got_len; i++) printf(" %02x", (unsigned char)got[i]);
  printf("\n");
  failures++;
}

// Encodes one printable codepoint the way nativeSendText does.
static size_t encode_char(GhosttyTerminal terminal, GhosttyKeyEncoder encoder,
                          uint32_t cp, const char *utf8, char *out,
                          size_t out_cap) {
  ghostty_key_encoder_setopt_from_terminal(encoder, terminal);
  GhosttyKeyEvent ev;
  if (ghostty_key_event_new(NULL, &ev) != GHOSTTY_SUCCESS) return 0;
  ghostty_key_event_set_action(ev, GHOSTTY_KEY_ACTION_PRESS);
  ghostty_key_event_set_key(ev, GHOSTTY_KEY_UNIDENTIFIED);
  ghostty_key_event_set_mods(ev, 0);
  ghostty_key_event_set_composing(ev, false);
  ghostty_key_event_set_unshifted_codepoint(ev, cp);
  ghostty_key_event_set_utf8(ev, utf8, strlen(utf8));
  size_t written = 0;
  ghostty_key_encoder_encode(encoder, ev, out, out_cap, &written);
  ghostty_key_event_free(ev);
  return written;
}

int main(void) {
  GhosttyTerminal terminal;
  GhosttyKeyEncoder encoder;
  assert(ghostty_terminal_new(NULL, &terminal, 80, 24) == GHOSTTY_SUCCESS);
  assert(ghostty_key_encoder_new(NULL, &encoder) == GHOSTTY_SUCCESS);

  char out[64];
  size_t n;

  printf("without the kitty protocol\n");
  n = encode_char(terminal, encoder, 'a', "a", out, sizeof(out));
  expect_bytes("a plain character is itself", out, n, "a");

  // What pico sends on startup: push disambiguate-escape-codes.
  const char push[] = "\x1b[>1u";
  ghostty_terminal_vt_write(terminal, (const uint8_t *)push, strlen(push));

  printf("with the kitty protocol pushed\n");
  n = encode_char(terminal, encoder, 'a', "a", out, sizeof(out));
  // Disambiguate alone still reports text keys as plain text; the point of
  // this case is that the encoder is consulted rather than bypassed, and that
  // it produces something the application can parse.
  if (n == 0) {
    printf("  FAIL a plain character encoded to nothing\n");
    failures++;
  } else {
    printf("  ok   a plain character still encodes (%zu bytes)\n", n);
  }

  // Enter is the key pico reads to submit. Under disambiguation it must not
  // silently vanish.
  ghostty_key_encoder_setopt_from_terminal(encoder, terminal);
  GhosttyKeyEvent ev;
  assert(ghostty_key_event_new(NULL, &ev) == GHOSTTY_SUCCESS);
  ghostty_key_event_set_action(ev, GHOSTTY_KEY_ACTION_PRESS);
  ghostty_key_event_set_key(ev, GHOSTTY_KEY_ENTER);
  ghostty_key_event_set_mods(ev, 0);
  ghostty_key_event_set_composing(ev, false);
  size_t written = 0;
  ghostty_key_encoder_encode(encoder, ev, out, sizeof(out), &written);
  ghostty_key_event_free(ev);
  if (written == 0) {
    printf("  FAIL enter encoded to nothing\n");
    failures++;
  } else {
    printf("  ok   enter encodes (%zu bytes)\n", written);
  }

  ghostty_key_encoder_free(encoder);
  ghostty_terminal_free(terminal);

  if (failures > 0) {
    printf("\n%d failure(s)\n", failures);
    return 1;
  }
  printf("\nall ok\n");
  return 0;
}

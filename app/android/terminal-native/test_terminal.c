// Host-side automated tests for the libghostty-vt terminal core that backs
// the Remotly Android terminal module (M1-09). These port the deterministic
// M0-02 / M0-03 fixtures into runnable assertions so rendering, streaming,
// width, wrap, resize, cursor, selection, and input-safety behavior are
// verified on a host build without an Android device.
//
// Build and run with run-host-tests.sh in this directory.

#include <ghostty/vt.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_failures = 0;
static int g_checks = 0;

#define CHECK(cond, msg)                                        \
  do {                                                          \
    g_checks++;                                                 \
    if (!(cond)) {                                              \
      g_failures++;                                             \
      fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__,   \
              msg);                                             \
    }                                                           \
  } while (0)

static uint8_t *read_file(const char *path, size_t *out_len) {
  FILE *f = fopen(path, "rb");
  if (!f) {
    fprintf(stderr, "cannot open fixture: %s\n", path);
    exit(2);
  }
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  uint8_t *buf = malloc((size_t)(n > 0 ? n : 1));
  size_t rd = fread(buf, 1, (size_t)n, f);
  fclose(f);
  *out_len = rd;
  return buf;
}

static uint16_t cursor_x(GhosttyTerminal t) {
  uint16_t x = 65535;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_CURSOR_X, &x);
  return x;
}

static uint16_t cursor_y(GhosttyTerminal t) {
  uint16_t y = 65535;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &y);
  return y;
}

// Render the terminal (or just the given selection) to trimmed plain text.
// Returns a NUL-terminated malloc'd string; caller frees with free().
static char *format_plain(GhosttyTerminal t, const GhosttySelection *sel) {
  GhosttyFormatterTerminalOptions opts =
      GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalOptions);
  opts.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  opts.trim = true;
  if (sel) opts.selection = sel;

  GhosttyFormatter formatter;
  GhosttyResult r =
      ghostty_formatter_terminal_new(NULL, &formatter, t, opts);
  if (r != GHOSTTY_SUCCESS) return NULL;

  uint8_t *buf = NULL;
  size_t len = 0;
  r = ghostty_formatter_format_alloc(formatter, NULL, &buf, &len);
  if (r != GHOSTTY_SUCCESS) {
    ghostty_formatter_free(formatter);
    return NULL;
  }
  char *out = malloc(len + 1);
  memcpy(out, buf, len);
  out[len] = '\0';
  ghostty_free(NULL, buf, len);
  ghostty_formatter_free(formatter);
  return out;
}

static int contains_utf8(const char *haystack, const char *needle) {
  size_t hlen = strlen(haystack);
  size_t nlen = strlen(needle);
  if (nlen > hlen) return 0;
  for (size_t i = 0; i + nlen <= hlen; i++) {
    if (memcmp(haystack + i, needle, nlen) == 0) return 1;
  }
  return 0;
}

// Write exactly `len` bytes of `data` starting at offset `off`.
static void write_slice(GhosttyTerminal t, const uint8_t *data, size_t off,
                        size_t len) {
  ghostty_terminal_vt_write(t, data + off, len);
}

// --- Width: East Asian Width, box drawing, emoji, combining marks ----------

// Expected display-cell width per line of m0-03 mixed-scripts.txt, mirrored
// from spikes/m0-03-cjk-ime/fixtures/expected.json.
static const int kMixedScriptCells[] = {34, 16, 13, 9, 16, 27};

static void test_width(const char *fixtures_dir) {
  fprintf(stderr, "run: test_width\n");
  char path[512];
  snprintf(path, sizeof(path), "%s/mixed-scripts.txt", fixtures_dir);
  size_t len;
  uint8_t *data = read_file(path, &len);

  // Walk the file line by line (each line is NUL-free, '\n' separated).
  int line_index = 0;
  size_t i = 0;
  while (i < len && line_index < (int)(sizeof(kMixedScriptCells) / sizeof(int))) {
    size_t start = i;
    while (i < len && data[i] != '\n') i++;
    size_t linelen = i - start;
    if (i < len) i++;  // consume '\n'

    GhosttyTerminal t;
    GhosttyResult r = ghostty_terminal_new(NULL, &t, 200, 10);
    CHECK(r == GHOSTTY_SUCCESS, "terminal_new (width)");
    if (r != GHOSTTY_SUCCESS) break;

    // Write the line without its trailing newline; the cursor X then equals
    // the number of display cells the line consumed.
    ghostty_terminal_vt_write(t, data + start, linelen);
    uint16_t x = cursor_x(t);
    CHECK(x == (uint16_t)kMixedScriptCells[line_index],
          "line cell width matches expected.json");
    if (x != (uint16_t)kMixedScriptCells[line_index]) {
      fprintf(stderr, "  line %d: got %u cells, expected %d\n", line_index,
              (unsigned)x, kMixedScriptCells[line_index]);
    }
    ghostty_terminal_free(t);
    line_index++;
  }
  CHECK(line_index == 6, "mixed-scripts has 6 lines");
  free(data);
}

// --- UTF-8: split multi-byte sequences across feed boundaries -------------

static void test_utf8_split(const char *fixtures_dir) {
  fprintf(stderr, "run: test_utf8_split\n");
  char path[512];
  snprintf(path, sizeof(path), "%s/split-utf8.bin", fixtures_dir);
  size_t len;
  uint8_t *data = read_file(path, &len);

  // Terminal A: the whole buffer in one write.
  GhosttyTerminal a;
  CHECK(ghostty_terminal_new(NULL, &a, 80, 24) == GHOSTTY_SUCCESS, "term A");
  ghostty_terminal_vt_write(a, data, len);

  // Terminal B: the same buffer one byte at a time, so every multi-byte
  // sequence is split across feed calls.
  GhosttyTerminal b;
  CHECK(ghostty_terminal_new(NULL, &b, 80, 24) == GHOSTTY_SUCCESS, "term B");
  for (size_t i = 0; i < len; i++) write_slice(b, data, i, 1);

  char *fa = format_plain(a, NULL);
  char *fb = format_plain(b, NULL);
  CHECK(fa && fb, "format both terminals");
  if (fa && fb) {
    CHECK(strcmp(fa, fb) == 0, "split stream renders identically to unsplit");
    // U+FFFD (EF BF BD) must not appear: chunking alone must never emit a
    // replacement character.
    CHECK(!contains_utf8(fa, "\xEF\xBF\xBD"), "no replacement char (A)");
    CHECK(!contains_utf8(fb, "\xEF\xBF\xBD"), "no replacement char (B)");
  }
  free(fa);
  free(fb);
  ghostty_terminal_free(a);
  ghostty_terminal_free(b);
  free(data);
}

// --- Malformed bytes fail safely without corrupting later output ----------

static void test_invalid_utf8(const char *fixtures_dir) {
  fprintf(stderr, "run: test_invalid_utf8\n");
  char path[512];
  snprintf(path, sizeof(path), "%s/invalid-utf8.bin", fixtures_dir);
  size_t len;
  uint8_t *data = read_file(path, &len);

  GhosttyTerminal t;
  CHECK(ghostty_terminal_new(NULL, &t, 80, 24) == GHOSTTY_SUCCESS, "term");
  // Feed malformed bytes, then valid content. The valid content must still
  // render intact (no crash, no corruption of later output).
  ghostty_terminal_vt_write(t, data, len);
  ghostty_terminal_vt_write(t, (const uint8_t *)"OK-AFTER\n", 9);

  char *f = format_plain(t, NULL);
  CHECK(f, "format after malformed input");
  if (f) {
    CHECK(contains_utf8(f, "OK-AFTER"), "later output not corrupted");
  }
  free(f);
  ghostty_terminal_free(t);
  free(data);
}

// --- Wrap and reflow on resize --------------------------------------------

static void test_wrap_reflow(const char *fixtures_dir) {
  fprintf(stderr, "run: test_wrap_reflow\n");
  char path[512];
  snprintf(path, sizeof(path), "%s/wrapping.txt", fixtures_dir);
  size_t len;
  uint8_t *data = read_file(path, &len);

  // 4 columns: "aaaa" fills the row, "hanhanhanhan" (8 cells) wraps to two.
  GhosttyTerminal t;
  CHECK(ghostty_terminal_new(NULL, &t, 4, 20) == GHOSTTY_SUCCESS, "term 4col");
  ghostty_terminal_vt_write(t, data, len);
  uint16_t y_narrow = cursor_y(t);
  CHECK(y_narrow >= 2, "content wraps in a narrow viewport");

  // Resize wider: the same content reflows into fewer rows.
  GhosttyResult r = ghostty_terminal_resize(t, 80, 20, 10, 20);
  CHECK(r == GHOSTTY_SUCCESS, "resize wider");
  uint16_t y_wide = cursor_y(t);
  CHECK(y_wide < y_narrow, "reflow reduces rows when widened");

  uint16_t cols = 0, rows = 0;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_COLS, &cols);
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
  CHECK(cols == 80 && rows == 20, "dimensions report post-resize");

  ghostty_terminal_free(t);
  free(data);
}

// --- Cursor positioning via CUP -------------------------------------------

static void test_cursor(void) {
  fprintf(stderr, "run: test_cursor\n");
  GhosttyTerminal t;
  CHECK(ghostty_terminal_new(NULL, &t, 80, 24) == GHOSTTY_SUCCESS, "term");
  // CUP is 1-based: CSI 3;10 H -> row 3, col 10 -> 0-based (x=9, y=2).
  ghostty_terminal_vt_write(t, (const uint8_t *)"\033[3;10H", 7);
  CHECK(cursor_x(t) == 9, "cursor x after CUP");
  CHECK(cursor_y(t) == 2, "cursor y after CUP");
  ghostty_terminal_free(t);
}

// --- Selection ------------------------------------------------------------

static void test_selection(void) {
  fprintf(stderr, "run: test_selection\n");
  GhosttyTerminal t;
  CHECK(ghostty_terminal_new(NULL, &t, 80, 8) == GHOSTTY_SUCCESS, "term");
  ghostty_terminal_vt_write(t, (const uint8_t *)"hello world\n", 12);

  // Select-all then format only the selection.
  GhosttySelection all = GHOSTTY_INIT_SIZED(GhosttySelection);
  GhosttyResult r = ghostty_terminal_select_all(t, &all);
  CHECK(r == GHOSTTY_SUCCESS, "select_all");
  char *f = format_plain(t, &all);
  CHECK(f, "format selection");
  if (f) {
    CHECK(strcmp(f, "hello world") == 0, "select-all text matches");
    free(f);
  }

  // Word selection under "world" (starts at column 6).
  GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
  GhosttyPoint p = {
      .tag = GHOSTTY_POINT_TAG_ACTIVE,
      .value = {.coordinate = {.x = 6, .y = 0}},
  };
  r = ghostty_terminal_grid_ref(t, p, &ref);
  CHECK(r == GHOSTTY_SUCCESS, "grid_ref");
  GhosttyTerminalSelectWordOptions w =
      GHOSTTY_INIT_SIZED(GhosttyTerminalSelectWordOptions);
  w.ref = ref;
  GhosttySelection word = GHOSTTY_INIT_SIZED(GhosttySelection);
  r = ghostty_terminal_select_word(t, &w, &word);
  CHECK(r == GHOSTTY_SUCCESS, "select_word");
  char *fw = format_plain(t, &word);
  CHECK(fw, "format word selection");
  if (fw) {
    CHECK(strcmp(fw, "world") == 0, "word selection matches");
    free(fw);
  }
  ghostty_terminal_free(t);
}

// --- Lifecycle: mount, feed, unmount, remount without crash ---------------

static void test_lifecycle(void) {
  fprintf(stderr, "run: test_lifecycle\n");
  for (int i = 0; i < 3; i++) {
    GhosttyTerminal t;
    GhosttyResult r = ghostty_terminal_new(NULL, &t, 80, 24);
    CHECK(r == GHOSTTY_SUCCESS, "recreate terminal");
    ghostty_terminal_vt_write(t, (const uint8_t *)"line\n", 5);
    ghostty_terminal_free(t);
  }
}

// --- Bounded large input: 1 MiB burst must not crash or blow up ----------

static void test_bounded_large_input(const char *fixtures_dir) {
  fprintf(stderr, "run: test_bounded_large_input\n");
  char path[512];
  snprintf(path, sizeof(path), "%s/burst-1mb.bin", fixtures_dir);
  size_t len;
  uint8_t *data = read_file(path, &len);
  CHECK(len == 1048576, "burst fixture is 1 MiB");

  GhosttyTerminal t;
  CHECK(ghostty_terminal_new(NULL, &t, 80, 24) == GHOSTTY_SUCCESS, "term");
  ghostty_terminal_vt_write(t, data, len);

  uint16_t cols = 0, rows = 0, x = 0, y = 0;
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_COLS, &cols);
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_CURSOR_X, &x);
  ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &y);
  CHECK(cols == 80 && rows == 24, "dimensions stable after burst");
  CHECK(x < 80 && y < 24, "cursor in bounds after burst");
  ghostty_terminal_free(t);
  free(data);
}

int main(int argc, char **argv) {
  // Fixture roots are supplied by run-host-tests.sh; the defaults assume the
  // test binary runs from the repository root.
  const char *fixtures_02 = "spikes/m0-02-embedding/fixtures";
  const char *fixtures_03 = "spikes/m0-03-cjk-ime/fixtures";
  if (argc > 1) fixtures_02 = argv[1];
  if (argc > 2) fixtures_03 = argv[2];
  fprintf(stderr, "run: main start\n");

  test_width(fixtures_03);
  test_utf8_split(fixtures_02);
  test_invalid_utf8(fixtures_02);
  test_wrap_reflow(fixtures_03);
  test_cursor();
  test_selection();
  test_lifecycle();
  test_bounded_large_input(fixtures_02);

  printf("%d checks, %d failures\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}

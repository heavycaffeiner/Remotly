// JNI bridge for the Remotly terminal module (M1-09). Wraps libghostty-vt
// (pinned in app/android/ghostty/PIN.txt) behind a small, thread-confined C
// API that the Kotlin layer drives from the main thread.
//
// Data flow:
//   output (daemon -> app): nativeWrite() feeds bytes into the terminal.
//   input  (app -> daemon): nativeSendText()/nativeSendKey() encode input and
//     report it to the app via the listener's onInput(); the app forwards it
//     to the daemon, which writes the PTY.
//   effects: bell / title / terminal-initiated PTY writes are delivered to the
//     listener via onBell() / onTitle() / onPtyWrite().
//
// All entry points must be called from the same thread (the Android main
// thread). The library performs no internal locking for the caller.

#include <jni.h>

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <ghostty/vt.h>

#define REMOTLY_JNI_VERSION JNI_VERSION_1_6

// Upper bound on the serialized grid. Matches the clamp in TerminalView.
#define REMOTLY_MAX_CELLS ((size_t)512 * 512)

typedef struct {
  GhosttyTerminal terminal;
  GhosttyKeyEncoder encoder;
  GhosttyRenderState render_state;
  GhosttyRenderStateRowIterator row_iter;
  GhosttyRenderStateRowCells row_cells;
  GhosttyMouseEncoder mouse_encoder;
  JavaVM *jvm;
  jobject listener;  // global ref to the Kotlin RemotlyTerminalListener
  jmethodID onBell;
  jmethodID onTitle;
  jmethodID onInput;
  jmethodID onPtyWrite;
} RemotlyTerm;

static JNIEnv *get_env(RemotlyTerm *st) {
  JNIEnv *env = NULL;
  if (!st || !st->jvm) return NULL;
  if ((*st->jvm)->GetEnv(st->jvm, (void **)&env, REMOTLY_JNI_VERSION) !=
      JNI_OK)
    return NULL;
  return env;
}

// Convert a jstring (UTF-16) to a newly malloc'd raw UTF-8 buffer. The buffer
// is NUL-terminated for convenience; *out_len excludes the terminator.
static uint8_t *jstring_to_utf8(JNIEnv *env, jstring str, size_t *out_len) {
  if (!str) {
    *out_len = 0;
    return NULL;
  }
  const jchar *u16 = (*env)->GetStringChars(env, str, NULL);
  if (!u16) {
    *out_len = 0;
    return NULL;
  }
  jsize n = (*env)->GetStringLength(env, str);
  uint8_t *buf = malloc((size_t)n * 4 + 1);  // worst case 4 bytes per unit
  if (!buf) {
    (*env)->ReleaseStringChars(env, str, u16);
    *out_len = 0;
    return NULL;
  }
  size_t o = 0;
  for (jsize i = 0; i < n;) {
    uint32_t cp;
    if (u16[i] >= 0xD800 && u16[i] <= 0xDBFF && i + 1 < n &&
        u16[i + 1] >= 0xDC00 && u16[i + 1] <= 0xDFFF) {
      cp = 0x10000 + ((uint32_t)(u16[i] - 0xD800) << 10) +
           (uint32_t)(u16[i + 1] - 0xDC00);
      i += 2;
    } else if (u16[i] >= 0xD800 && u16[i] <= 0xDFFF) {
      // A malformed IME must not make us emit an invalid UTF-8 surrogate.
      // Replace an unpaired UTF-16 code unit using the same policy as the
      // platform's normal encoders.
      cp = 0xFFFD;
      i += 1;
    } else {
      cp = (uint32_t)u16[i];
      i += 1;
    }
    if (cp < 0x80) {
      buf[o++] = (uint8_t)cp;
    } else if (cp < 0x800) {
      buf[o++] = (uint8_t)(0xC0 | (cp >> 6));
      buf[o++] = (uint8_t)(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      buf[o++] = (uint8_t)(0xE0 | (cp >> 12));
      buf[o++] = (uint8_t)(0x80 | ((cp >> 6) & 0x3F));
      buf[o++] = (uint8_t)(0x80 | (cp & 0x3F));
    } else {
      buf[o++] = (uint8_t)(0xF0 | (cp >> 18));
      buf[o++] = (uint8_t)(0x80 | ((cp >> 12) & 0x3F));
      buf[o++] = (uint8_t)(0x80 | ((cp >> 6) & 0x3F));
      buf[o++] = (uint8_t)(0x80 | (cp & 0x3F));
    }
  }
  (*env)->ReleaseStringChars(env, str, u16);
  buf[o] = '\0';
  *out_len = o;
  return buf;
}

// Deliver a malloc'd UTF-8 buffer to the listener via the given byte[] method,
// then free the buffer.
static void deliver_bytes(RemotlyTerm *st, jmethodID mid, uint8_t *utf8,
                          size_t len) {
  JNIEnv *env = get_env(st);
  if (!env || !utf8 || len == 0) {
    free(utf8);
    return;
  }
  jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
  if (arr) {
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)utf8);
    (*env)->CallVoidMethod(env, st->listener, mid, arr);
    (*env)->DeleteLocalRef(env, arr);
  }
  free(utf8);
}

// --- Effect callbacks. These run on the main thread, inside nativeWrite(). ---

static void on_bell(GhosttyTerminal terminal, void *userdata) {
  (void)terminal;
  RemotlyTerm *st = (RemotlyTerm *)userdata;
  JNIEnv *env = get_env(st);
  if (env) (*env)->CallVoidMethod(env, st->listener, st->onBell);
}

static void on_title_changed(GhosttyTerminal terminal, void *userdata) {
  RemotlyTerm *st = (RemotlyTerm *)userdata;
  JNIEnv *env = get_env(st);
  if (!env) return;
  GhosttyString title;
  if (ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_TITLE, &title) !=
          GHOSTTY_SUCCESS ||
      !title.ptr)
    return;
  jbyteArray arr = (*env)->NewByteArray(env, (jsize)title.len);
  if (arr) {
    if (title.len)
      (*env)->SetByteArrayRegion(env, arr, 0, (jsize)title.len,
                                 (const jbyte *)title.ptr);
    (*env)->CallVoidMethod(env, st->listener, st->onTitle, arr);
    (*env)->DeleteLocalRef(env, arr);
  }
}

static void on_pty_write(GhosttyTerminal terminal, void *userdata,
                         const uint8_t *data, size_t len) {
  (void)terminal;
  RemotlyTerm *st = (RemotlyTerm *)userdata;
  JNIEnv *env = get_env(st);
  if (!env || !data) return;
  jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
  if (arr) {
    if (len)
      (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)data);
    (*env)->CallVoidMethod(env, st->listener, st->onPtyWrite, arr);
    (*env)->DeleteLocalRef(env, arr);
  }
}

static RemotlyTerm *from_handle(jlong handle) {
  return (RemotlyTerm *)(uintptr_t)handle;
}

static void encode_text_as_keys(RemotlyTerm *st, const uint8_t *utf8,
                                size_t len);

// --- JNI entry points -------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeCreate(JNIEnv *env,
                                                           jclass, jint cols,
                                                           jint rows,
                                                           jlong scrollbackMaxBytes,
                                                           jobject listener) {
  RemotlyTerm *st = calloc(1, sizeof(RemotlyTerm));
  if (!st) return 0;
  if (ghostty_terminal_new(NULL, &st->terminal, (uint16_t)cols, (uint16_t)rows) !=
          GHOSTTY_SUCCESS ||
      ghostty_key_encoder_new(NULL, &st->encoder) != GHOSTTY_SUCCESS ||
      ghostty_render_state_new(NULL, &st->render_state) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_iterator_new(NULL, &st->row_iter) !=
          GHOSTTY_SUCCESS ||
      ghostty_render_state_row_cells_new(NULL, &st->row_cells) !=
          GHOSTTY_SUCCESS ||
      ghostty_mouse_encoder_new(NULL, &st->mouse_encoder) != GHOSTTY_SUCCESS) {
    if (st->terminal) ghostty_terminal_free(st->terminal);
    if (st->encoder) ghostty_key_encoder_free(st->encoder);
    if (st->render_state) ghostty_render_state_free(st->render_state);
    if (st->row_iter) ghostty_render_state_row_iterator_free(st->row_iter);
    if (st->row_cells) ghostty_render_state_row_cells_free(st->row_cells);
    if (st->mouse_encoder) ghostty_mouse_encoder_free(st->mouse_encoder);
    free(st);
    return 0;
  }
  size_t cap = (size_t)scrollbackMaxBytes;
  ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES,
                       &cap);

  (*env)->GetJavaVM(env, &st->jvm);
  st->listener = (*env)->NewGlobalRef(env, listener);
  jclass lcls = (*env)->GetObjectClass(env, listener);
  st->onBell = (*env)->GetMethodID(env, lcls, "onBell", "()V");
  st->onTitle = (*env)->GetMethodID(env, lcls, "onTitle", "([B)V");
  st->onInput = (*env)->GetMethodID(env, lcls, "onInput", "([B)V");
  st->onPtyWrite = (*env)->GetMethodID(env, lcls, "onPtyWrite", "([B)V");

  ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, st);
  ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_BELL,
                       (const void *)on_bell);
  ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED,
                       (const void *)on_title_changed);
  ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY,
                       (const void *)on_pty_write);
  return (jlong)(uintptr_t)st;
}

// Point an existing terminal at a new listener.
//
// A retained terminal outlives the view that created it, so the global ref it
// holds is to a view React has already dropped. Delivering a bell or a PTY
// write to that view would reach a dead host; worse, its input would never
// reach the session the user is now looking at.
JNIEXPORT void JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeRebind(JNIEnv *env, jclass,
                                                           jlong handle,
                                                           jobject listener) {
  RemotlyTerm *st = from_handle(handle);
  if (!st || !listener) return;
  if (st->listener) (*env)->DeleteGlobalRef(env, st->listener);
  st->listener = (*env)->NewGlobalRef(env, listener);
  jclass lcls = (*env)->GetObjectClass(env, listener);
  st->onBell = (*env)->GetMethodID(env, lcls, "onBell", "()V");
  st->onTitle = (*env)->GetMethodID(env, lcls, "onTitle", "([B)V");
  st->onInput = (*env)->GetMethodID(env, lcls, "onInput", "([B)V");
  st->onPtyWrite = (*env)->GetMethodID(env, lcls, "onPtyWrite", "([B)V");
}

JNIEXPORT void JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeDestroy(JNIEnv *env, jclass,
                                                            jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return;
  if (st->listener) (*env)->DeleteGlobalRef(env, st->listener);
  ghostty_key_encoder_free(st->encoder);
  ghostty_render_state_free(st->render_state);
  ghostty_render_state_row_iterator_free(st->row_iter);
  ghostty_render_state_row_cells_free(st->row_cells);
  ghostty_mouse_encoder_free(st->mouse_encoder);
  ghostty_terminal_free(st->terminal);
  free(st);
}

JNIEXPORT void JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeWrite(JNIEnv *env, jclass,
                                                          jlong handle,
                                                          jbyteArray data) {
  RemotlyTerm *st = from_handle(handle);
  if (!st || !data) return;
  jsize len = (*env)->GetArrayLength(env, data);
  if (len == 0) return;
  jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
  if (!buf) return;
  ghostty_terminal_vt_write(st->terminal, (const uint8_t *)buf, (size_t)len);
  (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
  // Keep the encoder in sync with any keyboard-mode changes the output made.
  ghostty_key_encoder_setopt_from_terminal(st->encoder, st->terminal);
}

JNIEXPORT void JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeResize(JNIEnv *env, jclass,
                                                           jlong handle,
                                                           jint cols, jint rows,
                                                           jint cellWidthPx,
                                                           jint cellHeightPx) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return;
  ghostty_terminal_resize(st->terminal, (uint16_t)cols, (uint16_t)rows,
                          (uint32_t)cellWidthPx, (uint32_t)cellHeightPx);
}

JNIEXPORT jint JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeCursorX(JNIEnv *, jclass,
                                                            jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return 0;
  uint16_t x = 0;
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_X, &x);
  return x;
}

JNIEXPORT jint JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeCursorY(JNIEnv *, jclass,
                                                            jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return 0;
  uint16_t y = 0;
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &y);
  return y;
}

JNIEXPORT jint JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeCols(JNIEnv *, jclass,
                                                         jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return 0;
  uint16_t v = 0;
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_COLS, &v);
  return v;
}

JNIEXPORT jint JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeRows(JNIEnv *, jclass,
                                                         jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return 0;
  uint16_t v = 0;
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_ROWS, &v);
  return v;
}

JNIEXPORT jint JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeTotalRows(JNIEnv *, jclass,
                                                              jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return 0;
  size_t v = 0;
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_TOTAL_ROWS, &v);
  return (jint)v;
}

// Return the current title as raw UTF-8 bytes (empty if unset).
JNIEXPORT jbyteArray JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeTitle(JNIEnv *env, jclass,
                                                          jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return NULL;
  GhosttyString title;
  if (ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_TITLE, &title) !=
          GHOSTTY_SUCCESS ||
      !title.ptr)
    return NULL;
  jbyteArray arr = (*env)->NewByteArray(env, (jsize)title.len);
  if (arr && title.len)
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)title.len,
                               (const jbyte *)title.ptr);
  return arr;
}

// Committed text (e.g. IME commitText). Printable text passes through as raw
// UTF-8; it needs no key encoding.
JNIEXPORT void JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeSendText(JNIEnv *env,
                                                             jclass,
                                                             jlong handle,
                                                             jstring text) {
  RemotlyTerm *st = from_handle(handle);
  if (!st || !text) return;
  size_t len = 0;
  uint8_t *utf8 = jstring_to_utf8(env, text, &len);
  if (!utf8 || len == 0) {
    free(utf8);
    return;
  }

  // Committed text still has to go through the key encoder. An application
  // that pushed the Kitty keyboard protocol expects every key as a CSI u
  // sequence, including ordinary printable ones; writing raw UTF-8 instead
  // sends bytes it never parses as keys.
  //
  // The encoder decides: with no protocol active it emits the same bytes that
  // were passed in, so the plain case is unchanged.
  ghostty_key_encoder_setopt_from_terminal(st->encoder, st->terminal);
  encode_text_as_keys(st, utf8, len);
  free(utf8);
}

// Encodes a run of committed UTF-8 as individual key presses.
//
// One event per codepoint: the encoder works a key at a time, and a paste or a
// CJK commit arrives as several. Everything is gathered into one buffer so the
// session sees a single write rather than one per character.
static void encode_text_as_keys(RemotlyTerm *st, const uint8_t *utf8,
                                size_t len) {
  uint8_t *out = NULL;
  size_t out_len = 0;
  size_t out_cap = 0;

  size_t i = 0;
  while (i < len) {
    // Decode one UTF-8 codepoint. The input came from jstring_to_utf8, which
    // only emits well-formed sequences.
    uint32_t cp = 0;
    size_t seq = 1;
    const uint8_t c = utf8[i];
    if (c < 0x80) {
      cp = c;
      seq = 1;
    } else if ((c & 0xE0) == 0xC0 && i + 1 < len) {
      cp = ((uint32_t)(c & 0x1F) << 6) | (utf8[i + 1] & 0x3F);
      seq = 2;
    } else if ((c & 0xF0) == 0xE0 && i + 2 < len) {
      cp = ((uint32_t)(c & 0x0F) << 12) | ((uint32_t)(utf8[i + 1] & 0x3F) << 6) |
           (utf8[i + 2] & 0x3F);
      seq = 3;
    } else if ((c & 0xF8) == 0xF0 && i + 3 < len) {
      cp = ((uint32_t)(c & 0x07) << 18) |
           ((uint32_t)(utf8[i + 1] & 0x3F) << 12) |
           ((uint32_t)(utf8[i + 2] & 0x3F) << 6) | (utf8[i + 3] & 0x3F);
      seq = 4;
    } else {
      // Not a lead byte: skip it rather than emitting a bogus key.
      i += 1;
      continue;
    }

    GhosttyKeyEvent ev;
    if (ghostty_key_event_new(NULL, &ev) != GHOSTTY_SUCCESS) break;
    ghostty_key_event_set_action(ev, GHOSTTY_KEY_ACTION_PRESS);
    ghostty_key_event_set_key(ev, GHOSTTY_KEY_UNIDENTIFIED);
    ghostty_key_event_set_mods(ev, 0);
    ghostty_key_event_set_composing(ev, false);
    // The encoder needs this to build a CSI u sequence; without it a Kitty
    // client receives nothing for an ordinary character.
    ghostty_key_event_set_unshifted_codepoint(ev, cp);
    ghostty_key_event_set_utf8(ev, (const char *)(utf8 + i), seq);

    char buf[512];
    size_t written = 0;
    GhosttyResult r =
        ghostty_key_encoder_encode(st->encoder, ev, buf, sizeof(buf), &written);
    ghostty_key_event_free(ev);

    if (r == GHOSTTY_SUCCESS && written > 0) {
      if (out_len + written > out_cap) {
        size_t next = out_cap == 0 ? 128 : out_cap * 2;
        while (next < out_len + written) next *= 2;
        uint8_t *grown = realloc(out, next);
        if (!grown) break;
        out = grown;
        out_cap = next;
      }
      memcpy(out + out_len, buf, written);
      out_len += written;
    }
    i += seq;
  }

  if (out && out_len > 0) {
    deliver_bytes(st, st->onInput, out, out_len);
  } else {
    free(out);
  }
}

// A single key event. ghosttyKey/ghosttyMods are the GhosttyKey/GhosttyMods
// integer values mapped on the Kotlin side; utf8 is the printable character
// (may be empty for special keys); composing marks an in-composition key.
JNIEXPORT void JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeSendKey(
    JNIEnv *env, jclass, jlong handle, jint ghosttyKey, jint ghosttyMods,
    jstring utf8, jboolean composing) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return;
  ghostty_key_encoder_setopt_from_terminal(st->encoder, st->terminal);

  GhosttyKeyEvent ev;
  if (ghostty_key_event_new(NULL, &ev) != GHOSTTY_SUCCESS) return;
  ghostty_key_event_set_action(ev, GHOSTTY_KEY_ACTION_PRESS);
  ghostty_key_event_set_key(ev, (GhosttyKey)ghosttyKey);
  ghostty_key_event_set_mods(ev, (GhosttyMods)ghosttyMods);
  ghostty_key_event_set_composing(ev, composing);
  if (utf8) {
    size_t len = 0;
    uint8_t *b = jstring_to_utf8(env, utf8, &len);
    if (b && len > 0) {
      // A Kitty client encodes an ordinary character from this, not from the
      // utf8 field, so a printable key needs it set or it encodes to nothing.
      if (b[0] < 0x80) ghostty_key_event_set_unshifted_codepoint(ev, b[0]);
      // Ghostty keeps this pointer on the event until encode completes. The
      // old code freed it here, leaving a use-after-free that was especially
      // visible for multi-byte IME input.
      ghostty_key_event_set_utf8(ev, (const char *)b, len);
    }
    char out[512];
    size_t written = 0;
    GhosttyResult r = ghostty_key_encoder_encode(st->encoder, ev, out,
                                                  sizeof(out), &written);
    free(b);
    ghostty_key_event_free(ev);
    if (r == GHOSTTY_SUCCESS && written > 0) {
      uint8_t *buf = malloc(written);
      if (buf) {
        memcpy(buf, out, written);
        deliver_bytes(st, st->onInput, buf, written);
      }
    }
    return;
  }
  char out[512];
  size_t written = 0;
  GhosttyResult r =
      ghostty_key_encoder_encode(st->encoder, ev, out, sizeof(out), &written);
  ghostty_key_event_free(ev);
  if (r == GHOSTTY_SUCCESS && written > 0) {
    uint8_t *buf = malloc(written);
    if (buf) {
      memcpy(buf, out, written);
      deliver_bytes(st, st->onInput, buf, written);
    }
  }
}

// Scroll the viewport by whole rows. Negative is toward the scrollback.
//
// A terminal application that owns the alternate screen (a TUI such as Claude
// Code, OpenCode, or vim) has no scrollback of its own; libghostty keeps the
// viewport pinned to the active area there, so this is a no-op rather than a
// way to scroll behind the application's own display.
JNIEXPORT void JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeScrollViewport(
    JNIEnv *, jclass, jlong handle, jint deltaRows) {
  RemotlyTerm *st = from_handle(handle);
  if (!st || deltaRows == 0) return;
  GhosttyTerminalScrollViewport behavior;
  behavior.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
  behavior.value.delta = (intptr_t)deltaRows;
  ghostty_terminal_scroll_viewport(st->terminal, behavior);
}

// Pin the viewport back to the active area, as a write from the shell does.
// Encodes a mouse event and writes it to the pty.
//
// The encoder takes its tracking mode and wire format from the terminal, so
// whatever the application asked for (X10, SGR, any-event) is what it gets,
// and an application that asked for nothing produces no bytes at all. That
// last part is what keeps a tap from corrupting the input of a plain shell.
//
// Returns true when the event produced output, so the caller knows the
// application wanted it and the gesture should not also scroll or select.
// Whether the running application asked for mouse reports.
//
// The view needs this before it knows what a touch will become: a press must
// not be sent until the gesture ends without scrolling, but whether to turn a
// drag into wheel reports has to be decided as the drag begins. Encoding a
// throwaway event to find out would write it to the pty.
JNIEXPORT jboolean JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeMouseReporting(JNIEnv *,
                                                                   jclass,
                                                                   jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return JNI_FALSE;
  // Any of the tracking modes means the application is listening. The format
  // modes (1005, 1006, 1015, 1016) only say how to encode, not whether to.
  const GhosttyMode tracking[] = {
      GHOSTTY_MODE_X10_MOUSE,
      GHOSTTY_MODE_NORMAL_MOUSE,
      GHOSTTY_MODE_BUTTON_MOUSE,
      GHOSTTY_MODE_ANY_MOUSE,
  };
  for (size_t i = 0; i < sizeof(tracking) / sizeof(tracking[0]); i++) {
    GhosttyTerminalModeConfig m = {0};
    m.mode = tracking[i];
    m.value = false;
    if (ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_MODE, &m) ==
            GHOSTTY_SUCCESS &&
        m.value) {
      return JNI_TRUE;
    }
  }
  return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeSendMouse(
    JNIEnv *, jclass, jlong handle, jint action, jint button, jint mods,
    jint col, jint row, jint cellWidthPx, jint cellHeightPx) {
  RemotlyTerm *st = from_handle(handle);
  if (!st || !st->mouse_encoder) return JNI_FALSE;

  ghostty_mouse_encoder_setopt_from_terminal(st->mouse_encoder, st->terminal);

  // Tracking mode and format come from the terminal, but the geometry does
  // not: without it the encoder cannot turn a pixel position into a cell and
  // reports everything at the origin.
  uint16_t cols = 0, rows = 0;
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_COLS, &cols);
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
  GhosttyMouseEncoderSize size = {0};
  size.size = sizeof(size);
  size.cell_width = (uint32_t)(cellWidthPx > 0 ? cellWidthPx : 1);
  size.cell_height = (uint32_t)(cellHeightPx > 0 ? cellHeightPx : 1);
  size.screen_width = (uint32_t)cols * size.cell_width;
  size.screen_height = (uint32_t)rows * size.cell_height;
  ghostty_mouse_encoder_setopt(st->mouse_encoder,
                               GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);

  GhosttyMouseEvent event;
  if (ghostty_mouse_event_new(NULL, &event) != GHOSTTY_SUCCESS) return JNI_FALSE;

  ghostty_mouse_event_set_action(event, (GhosttyMouseAction)action);
  if (button >= 0) {
    ghostty_mouse_event_set_button(event, (GhosttyMouseButton)button);
  } else {
    ghostty_mouse_event_clear_button(event);
  }
  ghostty_mouse_event_set_mods(event, (GhosttyMods)mods);

  // The encoder works in surface pixels and derives the cell from them, so a
  // cell is reported at its centre rather than its corner.
  GhosttyMousePosition pos;
  pos.x = (float)col * (float)cellWidthPx + (float)cellWidthPx / 2.0f;
  pos.y = (float)row * (float)cellHeightPx + (float)cellHeightPx / 2.0f;
  ghostty_mouse_event_set_position(event, pos);

  char buf[128];
  size_t written = 0;
  GhosttyResult r = ghostty_mouse_encoder_encode(st->mouse_encoder, event, buf,
                                                 sizeof(buf), &written);
  ghostty_mouse_event_free(event);
  if (r != GHOSTTY_SUCCESS || written == 0) return JNI_FALSE;

  on_pty_write(st->terminal, st, (const uint8_t *)buf, written);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeScrollToBottom(JNIEnv *,
                                                                   jclass,
                                                                   jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return;
  GhosttyTerminalScrollViewport behavior;
  behavior.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
  behavior.value.delta = 0;
  ghostty_terminal_scroll_viewport(st->terminal, behavior);
}

// Scrollbar geometry, packed as [total, offset, len] in rows.
//
// There is no change notification for this, so the view polls it per frame and
// diffs, which is what Ghostty's own renderer does.
JNIEXPORT jlongArray JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeScrollbar(JNIEnv *env,
                                                              jclass,
                                                              jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return NULL;
  GhosttyTerminalScrollbar bar;
  if (ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR,
                           &bar) != GHOSTTY_SUCCESS)
    return NULL;
  jlongArray arr = (*env)->NewLongArray(env, 3);
  if (!arr) return NULL;
  jlong values[3] = {(jlong)bar.total, (jlong)bar.offset, (jlong)bar.len};
  (*env)->SetLongArrayRegion(env, arr, 0, 3, values);
  return arr;
}

// Select a range between two viewport cells, inclusive.
//
// Coordinates are viewport-relative, which is what a touch on the rendered
// grid gives. Both ends resolve through the viewport tag so a selection made
// while scrolled back refers to the rows the user actually touched.
JNIEXPORT jboolean JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeSelectRange(
    JNIEnv *, jclass, jlong handle, jint startX, jint startY, jint endX,
    jint endY, jboolean rectangle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return JNI_FALSE;
  if (startX < 0 || startY < 0 || endX < 0 || endY < 0) return JNI_FALSE;

  GhosttyPoint start_point;
  start_point.tag = GHOSTTY_POINT_TAG_VIEWPORT;
  start_point.value.coordinate.x = (uint16_t)startX;
  start_point.value.coordinate.y = (uint32_t)startY;

  GhosttyPoint end_point;
  end_point.tag = GHOSTTY_POINT_TAG_VIEWPORT;
  end_point.value.coordinate.x = (uint16_t)endX;
  end_point.value.coordinate.y = (uint32_t)endY;

  GhosttyGridRef start_ref;
  GhosttyGridRef end_ref;
  if (ghostty_terminal_grid_ref(st->terminal, start_point, &start_ref) !=
          GHOSTTY_SUCCESS ||
      ghostty_terminal_grid_ref(st->terminal, end_point, &end_ref) !=
          GHOSTTY_SUCCESS)
    return JNI_FALSE;

  GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
  sel.start = start_ref;
  sel.end = end_ref;
  sel.rectangle = rectangle == JNI_TRUE;
  if (ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_SELECTION,
                           &sel) != GHOSTTY_SUCCESS)
    return JNI_FALSE;
  return JNI_TRUE;
}

// Word bounds under a viewport cell, as [startX, startY, endX, endY].
//
// Uses the terminal's own word-boundary rules rather than reimplementing them
// against the rendered frame, so a long press selects what the terminal itself
// considers a word. Returns NULL when the cell holds nothing selectable.
JNIEXPORT jintArray JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeSelectWord(JNIEnv *env,
                                                               jclass,
                                                               jlong handle,
                                                               jint col,
                                                               jint row) {
  RemotlyTerm *st = from_handle(handle);
  if (!st || col < 0 || row < 0) return NULL;

  GhosttyPoint point;
  point.tag = GHOSTTY_POINT_TAG_VIEWPORT;
  point.value.coordinate.x = (uint16_t)col;
  point.value.coordinate.y = (uint32_t)row;

  GhosttyGridRef ref;
  if (ghostty_terminal_grid_ref(st->terminal, point, &ref) != GHOSTTY_SUCCESS)
    return NULL;

  GhosttyTerminalSelectWordOptions opts =
      GHOSTTY_INIT_SIZED(GhosttyTerminalSelectWordOptions);
  opts.ref = ref;
  GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
  if (ghostty_terminal_select_word(st->terminal, &opts, &sel) !=
      GHOSTTY_SUCCESS)
    return NULL;

  // Back to viewport coordinates. A word running off the visible area is not
  // representable there, so the caller falls back to the touched cell.
  GhosttyPointCoordinate start_c;
  GhosttyPointCoordinate end_c;
  if (ghostty_terminal_point_from_grid_ref(st->terminal, &sel.start,
                                           GHOSTTY_POINT_TAG_VIEWPORT,
                                           &start_c) != GHOSTTY_SUCCESS ||
      ghostty_terminal_point_from_grid_ref(st->terminal, &sel.end,
                                           GHOSTTY_POINT_TAG_VIEWPORT,
                                           &end_c) != GHOSTTY_SUCCESS)
    return NULL;

  jintArray arr = (*env)->NewIntArray(env, 4);
  if (!arr) return NULL;
  jint values[4] = {(jint)start_c.x, (jint)start_c.y, (jint)end_c.x,
                    (jint)end_c.y};
  (*env)->SetIntArrayRegion(env, arr, 0, 4, values);
  return arr;
}

// Drop the active selection.
JNIEXPORT void JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeClearSelection(JNIEnv *,
                                                                   jclass,
                                                                   jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return;
  ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
}

// Install a select-all as the terminal's active selection.
JNIEXPORT void JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeSelectAll(JNIEnv *, jclass,
                                                              jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return;
  GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
  if (ghostty_terminal_select_all(st->terminal, &sel) == GHOSTTY_SUCCESS)
    ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
}

// --- Frame serialization for the Canvas renderer ---------------------------
//
// The frame is a little-endian byte buffer:
//   header: u16 cols, u16 rows, u16 cursor_x, u16 cursor_y, u8 cursor_visible,
//           u8 pad, u8[3] default_bg, u8[3] default_fg
//   then rows*cols cell records, row-major (y outer, x inner):
//     u8 wide (0 narrow,1 wide,2 spacer_tail,3 wrap_spacer)
//     u8 style_flags (bit0 bold,1 italic,2 underline,3 inverse,4 faint,
//                     5 strikethrough,6 selected)
//     u8[3] fg, u8[3] bg
//     u8 utf8_len, u8[utf8_len]
//
// The caller supplies a direct ByteBuffer and keeps it across frames. Returns
// the number of bytes written, 0 when no frame could be produced, or the
// negated required capacity when the buffer is too small, which the caller
// answers by growing it and asking again.
JNIEXPORT jint JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeGetFrame(JNIEnv *env,
                                                             jclass,
                                                             jlong handle,
                                                             jobject dst) {
  RemotlyTerm *st = from_handle(handle);
  if (!st || !dst) return 0;
  uint8_t *buf = (*env)->GetDirectBufferAddress(env, dst);
  jlong dst_cap = (*env)->GetDirectBufferCapacity(env, dst);
  if (!buf || dst_cap <= 0) return 0;
  if (ghostty_render_state_update(st->render_state, st->terminal) !=
      GHOSTTY_SUCCESS)
    return 0;

  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(st->render_state, GHOSTTY_RENDER_STATE_DATA_COLS,
                           &cols);
  ghostty_render_state_get(st->render_state, GHOSTTY_RENDER_STATE_DATA_ROWS,
                           &rows);

  GhosttyRenderStateColors pal = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  ghostty_render_state_get(st->render_state, GHOSTTY_RENDER_STATE_DATA_COLORS,
                           &pal);

  uint16_t cx = 0, cy = 0;
  bool cursor_visible = false;
  ghostty_render_state_get(st->render_state,
                           GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X, &cx);
  ghostty_render_state_get(st->render_state,
                           GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y, &cy);
  ghostty_render_state_get(st->render_state,
                           GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE,
                           &cursor_visible);

  // A frame costs 73 bytes per cell. Refuse absurd grids instead of asking for
  // a gigabyte of buffer on every draw.
  if (cols == 0 || rows == 0) return 0;
  if ((size_t)cols * rows > REMOTLY_MAX_CELLS) return 0;
  size_t cell_cap = 9 + 64;  // fixed 9 bytes + up to 64 utf8 bytes
  size_t cap = 16 + (size_t)cols * rows * cell_cap;
  if (cap > (size_t)dst_cap) return -(jint)cap;
  size_t o = 0;
  #define PUT8(v)  do { if (o + 1 <= cap) buf[o++] = (uint8_t)(v); } while (0)
  #define PUT16(v)                                                              \
    do {                                                                        \
      if (o + 2 <= cap) { buf[o++] = (uint8_t)((v) & 0xff);                     \
                          buf[o++] = (uint8_t)(((v) >> 8) & 0xff); }            \
    } while (0)
  #define PUT3(v)                                                               \
    do {                                                                        \
      if (o + 3 <= cap) { buf[o++] = (v).r; buf[o++] = (v).g; buf[o++] = (v).b; } \
    } while (0)

  PUT16(cols);
  PUT16(rows);
  PUT16(cx);
  PUT16(cy);
  PUT8(cursor_visible ? 1 : 0);
  PUT8(0);
  PUT3(pal.background);
  PUT3(pal.foreground);

  // The iterator and cells handles are populated in place, so these take the
  // address of the handle, not the handle value.
  if (ghostty_render_state_get(st->render_state,
                               GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                               &st->row_iter) != GHOSTTY_SUCCESS) {
    return 0;
  }
  // The reader addresses cells as y * cols + x, so exactly cols * rows records
  // must be written. The iterators carry no such guarantee: a row whose cells
  // cannot be read yields none, and a row can end short of cols. Emitting what
  // the iterator happens to produce shifts every later row by the shortfall,
  // which is a screen drawn from the wrong offsets, and a frame that ends short
  // is rejected outright and leaves the previous tab's screen on display.
  //
  // So every row is padded to cols and the grid to rows, with blanks in the
  // default colors: the same thing an empty cell would have serialized as.
  #define PUT_BLANK_CELL()                                                      \
    do {                                                                        \
      PUT8(GHOSTTY_CELL_WIDE_NARROW);                                           \
      PUT8(0);                                                                  \
      PUT3(pal.foreground);                                                     \
      PUT3(pal.background);                                                     \
      PUT8(0);                                                                  \
    } while (0)

  uint16_t y = 0;
  while (y < rows && ghostty_render_state_row_iterator_next(st->row_iter)) {
    if (ghostty_render_state_row_get(st->row_iter,
                                     GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                                     &st->row_cells) != GHOSTTY_SUCCESS) {
      for (uint16_t x = 0; x < cols; x++) PUT_BLANK_CELL();
      y++;
      continue;
    }
    uint16_t x = 0;
    while (x < cols && ghostty_render_state_row_cells_next(st->row_cells)) {
      // On GHOSTTY_OUT_OF_SPACE the call reports the length it would have
      // needed without writing anything, so the buffer still holds garbage.
      // Only a successful read may be trusted; anything else emits no text.
      uint8_t utf8[64];
      GhosttyBuffer gb = {.ptr = utf8, .cap = sizeof(utf8), .len = 0};
      if (ghostty_render_state_row_cells_get(
              st->row_cells,
              GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8,
              &gb) != GHOSTTY_SUCCESS ||
          gb.len > sizeof(utf8)) {
        gb.len = 0;
      }

      GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
      ghostty_render_state_row_cells_get(
          st->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);

      GhosttyColorRgb fg = pal.foreground, bg = pal.background;
      if (ghostty_render_state_row_cells_get(
              st->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
              &fg) != GHOSTTY_SUCCESS)
        fg = pal.foreground;
      if (ghostty_render_state_row_cells_get(
              st->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
              &bg) != GHOSTTY_SUCCESS)
        bg = pal.background;

      GhosttyCell raw = 0;
      ghostty_render_state_row_cells_get(
          st->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw);
      GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
      ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);

      bool selected = false;
      ghostty_render_state_row_cells_get(
          st->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED,
          &selected);

      uint8_t flags = 0;
      if (style.bold) flags |= 1 << 0;
      if (style.italic) flags |= 1 << 1;
      if (style.underline) flags |= 1 << 2;
      if (style.inverse) flags |= 1 << 3;
      if (style.faint) flags |= 1 << 4;
      if (style.strikethrough) flags |= 1 << 5;
      if (selected) flags |= 1 << 6;

      PUT8(wide);
      PUT8(flags);
      PUT3(fg);
      PUT3(bg);
      PUT8(gb.len);
      if (o + gb.len <= cap) {
        memcpy(buf + o, utf8, gb.len);
        o += gb.len;
      }
      x++;
    }
    // Short row: pad to the declared width.
    while (x < cols) {
      PUT_BLANK_CELL();
      x++;
    }
    y++;
  }
  // Short grid: pad to the declared height.
  while (y < rows) {
    for (uint16_t x = 0; x < cols; x++) PUT_BLANK_CELL();
    y++;
  }

  #undef PUT_BLANK_CELL
  #undef PUT8
  #undef PUT16
  #undef PUT3

  return (jint)o;
}

// Return the active selection formatted as plain text, or NULL if there is no
// selection. Caller frees the returned jbyteArray as a local ref (it is
// returned to Java).
JNIEXPORT jbyteArray JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeGetSelectionText(
    JNIEnv *env, jclass, jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return NULL;
  GhosttyTerminalSelectionFormatOptions opts =
      GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
  opts.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  opts.trim = true;
  uint8_t *ptr = NULL;
  size_t len = 0;
  GhosttyResult r = ghostty_terminal_selection_format_alloc(
      st->terminal, NULL, opts, &ptr, &len);
  if (r != GHOSTTY_SUCCESS || !ptr) return NULL;
  jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
  if (arr)
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)ptr);
  ghostty_free(NULL, ptr, len);
  return arr;
}

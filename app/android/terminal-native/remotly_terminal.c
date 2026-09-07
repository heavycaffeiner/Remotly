// JNI bridge for the Remotly terminal module (M1-09). Wraps libghostty-vt
// (pinned in app/android/ghostty/PIN.txt) behind a small, thread-confined C
// API that the Kotlin layer drives from the main thread.
//
// Data flow:
//   output (session -> app): nativeWrite() feeds bytes into the terminal.
//   input  (app -> session): nativeSendText()/nativeSendKey()/nativePasteText()
//     encode input and report it to the app via the listener's onInput(); the
//     app forwards it to the SSH session, which writes the PTY.
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

// Decoded pixels a terminal may hold for Kitty graphics. An image costs
// width * height * 4 bytes resident, so this is bounded well below the
// scrollback: a handful of full-screen images rather than an unbounded set.
#define REMOTLY_MAX_IMAGE_BYTES ((size_t)32 * 1024 * 1024)

// Visible image placements serialized per frame, and the ints each carries.
// Far above any real screen: a placement occupies grid cells, so a phone
// viewport cannot hold many.
#define REMOTLY_MAX_PLACEMENTS 64
#define REMOTLY_PLACEMENT_FIELDS 12

// Pixels converted per JNI region call when copying an image out.
#define REMOTLY_PIXEL_BLOCK 4096

// Longest link returned, and the widest row scanned for a bare URL. Both are
// bounded because the row is read into a fixed buffer on the stack.
#define REMOTLY_MAX_URL 2048
#define REMOTLY_MAX_COLS_SCAN 512

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
  jmethodID onNotify;
  jmethodID onClipboardWrite;
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

// Builds a Java String from a borrowed GhosttyString, which is not NUL
// terminated and may be empty.
static jstring to_jstring(JNIEnv *env, GhosttyString s) {
  if (!s.ptr || s.len == 0) return (*env)->NewStringUTF(env, "");
  char *copy = malloc(s.len + 1);
  if (!copy) return (*env)->NewStringUTF(env, "");
  memcpy(copy, s.ptr, s.len);
  copy[s.len] = '\0';
  jstring out = (*env)->NewStringUTF(env, copy);
  free(copy);
  return out;
}

// A desktop notification requested with OSC 9 or OSC 777.
//
// The library parses both and normalizes them to this one shape: OSC 9 has a
// body and no title, OSC 777 carries both.
static void on_desktop_notification(
    GhosttyTerminal terminal, void *userdata,
    const GhosttyTerminalDesktopNotification *notification) {
  (void)terminal;
  RemotlyTerm *st = (RemotlyTerm *)userdata;
  JNIEnv *env = get_env(st);
  if (!env || !notification || !st->onNotify) return;
  // Sized struct: only fields the reported size covers may be read.
  if (notification->size < sizeof(GhosttyTerminalDesktopNotification)) return;
  jstring title = to_jstring(env, notification->title);
  jstring body = to_jstring(env, notification->body);
  if (title && body) {
    (*env)->CallVoidMethod(env, st->listener, st->onNotify, title, body);
  }
  if (title) (*env)->DeleteLocalRef(env, title);
  if (body) (*env)->DeleteLocalRef(env, body);
}

// A clipboard write requested with OSC 52 or iTerm2's OSC 1337 Copy.
//
// The library decodes and normalizes both to the same shape. Only the plain
// text representation is taken: Android's clipboard is what receives this, and
// putting arbitrary MIME data there is not something a terminal should do
// unasked.
static GhosttyClipboardWriteResult on_clipboard_write(
    GhosttyTerminal terminal, void *userdata,
    const GhosttyClipboardWrite *write) {
  (void)terminal;
  RemotlyTerm *st = (RemotlyTerm *)userdata;
  JNIEnv *env = get_env(st);
  if (!env || !write || !st->onClipboardWrite) {
    return GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
  }
  // Sized struct: only fields the reported size covers may be read.
  if (write->size < sizeof(GhosttyClipboardWrite)) {
    return GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
  }
  // A zero-length contents array asks for the destination to be cleared, which
  // is not something a remote program should be able to do to the device
  // clipboard unasked.
  for (size_t i = 0; i < write->contents_len; i++) {
    const GhosttyClipboardContent *rep = &write->contents[i];
    if (!rep->mime.ptr || rep->mime.len != 10 ||
        memcmp(rep->mime.ptr, "text/plain", 10) != 0) {
      continue;
    }
    jstring text = to_jstring(env, rep->data);
    if (!text) return GHOSTTY_CLIPBOARD_WRITE_RESULT_IO_ERROR;
    (*env)->CallVoidMethod(env, st->listener, st->onClipboardWrite, text);
    (*env)->DeleteLocalRef(env, text);
    return GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
  }
  return GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
}

static RemotlyTerm *from_handle(jlong handle) {
  return (RemotlyTerm *)(uintptr_t)handle;
}

// --- Kitty graphics ---------------------------------------------------------

// The JVM, kept process-wide so the PNG decoder can attach.
//
// The decoder is installed once via ghostty_sys_set and is not tied to any one
// terminal, so it cannot reach a RemotlyTerm for its JavaVM.
static JavaVM *g_jvm = NULL;

/**
 * Decodes a PNG to RGBA for the Kitty graphics protocol.
 *
 * Android has no PNG decoder in the NDK, so this goes back through JNI to
 * BitmapFactory. The pixels are copied out with the allocator libghostty
 * supplied, which then owns and frees them.
 *
 * Runs on whichever thread wrote to the terminal, which is the main thread for
 * this app, but the JNIEnv is fetched rather than assumed for that reason.
 */
static bool decode_png(void *userdata, const GhosttyAllocator *allocator,
                       const uint8_t *data, size_t data_len,
                       GhosttySysImage *out) {
  (void)userdata;
  if (!g_jvm || !data || data_len == 0 || !out) return false;

  JNIEnv *env = NULL;
  if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, REMOTLY_JNI_VERSION) != JNI_OK) {
    return false;
  }

  jclass cls = (*env)->FindClass(env, "com/remotly/app/terminal/TerminalImage");
  if (!cls) {
    (*env)->ExceptionClear(env);
    return false;
  }
  jmethodID decode = (*env)->GetStaticMethodID(env, cls, "decodePng", "([B)[I");
  if (!decode) {
    (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, cls);
    return false;
  }

  jbyteArray src = (*env)->NewByteArray(env, (jsize)data_len);
  if (!src) {
    (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, cls);
    return false;
  }
  (*env)->SetByteArrayRegion(env, src, 0, (jsize)data_len, (const jbyte *)data);

  jintArray result = (jintArray)(*env)->CallStaticObjectMethod(env, cls, decode, src);
  (*env)->DeleteLocalRef(env, src);
  (*env)->DeleteLocalRef(env, cls);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return false;
  }
  if (!result) return false;

  // Layout: [width, height, then width*height pixels as ARGB_8888].
  jsize len = (*env)->GetArrayLength(env, result);
  if (len < 2) {
    (*env)->DeleteLocalRef(env, result);
    return false;
  }
  jint header[2] = {0, 0};
  (*env)->GetIntArrayRegion(env, result, 0, 2, header);
  uint32_t width = (uint32_t)header[0];
  uint32_t height = (uint32_t)header[1];
  if (width == 0 || height == 0 ||
      (jsize)(2 + (size_t)width * height) != len) {
    (*env)->DeleteLocalRef(env, result);
    return false;
  }

  size_t pixels = (size_t)width * height;
  size_t bytes = pixels * 4;
  uint8_t *rgba = ghostty_alloc(allocator, bytes);
  if (!rgba) {
    (*env)->DeleteLocalRef(env, result);
    return false;
  }

  jint *argb = (*env)->GetIntArrayElements(env, result, NULL);
  if (!argb) {
    ghostty_free(allocator, rgba, bytes);
    (*env)->DeleteLocalRef(env, result);
    return false;
  }
  // Bitmap.getPixels gives ARGB packed in host order; the protocol wants RGBA
  // byte order.
  for (size_t i = 0; i < pixels; i++) {
    uint32_t p = (uint32_t)argb[2 + i];
    rgba[i * 4 + 0] = (uint8_t)((p >> 16) & 0xff);
    rgba[i * 4 + 1] = (uint8_t)((p >> 8) & 0xff);
    rgba[i * 4 + 2] = (uint8_t)(p & 0xff);
    rgba[i * 4 + 3] = (uint8_t)((p >> 24) & 0xff);
  }
  (*env)->ReleaseIntArrayElements(env, result, argb, JNI_ABORT);
  (*env)->DeleteLocalRef(env, result);

  out->width = width;
  out->height = height;
  out->data = rgba;
  out->data_len = bytes;
  return true;
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

  // Kitty graphics. Storage is off until a limit is set, and PNG payloads are
  // rejected until a decoder is installed. Both are needed for an image to
  // survive transmission.
  //
  // The decoder is process-global rather than per terminal, so it is installed
  // once. Setting it again is harmless: the same pointer replaces itself.
  (*env)->GetJavaVM(env, &g_jvm);
  GhosttySysDecodePngFn png = decode_png;
  ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG, (const void *)png);

  // Bounded per terminal, like the scrollback. An image is decoded pixels, so
  // a few full-screen ones cost more than the entire text history.
  size_t image_cap = REMOTLY_MAX_IMAGE_BYTES;
  ghostty_terminal_set(st->terminal,
                       GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT,
                       &image_cap);

  (*env)->GetJavaVM(env, &st->jvm);
  st->listener = (*env)->NewGlobalRef(env, listener);
  jclass lcls = (*env)->GetObjectClass(env, listener);
  st->onBell = (*env)->GetMethodID(env, lcls, "onBell", "()V");
  st->onTitle = (*env)->GetMethodID(env, lcls, "onTitle", "([B)V");
  st->onInput = (*env)->GetMethodID(env, lcls, "onInput", "([B)V");
  st->onPtyWrite = (*env)->GetMethodID(env, lcls, "onPtyWrite", "([B)V");
  st->onNotify = (*env)->GetMethodID(
      env, lcls, "onNotify", "(Ljava/lang/String;Ljava/lang/String;)V");
  st->onClipboardWrite = (*env)->GetMethodID(env, lcls, "onClipboardWrite",
                                             "(Ljava/lang/String;)V");

  ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, st);
  ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_BELL,
                       (const void *)on_bell);
  ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED,
                       (const void *)on_title_changed);
  ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY,
                       (const void *)on_pty_write);
  // OSC 9 and OSC 777 both arrive here; the library normalizes them.
  ghostty_terminal_set(st->terminal,
                       GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION,
                       (const void *)on_desktop_notification);
  // OSC 52 and iTerm2's OSC 1337 Copy.
  ghostty_terminal_set(st->terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE,
                       (const void *)on_clipboard_write);
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

// Pasted text, encoded the way the running application expects to receive it.
//
// Bracketed paste is the part that matters. With mode 2004 set, a shell or an
// editor reads the wrapped block as one literal insertion; without the
// wrapper, every newline in the block is an Enter, so a multi-line paste runs
// each line as a command and an editor applies autoindent to all of them.
// ghostty_paste_encode also strips control bytes that could smuggle an escape
// sequence through a paste, and rewrites newlines as carriage returns when the
// application did not ask for bracketing.
//
// This deliberately bypasses the key encoder: a paste is a block of text, not
// a run of keystrokes, and encoding it per codepoint is what produced the
// broken multi-line result.
JNIEXPORT void JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativePasteText(JNIEnv *env,
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

  GhosttyTerminalModeConfig bracketed = {0};
  bracketed.mode = GHOSTTY_MODE_BRACKETED_PASTE;
  bracketed.value = false;
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_MODE, &bracketed);

  // The encoder needs room for the bracketing sequences on top of the data. It
  // reports the required size rather than truncating, so a short buffer is
  // retried at the size it asks for instead of guessing again.
  size_t cap = len + 16;
  char *out = malloc(cap);
  if (!out) {
    free(utf8);
    return;
  }
  size_t written = 0;
  GhosttyResult r = ghostty_paste_encode((char *)utf8, len, bracketed.value,
                                         out, cap, &written);
  if (r == GHOSTTY_OUT_OF_SPACE && written > 0) {
    char *grown = realloc(out, written);
    if (!grown) {
      free(out);
      free(utf8);
      return;
    }
    out = grown;
    cap = written;
    // The first call modifies data in place, so the retry re-reads the buffer
    // it already normalized. That is idempotent: stripping runs again over
    // bytes that carry nothing left to strip.
    r = ghostty_paste_encode((char *)utf8, len, bracketed.value, out, cap,
                             &written);
  }
  free(utf8);

  if (r != GHOSTTY_SUCCESS || written == 0) {
    free(out);
    return;
  }

  uint8_t *delivered = malloc(written);
  if (!delivered) {
    free(out);
    return;
  }
  memcpy(delivered, out, written);
  free(out);
  deliver_bytes(st, st->onInput, delivered, written);
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
  // Size the frame before updating. Updating consumes the terminal's dirty
  // state, so a capacity miss that returned after it would spend that state on
  // a frame the caller never receives; the retry would then update again and
  // find nothing dirty left. The grid is read from the terminal here for that
  // reason, and the render state is only updated once the buffer is known to
  // be big enough to hold the result.
  uint16_t cols = 0, rows = 0;
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_COLS, &cols);
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
  if (cols == 0 || rows == 0) return 0;
  if ((size_t)cols * rows > REMOTLY_MAX_CELLS) return 0;
  // A frame costs 9 fixed bytes plus up to 64 of UTF-8 per cell.
  size_t cell_cap = 9 + 64;
  size_t cap = 16 + (size_t)cols * rows * cell_cap;
  if (cap > (size_t)dst_cap) return -(jint)cap;

  if (ghostty_render_state_update(st->render_state, st->terminal) !=
      GHOSTTY_SUCCESS)
    return 0;

  // Re-read from the render state: the rows below are iterated from it, and
  // its grid is the one they belong to. The two normally agree, since the
  // update was taken from this same terminal a moment ago.
  //
  // A disagreement cannot ask the caller for a bigger buffer: the update has
  // already consumed the terminal's dirty state, so a retry would update again
  // and find nothing dirty. Serializing a grid larger than the buffer is not
  // an option either. The frame is dropped instead, which leaves the last good
  // screen on display, and the state is cleaned so the next draw starts from a
  // consistent point rather than inheriting half-consumed flags.
  uint16_t state_cols = 0, state_rows = 0;
  ghostty_render_state_get(st->render_state, GHOSTTY_RENDER_STATE_DATA_COLS,
                           &state_cols);
  ghostty_render_state_get(st->render_state, GHOSTTY_RENDER_STATE_DATA_ROWS,
                           &state_rows);
  if (state_cols == 0 || state_rows == 0) {
    ghostty_render_state_clean(st->render_state);
    return 0;
  }
  if ((size_t)state_cols * state_rows > REMOTLY_MAX_CELLS ||
      16 + (size_t)state_cols * state_rows * cell_cap > (size_t)dst_cap) {
    ghostty_render_state_clean(st->render_state);
    return 0;
  }
  cols = state_cols;
  rows = state_rows;
  cap = 16 + (size_t)cols * rows * cell_cap;

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

  // The frame reached the caller intact, so the dirty state it was built from
  // is spent. Leaving it set makes the next update see stale per-row flags on
  // top of the new ones.
  ghostty_render_state_clean(st->render_state);

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

// --- Kitty graphics placements ---------------------------------------------

// Returns the visible image placements as a flat int array, or NULL when the
// screen holds none.
//
// Layout, repeated per placement:
//   imageId, generation, viewportCol, viewportRow, gridCols, gridRows,
//   pixelWidth, pixelHeight, sourceX, sourceY, sourceWidth, sourceHeight
//
// Geometry only. The pixels are fetched separately by image id, so a placement
// that merely moved (scrolling) costs no pixel copy. The generation lets the
// caller tell a moved image from a replaced one holding the same id.
JNIEXPORT jintArray JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativePlacements(JNIEnv *env,
                                                               jclass,
                                                               jlong handle) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return NULL;

  GhosttyKittyGraphics graphics = NULL;
  if (ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,
                           &graphics) != GHOSTTY_SUCCESS ||
      !graphics) {
    return NULL;
  }

  uint64_t generation = 0;
  if (ghostty_kitty_graphics_get(graphics,
                                 GHOSTTY_KITTY_GRAPHICS_DATA_GENERATION,
                                 &generation) != GHOSTTY_SUCCESS ||
      generation == 0) {
    // Never mutated, so the storage is empty.
    return NULL;
  }

  GhosttyKittyGraphicsPlacementIterator it = NULL;
  if (ghostty_kitty_graphics_placement_iterator_new(NULL, &it) !=
      GHOSTTY_SUCCESS) {
    return NULL;
  }
  if (ghostty_kitty_graphics_get(
          graphics, GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR, &it) !=
      GHOSTTY_SUCCESS) {
    ghostty_kitty_graphics_placement_iterator_free(it);
    return NULL;
  }

  jint scratch[REMOTLY_MAX_PLACEMENTS * REMOTLY_PLACEMENT_FIELDS];
  size_t count = 0;
  while (count < REMOTLY_MAX_PLACEMENTS &&
         ghostty_kitty_graphics_placement_next(it)) {
    uint32_t image_id = 0;
    if (ghostty_kitty_graphics_placement_get(
            it, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID, &image_id) !=
        GHOSTTY_SUCCESS) {
      continue;
    }
    GhosttyKittyGraphicsImage image =
        ghostty_kitty_graphics_image(graphics, image_id);
    if (!image) continue;

    GhosttyKittyGraphicsPlacementRenderInfo info =
        GHOSTTY_INIT_SIZED(GhosttyKittyGraphicsPlacementRenderInfo);
    if (ghostty_kitty_graphics_placement_render_info(it, image, st->terminal,
                                                     &info) !=
            GHOSTTY_SUCCESS ||
        !info.viewport_visible) {
      // Off-screen or a virtual (unicode placeholder) placement, which this
      // renderer does not draw.
      continue;
    }

    uint64_t image_generation = 0;
    ghostty_kitty_graphics_image_get(
        image, GHOSTTY_KITTY_IMAGE_DATA_GENERATION, &image_generation);

    jint *row = &scratch[count * REMOTLY_PLACEMENT_FIELDS];
    row[0] = (jint)image_id;
    // Truncated to 32 bits: the stamp is only compared for equality against
    // the previous frame's value, so the low bits are enough to spot a change.
    row[1] = (jint)(image_generation & 0xffffffffu);
    row[2] = (jint)info.viewport_col;
    row[3] = (jint)info.viewport_row;
    row[4] = (jint)info.grid_cols;
    row[5] = (jint)info.grid_rows;
    row[6] = (jint)info.pixel_width;
    row[7] = (jint)info.pixel_height;
    row[8] = (jint)info.source_x;
    row[9] = (jint)info.source_y;
    row[10] = (jint)info.source_width;
    row[11] = (jint)info.source_height;
    count++;
  }
  ghostty_kitty_graphics_placement_iterator_free(it);
  if (count == 0) return NULL;

  jintArray out =
      (*env)->NewIntArray(env, (jsize)(count * REMOTLY_PLACEMENT_FIELDS));
  if (out) {
    (*env)->SetIntArrayRegion(env, out, 0,
                              (jsize)(count * REMOTLY_PLACEMENT_FIELDS),
                              scratch);
  }
  return out;
}

// Returns an image's decoded pixels as ARGB ints, or NULL when the id is not
// stored. Sized width * height, which the caller already knows from the
// placement's source rectangle.
JNIEXPORT jintArray JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeImagePixels(JNIEnv *env,
                                                                jclass,
                                                                jlong handle,
                                                                jint imageId) {
  RemotlyTerm *st = from_handle(handle);
  if (!st) return NULL;

  GhosttyKittyGraphics graphics = NULL;
  if (ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,
                           &graphics) != GHOSTTY_SUCCESS ||
      !graphics) {
    return NULL;
  }
  GhosttyKittyGraphicsImage image =
      ghostty_kitty_graphics_image(graphics, (uint32_t)imageId);
  if (!image) return NULL;

  uint32_t width = 0, height = 0;
  const uint8_t *data = NULL;
  size_t data_len = 0;
  GhosttyKittyImageFormat format = GHOSTTY_KITTY_IMAGE_FORMAT_RGBA;
  if (ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_WIDTH,
                                       &width) != GHOSTTY_SUCCESS ||
      ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_HEIGHT,
                                       &height) != GHOSTTY_SUCCESS ||
      ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_FORMAT,
                                       &format) != GHOSTTY_SUCCESS ||
      ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR,
                                       &data) != GHOSTTY_SUCCESS ||
      ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN,
                                       &data_len) != GHOSTTY_SUCCESS ||
      !data || width == 0 || height == 0) {
    // A pending payload reports metadata without pixels; the caller retries on
    // a later frame once the generation moves.
    return NULL;
  }

  size_t pixels = (size_t)width * height;
  size_t bpp = format == GHOSTTY_KITTY_IMAGE_FORMAT_RGB ? 3 : 4;
  if (data_len < pixels * bpp) return NULL;

  jintArray out = (*env)->NewIntArray(env, (jsize)(pixels + 2));
  if (!out) return NULL;
  jint header[2] = {(jint)width, (jint)height};
  (*env)->SetIntArrayRegion(env, out, 0, 2, header);

  // Converted in blocks rather than per pixel: a full-screen image is millions
  // of cells and a JNI region call each would dominate the frame.
  jint block[REMOTLY_PIXEL_BLOCK];
  size_t done = 0;
  while (done < pixels) {
    size_t n = pixels - done;
    if (n > REMOTLY_PIXEL_BLOCK) n = REMOTLY_PIXEL_BLOCK;
    for (size_t i = 0; i < n; i++) {
      const uint8_t *p = data + (done + i) * bpp;
      uint32_t a = bpp == 4 ? p[3] : 0xffu;
      block[i] = (jint)((a << 24) | ((uint32_t)p[0] << 16) |
                        ((uint32_t)p[1] << 8) | (uint32_t)p[2]);
    }
    (*env)->SetIntArrayRegion(env, out, (jsize)(2 + done), (jsize)n, block);
    done += n;
  }
  return out;
}

// --- Links ------------------------------------------------------------------

// True for a byte that may appear in a URL as typed into a terminal.
//
// Deliberately narrow: control bytes and spaces are what delimit a URL on a
// screen, and the quoting characters around one are never part of it.
static bool url_byte(uint8_t c) {
  if (c <= 0x20 || c >= 0x7f) return false;
  switch (c) {
    case '"': case '\'': case '<': case '>':
    case '`': case '\\': case '{': case '}':
    case '|': case '^':
      return false;
    default:
      return true;
  }
}

// Reads one viewport row as ASCII, filling col_offset with the byte position of
// each column so a hit maps back to the cell that was tapped.
//
// Only the primary codepoint of each cell is taken, and anything outside ASCII
// becomes a space: a URL is ASCII by the time it is one, and this keeps the
// mapping exactly one byte per column.
static size_t read_row_ascii(RemotlyTerm *st, uint32_t row, uint16_t cols,
                             uint8_t *out, size_t *col_offset) {
  size_t len = 0;
  for (uint16_t x = 0; x < cols; x++) {
    col_offset[x] = len;
    GhosttyPoint point = {0};
    point.tag = GHOSTTY_POINT_TAG_VIEWPORT;
    point.value.coordinate.x = x;
    point.value.coordinate.y = row;
    GhosttyGridRef ref = {0};
    uint32_t cps[8];
    size_t n = 0;
    if (ghostty_terminal_grid_ref(st->terminal, point, &ref) !=
            GHOSTTY_SUCCESS ||
        ghostty_grid_ref_graphemes(&ref, cps, 8, &n) != GHOSTTY_SUCCESS ||
        n == 0) {
      out[len++] = ' ';
      continue;
    }
    out[len++] = cps[0] < 0x80 ? (uint8_t)cps[0] : ' ';
  }
  col_offset[cols] = len;
  return len;
}

// Returns the link under a viewport cell, or NULL when there is none.
//
// An OSC 8 hyperlink is authoritative: the program named the target, so what
// the cell renders as does not matter. Failing that the row is scanned for a
// bare URL, which is what makes this work in a TUI that never emitted OSC 8.
JNIEXPORT jstring JNICALL
Java_com_remotly_app_terminal_RemotlyTerminal_nativeLinkAt(JNIEnv *env, jclass,
                                                           jlong handle,
                                                           jint col,
                                                           jint row) {
  RemotlyTerm *st = from_handle(handle);
  if (!st || col < 0 || row < 0) return NULL;

  uint16_t cols = 0, rows = 0;
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_COLS, &cols);
  ghostty_terminal_get(st->terminal, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
  if (cols == 0 || rows == 0 || col >= (jint)cols || row >= (jint)rows) {
    return NULL;
  }

  // An explicit hyperlink wins, whatever the cell happens to render as.
  GhosttyPoint point = {0};
  point.tag = GHOSTTY_POINT_TAG_VIEWPORT;
  point.value.coordinate.x = (uint16_t)col;
  point.value.coordinate.y = (uint32_t)row;
  GhosttyGridRef ref = {0};
  if (ghostty_terminal_grid_ref(st->terminal, point, &ref) == GHOSTTY_SUCCESS) {
    uint8_t uri[REMOTLY_MAX_URL];
    size_t uri_len = 0;
    if (ghostty_grid_ref_hyperlink_uri(&ref, uri, sizeof(uri) - 1, &uri_len) ==
            GHOSTTY_SUCCESS &&
        uri_len > 0 && uri_len < sizeof(uri)) {
      uri[uri_len] = '\0';
      return (*env)->NewStringUTF(env, (const char *)uri);
    }
  }

  if (cols > REMOTLY_MAX_COLS_SCAN) return NULL;
  uint8_t line[REMOTLY_MAX_COLS_SCAN + 1];
  size_t col_offset[REMOTLY_MAX_COLS_SCAN + 1];
  size_t len = read_row_ascii(st, (uint32_t)row, cols, line, col_offset);
  if (len == 0) return NULL;
  size_t at = col_offset[col];
  if (at >= len || !url_byte(line[at])) return NULL;

  // Expand to the whole run around the tap, then require a scheme at its
  // start. Matching a scheme and scanning forward instead would miss a tap in
  // the middle of the URL, which is most of its length.
  size_t start = at;
  while (start > 0 && url_byte(line[start - 1])) start--;
  size_t end = at;
  while (end + 1 < len && url_byte(line[end + 1])) end++;

  static const char *const schemes[] = {"https://", "http://", "ftp://",
                                        "ssh://",   "file://", "mailto:"};
  size_t run_len = end - start + 1;
  const uint8_t *run = line + start;
  size_t scheme_len = 0;
  for (size_t i = 0; i < sizeof(schemes) / sizeof(schemes[0]); i++) {
    size_t n = strlen(schemes[i]);
    if (run_len > n && memcmp(run, schemes[i], n) == 0) {
      scheme_len = n;
      break;
    }
  }
  if (scheme_len == 0) return NULL;

  // Trailing punctuation is far more often the sentence's than the URL's.
  while (run_len > scheme_len) {
    uint8_t last = run[run_len - 1];
    if (last == '.' || last == ',' || last == ';' || last == ':' ||
        last == '!' || last == '?' || last == ')' || last == ']') {
      run_len--;
      continue;
    }
    break;
  }
  if (run_len <= scheme_len) return NULL;

  char out[REMOTLY_MAX_URL];
  if (run_len >= sizeof(out)) return NULL;
  memcpy(out, run, run_len);
  out[run_len] = '\0';
  return (*env)->NewStringUTF(env, out);
}

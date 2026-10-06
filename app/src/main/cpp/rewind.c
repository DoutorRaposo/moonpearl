// Rewind, in the style of Nintendo Switch Online: snapshots of the game state are kept in memory,
// and the player can step back (and forward again) through them, then resume from any point.
//
// Every kInterval frames the frame hook takes a snapshot (ZeldaSaveSnapshot, patch 0009) and
// stores only its difference from the previous one: the XOR of the two, run-length coded, which
// is a few KB as consecutive snapshots barely differ. XOR runs both ways, so from the newest full
// snapshot every earlier one can be rebuilt, and rebuilt forward again. While rewinding, the
// rewind hook (patch 0009) takes over the main loop on the game thread; the UI thread only sets
// the requests below.
//
// A snapshot keeps the PPU's memory but not its registers (scroll, sprites), which the game sets
// again every frame. So after loading one, a frame is run without input to show it, and the
// snapshot is loaded again when the game continues, so it resumes exactly from there.
#include <android/log.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef void SaveLoadFunc(void *ctx, void *data, size_t data_size);  // snes/saveload.h
void ZeldaSaveSnapshot(SaveLoadFunc *func, void *ctx);  // patches/zelda3/0009-rewind-hooks.patch
void ZeldaLoadSnapshot(SaveLoadFunc *func, void *ctx);  // patches/zelda3/0009-rewind-hooks.patch
bool ZeldaRunFrame(int inputs);                         // src/zelda_rtl.c

enum {
  kInterval = 6,                // frames between snapshots: 10 per second
  kMaxEntries = 600,            // 60 seconds
  kMaxBytes = 48 * 1024 * 1024, // and never more memory than this
  kStepMs = 50,                 // while held: 20 snapshots a second, twice real time,
  kFastStepMs = 25,             // and four times after
  kFastAfterMs = 2000,          // two seconds of holding
};

typedef struct Delta {
  uint8_t *data;
  size_t size;
} Delta;

// Game thread state.
static uint8_t *g_cur;        // the newest snapshot, or the one shown while rewinding
static uint8_t *g_tmp;        // where a new snapshot is written
static size_t g_size, g_tmp_cap, g_tmp_used;
static Delta g_hist[kMaxEntries];  // ring: oldest at g_head; each turns g_cur into the one before
static int g_head, g_count;
static Delta g_redo[kMaxEntries];  // stack of steps taken back, to go forward again
static int g_redo_count;
static size_t g_bytes;
static int g_frames;
static bool g_active;
static bool g_ran_past;            // a frame was run after loading g_cur, to show it
static int g_held_dir;
static int64_t g_held_since, g_last_step;

// Requests from the UI thread.
static volatile int g_enabled = 1;
static volatile int g_want_active;
static volatile int g_want_cancel;
static volatile int g_direction;
static volatile int g_seek = -1;  // snapshots back from the present to jump to, or -1

static void SaveToTmp(void *ctx, void *data, size_t size) {
  (void)ctx;
  if (g_tmp_used + size > g_tmp_cap) {
    size_t cap = (g_tmp_used + size) * 2;
    uint8_t *p = realloc(g_tmp, cap);
    if (p == NULL)
      abort();
    g_tmp = p;
    g_tmp_cap = cap;
  }
  memcpy(g_tmp + g_tmp_used, data, size);
  g_tmp_used += size;
}

typedef struct Reader {
  const uint8_t *p, *end;
} Reader;

static void LoadFrom(void *ctx, void *data, size_t size) {
  Reader *r = ctx;
  if ((size_t)(r->end - r->p) < size)
    abort();
  memcpy(data, r->p, size);
  r->p += size;
}

static void LoadCurrent(void) {
  Reader r = { g_cur, g_cur + g_size };
  ZeldaLoadSnapshot(&LoadFrom, &r);
  g_ran_past = false;
}

static int64_t NowMs(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static uint8_t *PutVarint(uint8_t *p, size_t v) {
  for (; v >= 0x80; v >>= 7)
    *p++ = (uint8_t)(v | 0x80);
  *p++ = (uint8_t)v;
  return p;
}

static size_t GetVarint(const uint8_t **p) {
  size_t v = 0;
  for (int shift = 0;; shift += 7) {
    uint8_t b = *(*p)++;
    v |= (size_t)(b & 0x7f) << shift;
    if (!(b & 0x80))
      return v;
  }
}

// Codes a ^ b as (zero run, literal run, literal bytes)... Short zero runs stay in the literals.
static Delta MakeDelta(const uint8_t *a, const uint8_t *b, size_t n) {
  uint8_t *out = malloc(n + n / 64 + 32), *o = out;
  size_t i = 0;
  while (i < n) {
    size_t z = i;
    while (z < n && a[z] == b[z])
      z++;
    size_t lit = z, end = z;
    while (end < n) {
      if (a[end] != b[end]) {
        end++;
        lit = end;
      } else if (end - lit >= 8) {
        break;
      } else {
        end++;
      }
    }
    o = PutVarint(o, z - i);
    o = PutVarint(o, lit - z);
    for (size_t k = z; k < lit; k++)
      *o++ = a[k] ^ b[k];
    i = lit;
  }
  Delta d = { realloc(out, (size_t)(o - out) ? (size_t)(o - out) : 1), (size_t)(o - out) };
  return d;
}

// XORs a delta into g_cur: turns a snapshot into its neighbour, either way.
static void ApplyDelta(const Delta *d) {
  const uint8_t *p = d->data, *end = d->data + d->size;
  size_t pos = 0;
  while (p < end) {
    pos += GetVarint(&p);
    size_t lit = GetVarint(&p);
    for (size_t k = 0; k < lit; k++)
      g_cur[pos++] ^= *p++;
  }
}

static void FreeDelta(Delta *d) {
  free(d->data);
  d->data = NULL;
  g_bytes -= d->size;
  d->size = 0;
}

static void DropOldest(void) {
  FreeDelta(&g_hist[g_head]);
  g_head = (g_head + 1) % kMaxEntries;
  g_count--;
}

static void ClearRedo(void) {
  while (g_redo_count > 0)
    FreeDelta(&g_redo[--g_redo_count]);
}

static void ClearAll(void) {
  ClearRedo();
  while (g_count > 0)
    DropOldest();
  free(g_cur);
  g_cur = NULL;
  g_size = 0;
}

static void Capture(void) {
  g_tmp_used = 0;
  ZeldaSaveSnapshot(&SaveToTmp, NULL);
  if (g_cur == NULL || g_tmp_used != g_size) {
    ClearAll();
    g_cur = malloc(g_tmp_used);
    if (g_cur == NULL)
      return;
    memcpy(g_cur, g_tmp, g_tmp_used);
    g_size = g_tmp_used;
    return;
  }
  Delta d = MakeDelta(g_tmp, g_cur, g_size);
  while (g_count > 0 && (g_count == kMaxEntries || g_bytes + d.size > kMaxBytes))
    DropOldest();
  g_hist[(g_head + g_count) % kMaxEntries] = d;
  g_count++;
  g_bytes += d.size;
  memcpy(g_cur, g_tmp, g_size);
#ifndef NDEBUG
  static int captures;
  if (++captures % 100 == 0)
    __android_log_print(ANDROID_LOG_DEBUG, "moonpearl", "rewind: %d snapshots, %zu KB of deltas (snapshot %zu KB)",
                        g_count, g_bytes / 1024, g_size / 1024);
#endif
}

static bool StepBack(void) {
  if (g_count == 0 || g_redo_count == kMaxEntries)
    return false;
  Delta d = g_hist[(g_head + g_count - 1) % kMaxEntries];
  g_count--;
  ApplyDelta(&d);
  g_redo[g_redo_count++] = d;
  return true;
}

static bool StepForward(void) {
  if (g_redo_count == 0)
    return false;
  Delta d = g_redo[--g_redo_count];
  ApplyDelta(&d);
  g_hist[(g_head + g_count) % kMaxEntries] = d;
  g_count++;
  return true;
}

// Frame hook (game thread, before each frame).
void RewindOnFrame(void) {
  if (!g_enabled || g_active)
    return;
  if (++g_frames < kInterval)
    return;
  g_frames = 0;
#ifndef NDEBUG
  static int64_t total_us;
  static int n;
  struct timespec a, b;
  clock_gettime(CLOCK_MONOTONIC, &a);
  Capture();
  clock_gettime(CLOCK_MONOTONIC, &b);
  total_us += (b.tv_sec - a.tv_sec) * 1000000 + (b.tv_nsec - a.tv_nsec) / 1000;
  if (++n % 100 == 0)
    __android_log_print(ANDROID_LOG_DEBUG, "moonpearl", "rewind: a snapshot takes %lld us on average", (long long)(total_us / n));
#else
  Capture();
#endif
}

// State jump hook (game thread): a save state, a chapter or the autosave was loaded, or the game
// was reset. Rewinding starts over from there instead of going back across the jump.
void RewindOnStateJump(void) {
  if (g_active)
    return;
  ClearAll();
  g_frames = 0;
}

// Rewind hook (game thread, each turn of the main loop). See ZeldaSetRewindHook.
int RewindHook(void) {
  if (!g_enabled) {
    if (g_cur != NULL)
      ClearAll();
    g_active = false;
    return 0;
  }
  if (!g_active) {
    if (!g_want_active)
      return 0;
    // Entering: take the present as the newest snapshot, so the player can come back to it.
    Capture();
    g_active = true;
    g_held_dir = 0;
    g_frames = 0;
    return 2;
  }
  if (!g_want_active) {
    g_active = false;
    bool cancel = g_want_cancel;
    g_want_cancel = 0;
    if (cancel)
      while (StepForward()) {}
    // Undo the frame run to show the point, or go back to the present.
    if (cancel || g_ran_past)
      LoadCurrent();
    // Resuming from an earlier point drops the steps after it, as the game goes another way.
    ClearRedo();
    return 0;
  }
  // Dragging the bar: walk the deltas to the point (cheap XORs), then load and show it once.
  int seek = g_seek;
  if (seek >= 0) {
    g_seek = -1;
    bool moved = false;
    while (g_redo_count < seek && StepBack())
      moved = true;
    while (g_redo_count > seek && StepForward())
      moved = true;
    if (!moved)
      return 1;
    LoadCurrent();
    ZeldaRunFrame(0);
    g_ran_past = true;
    return 2;
  }
  int dir = g_direction;
  int64_t now = NowMs();
  if (dir != g_held_dir) {
    g_held_dir = dir;
    g_held_since = now;
    g_last_step = 0;
  }
  if (dir == 0)
    return 1;
  int interval = now - g_held_since >= kFastAfterMs ? kFastStepMs : kStepMs;
  if (now - g_last_step < interval)
    return 1;
  g_last_step = now;
  if (!(dir < 0 ? StepBack() : StepForward()))
    return 1;
  LoadCurrent();
  // Let the game set the PPU's registers for this point (see the top of the file).
  ZeldaRunFrame(0);
  g_ran_past = true;
  return 2;
}

JNIEXPORT void JNICALL
Java_io_github_doutorraposo_moonpearl_GameActivity_nativeRewindEnable(JNIEnv *env, jclass cls, jboolean enabled) {
  g_enabled = enabled;
}

// active: enter (true) or leave (false) rewind mode; cancel (with active false): back to the present.
JNIEXPORT void JNICALL
Java_io_github_doutorraposo_moonpearl_GameActivity_nativeRewindMode(JNIEnv *env, jclass cls, jboolean active, jboolean cancel) {
  g_direction = 0;
  g_want_cancel = cancel;
  g_want_active = active;
}

// Jump to a point: snapshots back from the present (dragging the bar).
JNIEXPORT void JNICALL
Java_io_github_doutorraposo_moonpearl_GameActivity_nativeRewindSeek(JNIEnv *env, jclass cls, jint steps_back) {
  g_direction = 0;
  g_seek = steps_back < 0 ? 0 : steps_back;
}

// -1 back, 0 hold still, 1 forward.
JNIEXPORT void JNICALL
Java_io_github_doutorraposo_moonpearl_GameActivity_nativeRewindDirection(JNIEnv *env, jclass cls, jint direction) {
  g_direction = direction;
}

// Snapshots back from the present, kept, and at most (10 a second of game time).
JNIEXPORT jintArray JNICALL
Java_io_github_doutorraposo_moonpearl_GameActivity_nativeRewindPosition(JNIEnv *env, jclass cls) {
  jint values[3] = { g_redo_count, g_count + g_redo_count, kMaxEntries };
  jintArray result = (*env)->NewIntArray(env, 3);
  if (result)
    (*env)->SetIntArrayRegion(env, result, 0, 3, values);
  return result;
}

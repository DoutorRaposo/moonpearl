// Android entry point for the zelda3 sources (upstream plus patches/zelda3).
//
// SDLActivity calls SDL_main() on its own thread. zelda3 expects to run from a
// directory holding zelda3.ini, zelda3_assets.dat and saves/, so we move into
// the app's internal storage (prepared by the Kotlin side) before calling the
// upstream main().
#include <SDL.h>
#include <android/log.h>
#include <jni.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#undef main
int main(int argc, char **argv);  // src/main.c
void ZeldaSetSpeed(int speed);    // patches/zelda3/0001-fixed-rate-fast-forward.patch

static const char kTag[] = "moonpearl";

// Upstream reports everything (including Die() messages) through stdio, which
// goes nowhere on Android. Pipe it into logcat instead.
static void *LogPump(void *arg) {
  int fd = (int)(intptr_t)arg;
  char buf[1024];
  size_t used = 0;
  for (;;) {
    ssize_t n = read(fd, buf + used, sizeof(buf) - 1 - used);
    if (n <= 0)
      break;
    used += (size_t)n;
    buf[used] = 0;
    char *line = buf, *nl;
    while ((nl = strchr(line, '\n')) != NULL) {
      *nl = 0;
      __android_log_write(ANDROID_LOG_INFO, kTag, line);
      line = nl + 1;
    }
    used = strlen(line);
    if (used == sizeof(buf) - 1) {
      __android_log_write(ANDROID_LOG_INFO, kTag, line);
      used = 0;
    } else {
      memmove(buf, line, used);
    }
  }
  return NULL;
}

static void RedirectStdioToLogcat(void) {
  int fds[2];
  if (pipe(fds) != 0)
    return;
  setvbuf(stdout, NULL, _IOLBF, 0);
  setvbuf(stderr, NULL, _IONBF, 0);
  dup2(fds[1], STDOUT_FILENO);
  dup2(fds[1], STDERR_FILENO);
  close(fds[1]);
  pthread_t thread;
  if (pthread_create(&thread, NULL, LogPump, (void *)(intptr_t)fds[0]) == 0)
    pthread_detach(thread);
}

JNIEXPORT void JNICALL
Java_io_github_doutorraposo_moonpearl_GameActivity_nativeSetSpeed(JNIEnv *env, jclass cls, jint speed) {
  ZeldaSetSpeed(speed);
}

#ifndef NDEBUG
extern unsigned char g_ram[];  // src/zelda_rtl.c; 0x1A is the game's frame counter

// Debug builds log how many game frames run per second, to check fast-forward rates.
static void *LogGameSpeed(void *arg) {
  (void)arg;
  unsigned char last = g_ram[0x1A];
  int frames = 0;
  for (int tick = 1;; tick++) {
    usleep(100 * 1000);
    unsigned char now = g_ram[0x1A];
    frames += (unsigned char)(now - last);
    last = now;
    if (tick % 10 == 0) {
      __android_log_print(ANDROID_LOG_DEBUG, kTag, "game frames/s: %d", frames);
      frames = 0;
    }
  }
  return NULL;
}
#endif

__attribute__((visibility("default")))
int SDL_main(int argc, char *argv[]) {
  RedirectStdioToLogcat();
#ifndef NDEBUG
  pthread_t speed_thread;
  if (pthread_create(&speed_thread, NULL, LogGameSpeed, NULL) == 0)
    pthread_detach(speed_thread);
#endif

  const char *dir = SDL_AndroidGetInternalStoragePath();
  if (dir == NULL || chdir(dir) != 0) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "chdir(%s) failed: %s",
                        dir ? dir : "(null)", strerror(errno));
    return 1;
  }
  __android_log_print(ANDROID_LOG_INFO, kTag, "Running from %s", dir);

  // Upstream creates a resizable window, which makes SDL unlock rotation and
  // override the manifest. Keep the game in landscape.
  SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
  return main(argc, argv);
}

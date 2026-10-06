// Android entry point for the unmodified zelda3 sources.
//
// SDLActivity calls SDL_main() on its own thread. zelda3 expects to run from a
// directory holding zelda3.ini, zelda3_assets.dat and saves/, so we move into
// the app's internal storage (prepared by the Kotlin side) before calling the
// upstream main().
#include <SDL.h>
#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#undef main
int main(int argc, char **argv);  // src/main.c

static const char kTag[] = "zelda3";

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

__attribute__((visibility("default")))
int SDL_main(int argc, char *argv[]) {
  RedirectStdioToLogcat();

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

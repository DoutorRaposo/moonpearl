// Cheats, applied on the game thread before every frame through the hook added by
// patches/zelda3/0002-frame-hook.patch.
//
// The game's RAM is laid out exactly like the SNES work RAM (g_ram = $7E0000-$7FFFFF), so
// built-in cheats write the same variables the game uses, and Pro Action Replay RAM codes
// work unchanged.
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>

extern unsigned char g_ram[];                  // src/zelda_rtl.c, 128 KiB
extern const uint8_t kMaxBombsForLevel[];      // src/hud.c, capacity per upgrade level
extern const uint8_t kMaxArrowsForLevel[];
void ZeldaSetFrameHook(void (*hook)(void));    // patches/zelda3/0002-frame-hook.patch

// Must match Cheats.kt.
enum {
  kCheat_Health = 1 << 0,
  kCheat_Magic = 1 << 1,
  kCheat_Bombs = 1 << 2,
  kCheat_Arrows = 1 << 3,
  kCheat_Rupees = 1 << 4,
  kCheat_Keys = 1 << 5,
  kCheat_WalkThroughWalls = 1 << 6,
};

// RAM offsets from src/variables.h.
enum {
  kRam_MainModule = 0x10,
  kRam_WalkThroughWalls = 0x37F,
  kRam_Features0 = 0x64C,
  kRam_Bombs = 0xF343,
  kRam_RupeesGoal = 0xF360,
  kRam_HealthCapacity = 0xF36C,
  kRam_Health = 0xF36D,
  kRam_Magic = 0xF36E,
  kRam_Keys = 0xF36F,
  kRam_BombUpgrades = 0xF370,
  kRam_ArrowUpgrades = 0xF371,
  kRam_Arrows = 0xF377,
};

enum {
  kModule_Dungeon = 7,
  kModule_Overworld = 9,
  kFeatures0_CarryMoreRupees = 2048,  // src/features.h
  kMaxCodes = 64,
  kRamSize = 0x20000,
};

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_flags;
static uint32_t g_codes[kMaxCodes];  // (offset from $7E0000) << 8 | value
static int g_num_codes;
static int g_walls_applied;

static uint16_t Read16(int at) { return g_ram[at] | g_ram[at + 1] << 8; }

static void Write16(int at, uint16_t v) {
  g_ram[at] = (uint8_t)v;
  g_ram[at + 1] = (uint8_t)(v >> 8);
}

static void ApplyBuiltIn(int flags) {
  // Walking through walls is a flag the game reads; switch it back off once.
  if (flags & kCheat_WalkThroughWalls) {
    g_ram[kRam_WalkThroughWalls] = 1;
    g_walls_applied = 1;
  } else if (g_walls_applied) {
    g_ram[kRam_WalkThroughWalls] = 0;
    g_walls_applied = 0;
  }

  // The rest edits the loaded save file, which only holds the player's state while playing.
  uint8_t module = g_ram[kRam_MainModule];
  if (module != kModule_Dungeon && module != kModule_Overworld)
    return;

  if (flags & kCheat_Health)
    g_ram[kRam_Health] = g_ram[kRam_HealthCapacity];
  if (flags & kCheat_Magic)
    g_ram[kRam_Magic] = 0x80;
  if (flags & kCheat_Bombs)
    g_ram[kRam_Bombs] = kMaxBombsForLevel[g_ram[kRam_BombUpgrades] & 7];
  if (flags & kCheat_Arrows)
    g_ram[kRam_Arrows] = kMaxArrowsForLevel[g_ram[kRam_ArrowUpgrades] & 7];
  if (flags & kCheat_Rupees) {
    uint32_t features = g_ram[kRam_Features0] | g_ram[kRam_Features0 + 1] << 8;
    uint16_t max = features & kFeatures0_CarryMoreRupees ? 9999 : 999;
    if (Read16(kRam_RupeesGoal) < max)
      Write16(kRam_RupeesGoal, max);  // the counter then rolls up, like picking up rupees
  }
  // 0xFF means "no small keys here" (outside dungeons).
  if ((flags & kCheat_Keys) && g_ram[kRam_Keys] != 0xFF && g_ram[kRam_Keys] < 1)
    g_ram[kRam_Keys] = 1;
}

static void ApplyCheats(void) {
  pthread_mutex_lock(&g_lock);
  ApplyBuiltIn(g_flags);
  for (int i = 0; i < g_num_codes; i++)
    g_ram[g_codes[i] >> 8] = (uint8_t)g_codes[i];
  pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_io_github_doutorraposo_z3_GameActivity_nativeSetCheats(JNIEnv *env, jclass cls, jint flags, jintArray codes) {
  jsize n = codes ? (*env)->GetArrayLength(env, codes) : 0;
  jint *items = n ? (*env)->GetIntArrayElements(env, codes, NULL) : NULL;

  pthread_mutex_lock(&g_lock);
  g_flags = flags;
  g_num_codes = 0;
  for (jsize i = 0; i < n && g_num_codes < kMaxCodes; i++) {
    uint32_t code = (uint32_t)items[i];
    if ((code >> 8) < kRamSize)
      g_codes[g_num_codes++] = code;
  }
  pthread_mutex_unlock(&g_lock);

  if (items)
    (*env)->ReleaseIntArrayElements(env, codes, items, JNI_ABORT);
  // Stays installed even with everything off, so walking through walls gets switched back.
  ZeldaSetFrameHook(ApplyCheats);
}

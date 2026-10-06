cmake_minimum_required(VERSION 3.22)
project(zelda3_android C)

if(NOT ZELDA3_DIR OR NOT SDL_DIR)
  message(FATAL_ERROR "ZELDA3_DIR and SDL_DIR must be passed by Gradle")
endif()

# --- SDL2, built as libSDL2.so (the name SDLActivity loads) ---
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_SDL2MAIN ON CACHE BOOL "" FORCE)
add_subdirectory(${SDL_DIR} SDL2 EXCLUDE_FROM_ALL)

# --- zelda3, unmodified upstream sources, built as libmain.so ---
file(GLOB ZELDA3_SOURCES CONFIGURE_DEPENDS
  ${ZELDA3_DIR}/src/*.c
  ${ZELDA3_DIR}/snes/*.c
)

add_library(main SHARED
  android_main.c
  ${ZELDA3_SOURCES}
  ${ZELDA3_DIR}/third_party/gl_core/gl_core_3_1.c
  ${ZELDA3_DIR}/third_party/opus-1.3.1-stripped/opus_decoder_amalgam.c
)

target_include_directories(main PRIVATE ${ZELDA3_DIR})
target_compile_definitions(main PRIVATE SYSTEM_VOLUME_MIXER_AVAILABLE=0)
target_compile_options(main PRIVATE -O2 -Wno-unused-value -Wno-parentheses -Wno-dangling-else)
target_link_libraries(main PRIVATE SDL2::SDL2 log m)

cmake_minimum_required(VERSION 3.22)
project(moonpearl C)

if(NOT ZELDA3_DIR OR NOT ZELDA3_PATCHES_DIR OR NOT SDL_DIR)
  message(FATAL_ERROR "ZELDA3_DIR, ZELDA3_PATCHES_DIR and SDL_DIR must be passed by Gradle")
endif()

# --- SDL2, built as libSDL2.so (the name SDLActivity loads) ---
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_SDL2MAIN ON CACHE BOOL "" FORCE)
add_subdirectory(${SDL_DIR} SDL2 EXCLUDE_FROM_ALL)

# --- zelda3, built as libmain.so ---
# The submodule stays pristine: src/ and snes/ are copied into the build tree and the
# patches in patches/zelda3 are applied to the copy. A patch that does not apply fails here.
set(ZELDA3_PATCHED ${CMAKE_CURRENT_BINARY_DIR}/zelda3-patched)
file(REMOVE_RECURSE ${ZELDA3_PATCHED})
file(COPY ${ZELDA3_DIR}/src ${ZELDA3_DIR}/snes DESTINATION ${ZELDA3_PATCHED})
file(GLOB ZELDA3_PATCHES ${ZELDA3_PATCHES_DIR}/*.patch)
list(SORT ZELDA3_PATCHES)
find_package(Git REQUIRED)
foreach(patch ${ZELDA3_PATCHES})
  # Stop git from finding the enclosing repository; inside one, apply would silently
  # skip paths outside the current directory. Upstream files are CRLF in its repository
  # while patches are LF, and checkouts may convert either way (core.autocrlf), so line
  # endings are ignored when matching.
  execute_process(
    COMMAND ${CMAKE_COMMAND} -E env GIT_CEILING_DIRECTORIES=${CMAKE_CURRENT_BINARY_DIR}
            ${GIT_EXECUTABLE} apply --verbose --ignore-whitespace ${patch}
    WORKING_DIRECTORY ${ZELDA3_PATCHED}
    RESULT_VARIABLE result
    ERROR_VARIABLE output)
  if(NOT result EQUAL 0 OR NOT output MATCHES "Applied patch")
    message(FATAL_ERROR "Could not apply ${patch}:
${output}")
  endif()
endforeach()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${ZELDA3_PATCHES})

file(GLOB ZELDA3_SOURCES
  ${ZELDA3_PATCHED}/src/*.c
  ${ZELDA3_PATCHED}/snes/*.c
)

add_library(main SHARED
  android_main.c
  cheats.c
  ${ZELDA3_SOURCES}
  ${ZELDA3_DIR}/third_party/gl_core/gl_core_3_1.c
  ${ZELDA3_DIR}/third_party/opus-1.3.1-stripped/opus_decoder_amalgam.c
)

target_include_directories(main PRIVATE ${ZELDA3_PATCHED} ${ZELDA3_DIR})
target_compile_definitions(main PRIVATE SYSTEM_VOLUME_MIXER_AVAILABLE=0)
target_compile_options(main PRIVATE -O2 -Wno-unused-value -Wno-parentheses -Wno-dangling-else)
target_link_libraries(main PRIVATE SDL2::SDL2 log m)

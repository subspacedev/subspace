# SPDX-License-Identifier: AGPL-3.0-or-later
#
# x86 (32-bit) is omitted deliberately: it is effectively dead on real devices
# and doubles native build time. arm64-v8a is what ships; the other two exist
# for older hardware and emulators.
APP_ABI := arm64-v8a armeabi-v7a x86_64

# Matches minSdk 26 in subspace.android.library.
APP_PLATFORM := android-26

# Pure C, no C++ runtime needed.
APP_STL := none

# Build location must not change the library (M10-A spec §6.5). Without these, the
# checkout path reaches the .so through DWARF (comp_dir, file names) and __FILE__,
# and through the build ID, which the linker hashes over those sections before
# :app strips them. So a stripped library still differs by path.
# -ffile-prefix-map is -fdebug-prefix-map plus -fmacro-prefix-map
# (https://clang.llvm.org/docs/ClangCommandLineReference.html).
# APP_CFLAGS, not LOCAL_CFLAGS: it reaches every module, including hev's three
# vendored static libraries, whose makefiles reset LOCAL_CFLAGS with CLEAR_VARS
# and cannot be appended to without patching them
# (https://developer.android.com/ndk/guides/application_mk#app_cflags).
# The repo root is four levels above this file (service/src/main/jni). The NDK's
# own location is mapped too, so an SDK installed elsewhere does not leak its path.
SUBSPACE_ROOT := $(abspath $(call my-dir)/../../../..)
APP_CFLAGS += -ffile-prefix-map=$(SUBSPACE_ROOT)=.
APP_CFLAGS += -ffile-prefix-map=$(abspath $(NDK_ROOT))=/ndk

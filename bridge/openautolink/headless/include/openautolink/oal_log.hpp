#pragma once
// oal_log.hpp — Version-prefixed logging for the bridge binary.
//
// EVERY log line from the bridge includes the version so you never
// have to guess which binary is running when reading journal output.
//
// Usage:
//   BLOG << "[OAL] app connected" << std::endl;          // stream style
//   oal_log("[OAL] app connected from %s\n", ip);        // printf style

#include <cstdarg>
#include <cstdio>
#include <iostream>

// Compile-time prefix: "[bridge vX.Y.Z] "
#define OAL_LOG_PREFIX "[bridge " OAL_BRIDGE_VERSION "] "

// Stream-style: BLOG << "message" << std::endl;
// Outputs: [bridge v0.1.115] message
#define BLOG (std::cerr << OAL_LOG_PREFIX)

// printf-style: oal_log("[OAL] thing happened: %s\n", detail);
// Outputs: [bridge v0.1.115] [OAL] thing happened: detail
inline void oal_log(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
inline void oal_log(const char* fmt, ...) {
    fputs(OAL_LOG_PREFIX, stderr);
    va_list args;
    va_start(args, fmt);
    vfprintf(stderr, fmt, args);
    va_end(args);
}

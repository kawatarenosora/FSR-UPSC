// Minimal NGX shim surface. Real file lives in the reference repo as
// src/native/fsrupsc_ngx.cpp; this stub only pins the ABI the Java side needs.
//
// Exports (C linkage): pzngx_init, pzngx_error, pzngx_optimal, pzngx_create,
//   pzngx_image_fd, pzngx_image_bytes, pzngx_semaphore_fd, pzngx_evaluate,
//   pzngx_destroy
//
// MSVC build (x64, from a VS developer prompt):
//   cl /LD /O2 /std:c++17 /DNOMINMAX fsrupsc_ngx.cpp ^
//      /I"%DLSS_SDK%\include" "%DLSS_SDK%\lib\Windows_x86_64\nvsdk_ngx.lib" ^
//      /Fe:fsrupsc_ngx64.dll
// Output fsrupsc_ngx64.dll + NVIDIA's nvngx_dlss.dll go to <game>/natives/.
// Linux equivalent: g++ -O2 -shared -fPIC -std=c++17 -fvisibility=hidden ...
// Until the DLL exists, NgxSupport.ready()==false and the mod runs as FSR1.

#include <cstdint>

#if defined(_WIN32)
#define PZNGX_EXPORT __declspec(dllexport)
#else
#define PZNGX_EXPORT __attribute__((visibility("default")))
#endif

extern "C" {
PZNGX_EXPORT int pzngx_init(void) { return -1; }
PZNGX_EXPORT const char* pzngx_error(void) { return "stub: not built"; }
PZNGX_EXPORT int pzngx_optimal(void) { return -1; }
PZNGX_EXPORT int pzngx_create(void) { return -1; }
PZNGX_EXPORT int pzngx_image_fd(void) { return -1; }
PZNGX_EXPORT int pzngx_image_bytes(void) { return -1; }
PZNGX_EXPORT int pzngx_semaphore_fd(void) { return -1; }
PZNGX_EXPORT int pzngx_evaluate(void) { return -1; }
PZNGX_EXPORT void pzngx_destroy(void) {}
}

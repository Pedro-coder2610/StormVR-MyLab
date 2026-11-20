// platform.h - camada de compatibilidade Windows / Linux do driver StormVR.
// No Windows nada muda (inclui winsock2/windows.h). No Linux (DroidDeck, aarch64) cria os poucos
// equivalentes que o driver usa: sockets UDP, Sleep, arquivos de configuracao, memoria compartilhada.
#pragma once
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <chrono>
#include <string>
#include <thread>

#ifdef _WIN32
  #ifndef WIN32_LEAN_AND_MEAN
  #define WIN32_LEAN_AND_MEAN
  #endif
  #include <winsock2.h>
  #include <ws2tcpip.h>
  #include <windows.h>
  #include <mmsystem.h>
  #define STORM_EXPORT extern "C" __declspec(dllexport)
  typedef int StormSockLen;
  inline void SetRecvTimeoutMs(SOCKET s, int ms) {
      DWORD to = (DWORD)ms;
      setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&to), sizeof(to));
  }
#else
  #include <sys/types.h>
  #include <sys/socket.h>
  #include <sys/stat.h>
  #include <sys/mman.h>
  #include <netinet/in.h>
  #include <arpa/inet.h>
  #include <fcntl.h>
  #include <unistd.h>
  #include <errno.h>
  #include <strings.h>

  #define STORM_EXPORT extern "C" __attribute__((visibility("default")))

  typedef int      SOCKET;
  typedef uint32_t DWORD;
  typedef int32_t  LONG;
  typedef uint64_t ULONGLONG;
  typedef socklen_t StormSockLen;
  constexpr SOCKET INVALID_SOCKET = -1;
  constexpr int    SOCKET_ERROR   = -1;
  constexpr int    MAX_PATH       = 4096;

  // Codigos de tecla do Windows (so para manter o mesmo formato de configuracao; no Linux o teclado nao e lido)
  enum { VK_SHIFT = 0x10, VK_CONTROL = 0x11, VK_PRIOR = 0x21, VK_NEXT = 0x22, VK_END = 0x23, VK_HOME = 0x24,
         VK_LEFT = 0x25, VK_UP = 0x26, VK_RIGHT = 0x27, VK_DOWN = 0x28, VK_INSERT = 0x2D, VK_DELETE = 0x2E,
         VK_NUMPAD0 = 0x60, VK_NUMPAD2 = 0x62, VK_NUMPAD4 = 0x64, VK_NUMPAD5 = 0x65, VK_NUMPAD6 = 0x66,
         VK_NUMPAD8 = 0x68, VK_ADD = 0x6B, VK_SCROLL = 0x91 };

  struct WSADATA { int unused; };
  #define MAKEWORD(a, b) 0
  inline int  WSAStartup(int, WSADATA*) { return 0; }
  inline void WSACleanup() {}
  inline int  WSAGetLastError() { return errno; }
  inline int  closesocket(SOCKET s) { return close(s); }

  inline void SetRecvTimeoutMs(SOCKET s, int ms) {
      timeval tv; tv.tv_sec = ms / 1000; tv.tv_usec = (ms % 1000) * 1000;
      setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  }
  inline void Sleep(unsigned ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }
  inline ULONGLONG GetTickCount64() {
      return (ULONGLONG)std::chrono::duration_cast<std::chrono::milliseconds>(
                 std::chrono::steady_clock::now().time_since_epoch()).count();
  }
  inline LONG InterlockedCompareExchange(volatile LONG* p, LONG exchange, LONG comparand) {
      return __sync_val_compare_and_swap(p, comparand, exchange);
  }
  inline unsigned timeBeginPeriod(unsigned) { return 0; }
  inline unsigned timeEndPeriod(unsigned) { return 0; }
  template <size_t N> inline void strcat_s(char (&d)[N], const char* s) { strncat(d, s, N - strlen(d) - 1); }
  inline int fopen_s(FILE** f, const char* path, const char* mode) { *f = fopen(path, mode); return *f ? 0 : errno; }
#endif

namespace kbt {

// Pasta de dados do StormVR (external.ini / keys.ini / state.ini).
//   Windows: %LOCALAPPDATA%\StormVR
//   Linux:   $STORMVR_DIR, senao $XDG_CONFIG_HOME/StormVR, senao $HOME/.config/StormVR
inline std::string DataDir(bool create = true) {
#ifdef _WIN32
    char base[MAX_PATH] = {};
    const DWORD n = GetEnvironmentVariableA("LOCALAPPDATA", base, MAX_PATH);
    const std::string dir = (n ? std::string(base) : std::string(".")) + "\\StormVR";
    if (create) CreateDirectoryA(dir.c_str(), nullptr);
    return dir;
#else
    std::string dir;
    if (const char* e = getenv("STORMVR_DIR")) dir = e;
    else if (const char* x = getenv("XDG_CONFIG_HOME")) dir = std::string(x) + "/StormVR";
    else if (const char* h = getenv("HOME")) dir = std::string(h) + "/.config/StormVR";
    else dir = "/tmp/StormVR";
    if (create) {
        std::string p;
        for (size_t i = 0; i <= dir.size(); ++i) {
            if (i == dir.size() || (dir[i] == '/' && i > 0)) mkdir(dir.substr(0, i).c_str(), 0700);
        }
    }
    return dir;
#endif
}
inline std::string DataFile(const char* name) {
#ifdef _WIN32
    return DataDir(true) + "\\" + name;
#else
    return DataDir(true) + "/" + name;
#endif
}

// Data de modificacao de um arquivo, como numero comparavel (0 = nao existe).
inline bool FileStamp(const std::string& path, uint64_t& stamp) {
#ifdef _WIN32
    WIN32_FILE_ATTRIBUTE_DATA fa;
    if (!GetFileAttributesExA(path.c_str(), GetFileExInfoStandard, &fa)) return false;
    stamp = ((uint64_t)fa.ftLastWriteTime.dwHighDateTime << 32) | fa.ftLastWriteTime.dwLowDateTime;
    return true;
#else
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return false;
    stamp = (uint64_t)st.st_mtim.tv_sec * 1000000000ull + (uint64_t)st.st_mtim.tv_nsec;
    return true;
#endif
}
inline void ReplaceFile(const std::string& tmp, const std::string& dst) {
#ifdef _WIN32
    MoveFileExA(tmp.c_str(), dst.c_str(), MOVEFILE_REPLACE_EXISTING);
#else
    std::rename(tmp.c_str(), dst.c_str());
#endif
}

#ifndef _WIN32
// Arquivo de memoria compartilhada driver <-> painel (mesmo papel do "Local\StormVRLive" do Windows).
// $STORMVR_LIVE, senao /dev/shm/stormvr-live-<uid>, senao /tmp/stormvr-live-<uid>. O painel Web usa a mesma regra.
inline std::string LivePath() {
    if (const char* e = getenv("STORMVR_LIVE")) return e;
    const char* base = access("/dev/shm", W_OK) == 0 ? "/dev/shm" : "/tmp";
    return std::string(base) + "/stormvr-live-" + std::to_string((unsigned)getuid());
}
#endif

} // namespace kbt

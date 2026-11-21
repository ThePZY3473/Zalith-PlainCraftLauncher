/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 本文件为 ZPCL Launcher 新增：为 Android 8.0（API 26）以下的 bionic 补齐
 * 一组缺失的 libc 接口，使预编译的 NG-GL4ES / OpenAL / Zink / ANGLE / OSMesa /
 * shaderc 等原生库能在 Android 7 上正常 dlopen。
 *
 * 这些符号在 Android 8.0 才被加入 bionic；本 shim 以 RTLD_GLOBAL 预先加载后，
 * 后续 dlopen 的库即可解析到它们。API 26+ 设备上 libc 的同名符号先进入全局
 * 作用域，因此不会被本 shim 覆盖。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。
 */

#include <android/log.h>
#include <pthread.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

/* ------------------------------------------------------------------------- */
/* locale 系列（bionic 于 API 26 引入）                                       */
/* ------------------------------------------------------------------------- */

// bionic 的 <langinfo.h> 在 API 26 才存在，这里自行定义最小实现所需的类型和常量。
typedef int nl_item;

// 与 bionic 的 _NL_CTYPE_CODESET_NAME 一致。
enum { SHIM_CODESET = 14 };

static int dummy_locale;
static __thread void *current_locale = &dummy_locale;

void *newlocale(int category_mask, const char *locale, void *base) {
    (void) category_mask;
    (void) locale;
    (void) base;
    return &dummy_locale;
}

void *duplocale(void *locale) {
    (void) locale;
    return &dummy_locale;
}

void freelocale(void *locale) {
    (void) locale;
}

void *uselocale(void *locale) {
    void *old = current_locale;
    if (locale != NULL) {
        current_locale = locale;
    }
    return old;
}

char *nl_langinfo(nl_item item) {
    // 绝大多数调用只关心 CODESET，返回 UTF-8 即可；其余给空串。
    return item == SHIM_CODESET ? (char *) "UTF-8" : (char *) "";
}

char *nl_langinfo_l(nl_item item, void *locale) {
    (void) locale;
    return nl_langinfo(item);
}

__attribute__((constructor)) static void shim_loaded(void) {
    __android_log_print(ANDROID_LOG_INFO, "locale_shim",
                        "liblocale_shim.so loaded (pid=%d)", (int) getpid());
}

/* ------------------------------------------------------------------------- */
/* 线程名（bionic 于 API 26 引入）                                            */
/* ------------------------------------------------------------------------- */

int pthread_setname_np(pthread_t thread, const char *name) {
    (void) thread;
    return prctl(PR_SET_NAME, (unsigned long) name, 0, 0, 0) == 0 ? 0 : -1;
}

int pthread_getname_np(pthread_t thread, char *name, size_t len) {
    (void) thread;
    if (name == NULL || len == 0) return -1;
    name[0] = '\0';
    return 0;
}

/* ------------------------------------------------------------------------- */
/* memfd_create（bionic 于 API 28 引入，ANGLE 会用到）                        */
/* ------------------------------------------------------------------------- */

#ifndef __NR_memfd_create
#if defined(__aarch64__)
#define __NR_memfd_create 279
#elif defined(__arm__)
#define __NR_memfd_create 385
#elif defined(__x86_64__)
#define __NR_memfd_create 319
#elif defined(__i386__)
#define __NR_memfd_create 356
#endif
#endif

int memfd_create(const char *name, unsigned int flags) {
    return (int) syscall(__NR_memfd_create, name, flags);
}

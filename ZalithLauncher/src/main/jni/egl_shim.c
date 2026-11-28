/*
 * ZPCL Launcher —— Zalith Launcher 2 的修改版（非官方）。
 * 本文件为 ZPCL Launcher 新增：插在 SDL3 与渲染器之间的一层 EGL 转发库。
 *
 * 背景：
 *   Cleanroom 用 SDL 创建并持有 OpenGL 上下文，LWJGL2 需要「接管」这个上下文
 *   （Display.adoptContext），接管时会调用 SDL_GL_GetAttribute(SDL_GL_DEPTH_SIZE)。
 *   SDL3 在 EGL 后端下把该属性翻译成桌面 GL 的 GL_DEPTH_BITS(0xD56)，直接
 *   glGetIntegerv 问驱动，并检查 glGetError()。
 *
 *   GLES 规范里没有 GL_DEPTH_BITS / GL_STENCIL_BITS / GL_*_BITS 这些枚举，
 *   严格实现的驱动会返回 GL_INVALID_ENUM。SDL 据此判定查询失败，
 *   Cleanroom 抛 SDLException，LWJGL 抛 LWJGLException，游戏在
 *   「Initializing game」阶段崩溃。
 *
 *   桌面 GL 模拟层（如 GL4ES / Krypton）会自己实现这些枚举，所以能跑；
 *   MobileGlues 对未知 pname 直接透传给驱动，于是崩。
 *
 * 做法：
 *   把 SDL_EGL_LIBRARY 指向本库，本库再按 EGLSHIM_REAL_LIB 去 dlopen 真正的
 *   EGL 入口（可能是渲染器本身，也可能是 SimpleFPEWrapper）。SDL 拿到本库的
 *   eglGetProcAddress 后，glGetIntegerv 被替换为本地实现：
 *     - 桌面 GL 的位深枚举返回合理常量（RGBA 8/8/8/8、depth 24、stencil 8）；
 *     - 其余 pname 原样转发给真正实现。
 *   其它 EGL 入口全部一对一转发，行为不变。
 *
 * 本程序为自由软件：你可以依据 GNU 通用公共许可证第 3 版（或更高版本）重新发布
 * 和/或修改它。
 */

#define EGL_EGLEXT_PROTOTYPES 1

#include <dlfcn.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <android/log.h>

#define TAG "EGLShim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* 真实 EGL 库的懒加载                                                */
/* ------------------------------------------------------------------ */

static void *g_real_handle;
static int g_real_tried;

static void *real_handle(void)
{
    if (g_real_tried) {
        return g_real_handle;
    }
    g_real_tried = 1;

    const char *path = getenv("EGLSHIM_REAL_LIB");
    if (path == NULL || *path == '\0') {
        LOGE("EGLSHIM_REAL_LIB 未设置，EGL 调用将全部失败");
        return NULL;
    }

    g_real_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (g_real_handle == NULL) {
        LOGE("dlopen(%s) 失败: %s", path, dlerror());
    } else {
        LOGI("已加载真实 EGL 库: %s", path);
    }
    return g_real_handle;
}

static void *real_sym(const char *name)
{
    void *handle = real_handle();
    if (handle == NULL) {
        return NULL;
    }
    return dlsym(handle, name);
}

static PFNEGLGETPROCADDRESSPROC g_real_get_proc_address;

static PFNEGLGETPROCADDRESSPROC real_get_proc_address(void)
{
    if (g_real_get_proc_address == NULL) {
        g_real_get_proc_address =
                (PFNEGLGETPROCADDRESSPROC) real_sym("eglGetProcAddress");
    }
    return g_real_get_proc_address;
}

/* ------------------------------------------------------------------ */
/* glGetIntegerv：拦截桌面 GL 的位深查询                              */
/* ------------------------------------------------------------------ */

/* GLES 头文件不提供这些桌面 GL 枚举，这里按 glext.h 的定义补上 */
#define GL_RED_BITS      0x0D52
#define GL_GREEN_BITS    0x0D53
#define GL_BLUE_BITS     0x0D54
#define GL_ALPHA_BITS    0x0D55
#define GL_DEPTH_BITS    0x0D56
#define GL_STENCIL_BITS  0x0D57

/* GLES 头文件没有桌面 GL 的 PFNGL*PROC 别名，这里自行定义 */
typedef void (*eglshim_gl_get_integerv_t)(GLenum pname, GLint *params);

static eglshim_gl_get_integerv_t g_real_gl_get_integer_v;
static int g_real_gl_get_integer_v_tried;

static eglshim_gl_get_integerv_t real_gl_get_integer_v(void)
{
    if (g_real_gl_get_integer_v_tried) {
        return g_real_gl_get_integer_v;
    }
    g_real_gl_get_integer_v_tried = 1;

    PFNEGLGETPROCADDRESSPROC get_proc = real_get_proc_address();
    if (get_proc != NULL) {
        g_real_gl_get_integer_v =
                (eglshim_gl_get_integerv_t) get_proc("glGetIntegerv");
    }
    if (g_real_gl_get_integer_v == NULL) {
        g_real_gl_get_integer_v =
                (eglshim_gl_get_integerv_t) dlsym(RTLD_DEFAULT, "glGetIntegerv");
    }
    return g_real_gl_get_integer_v;
}

GL_APICALL void GL_APIENTRY glGetIntegerv(GLenum pname, GLint *params)
{
    if (params != NULL) {
        switch (pname) {
            case GL_RED_BITS:
            case GL_GREEN_BITS:
            case GL_BLUE_BITS:
            case GL_ALPHA_BITS:
                /* 默认帧缓冲按 RGBA8888 上报 */
                *params = 8;
                return;
            case GL_DEPTH_BITS:
                /* 与 EGL config 的常见取值保持一致（EGL_DEPTH_SIZE = 24） */
                *params = 24;
                return;
            case GL_STENCIL_BITS:
                *params = 8;
                return;
            default:
                break;
        }
    }

    eglshim_gl_get_integerv_t real = real_gl_get_integer_v();
    if (real != NULL) {
        real(pname, params);
    }
}

__eglMustCastToProperFunctionPointerType EGLAPIENTRY
eglGetProcAddress(const char *procname)
{
    if (procname != NULL && strcmp(procname, "glGetIntegerv") == 0) {
        return (__eglMustCastToProperFunctionPointerType) glGetIntegerv;
    }

    PFNEGLGETPROCADDRESSPROC real = real_get_proc_address();
    if (real == NULL) {
        return NULL;
    }
    return real(procname);
}

/* ------------------------------------------------------------------ */
/* EGL 入口转发                                                       */
/* ------------------------------------------------------------------ */

/*
 * 注意：这里不能用「首次调用时缓存函数指针」以外的技巧 —— 所有函数都只在
 * 主线程（SDL 的窗口线程）被调用，简单的静态缓存足够。
 */
#define EGL_SHIM(ret, name, params, args)                 \
    EGLAPI ret EGLAPIENTRY name params                    \
    {                                                     \
        typedef ret (*fn_t) params;                       \
        static fn_t fn;                                   \
        if (fn == NULL) {                                 \
            fn = (fn_t) real_sym(#name);                  \
        }                                                 \
        if (fn == NULL) {                                 \
            return (ret) 0;                               \
        }                                                 \
        return fn args;                                   \
    }

EGL_SHIM(EGLBoolean, eglBindAPI, (EGLenum api), (api))
EGL_SHIM(EGLBoolean, eglChooseConfig,
         (EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs,
          EGLint config_size, EGLint *num_config),
         (dpy, attrib_list, configs, config_size, num_config))
EGL_SHIM(EGLContext, eglCreateContext,
         (EGLDisplay dpy, EGLConfig config, EGLContext share_context,
          const EGLint *attrib_list),
         (dpy, config, share_context, attrib_list))
EGL_SHIM(EGLSurface, eglCreatePbufferSurface,
         (EGLDisplay dpy, EGLConfig config, const EGLint *attrib_list),
         (dpy, config, attrib_list))
EGL_SHIM(EGLSurface, eglCreateWindowSurface,
         (EGLDisplay dpy, EGLConfig config, EGLNativeWindowType win,
          const EGLint *attrib_list),
         (dpy, config, win, attrib_list))
EGL_SHIM(EGLSurface, eglCreatePixmapSurface,
         (EGLDisplay dpy, EGLConfig config, EGLNativePixmapType pixmap,
          const EGLint *attrib_list),
         (dpy, config, pixmap, attrib_list))
EGL_SHIM(EGLBoolean, eglDestroyContext, (EGLDisplay dpy, EGLContext ctx),
         (dpy, ctx))
EGL_SHIM(EGLBoolean, eglDestroySurface, (EGLDisplay dpy, EGLSurface surface),
         (dpy, surface))
EGL_SHIM(EGLBoolean, eglGetConfigAttrib,
         (EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint *value),
         (dpy, config, attribute, value))
EGL_SHIM(EGLBoolean, eglGetConfigs,
         (EGLDisplay dpy, EGLConfig *configs, EGLint config_size,
          EGLint *num_config),
         (dpy, configs, config_size, num_config))
EGL_SHIM(EGLDisplay, eglGetDisplay, (EGLNativeDisplayType display_id),
         (display_id))
EGL_SHIM(EGLint, eglGetError, (void), ())
EGL_SHIM(EGLDisplay, eglGetPlatformDisplay,
         (EGLenum platform, void *native_display, const EGLAttrib *attrib_list),
         (platform, native_display, attrib_list))
EGL_SHIM(EGLDisplay, eglGetPlatformDisplayEXT,
         (EGLenum platform, void *native_display, const EGLint *attrib_list),
         (platform, native_display, attrib_list))
EGL_SHIM(EGLBoolean, eglInitialize,
         (EGLDisplay dpy, EGLint *major, EGLint *minor), (dpy, major, minor))
EGL_SHIM(EGLBoolean, eglMakeCurrent,
         (EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx),
         (dpy, draw, read, ctx))
EGL_SHIM(const char *, eglQueryString, (EGLDisplay dpy, EGLint name),
         (dpy, name))
EGL_SHIM(EGLBoolean, eglQuerySurface,
         (EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint *value),
         (dpy, surface, attribute, value))
EGL_SHIM(EGLBoolean, eglSwapBuffers, (EGLDisplay dpy, EGLSurface surface),
         (dpy, surface))
EGL_SHIM(EGLBoolean, eglSwapInterval, (EGLDisplay dpy, EGLint interval),
         (dpy, interval))
EGL_SHIM(EGLBoolean, eglTerminate, (EGLDisplay dpy), (dpy))
EGL_SHIM(EGLBoolean, eglWaitGL, (void), ())
EGL_SHIM(EGLBoolean, eglWaitNative, (EGLint engine), (engine))
EGL_SHIM(EGLBoolean, eglWaitClient, (void), ())
EGL_SHIM(EGLBoolean, eglReleaseThread, (void), ())
EGL_SHIM(EGLContext, eglGetCurrentContext, (void), ())
EGL_SHIM(EGLDisplay, eglGetCurrentDisplay, (void), ())
EGL_SHIM(EGLSurface, eglGetCurrentSurface, (EGLint readdraw), (readdraw))
EGL_SHIM(EGLBoolean, eglBindTexImage,
         (EGLDisplay dpy, EGLSurface surface, EGLint buffer),
         (dpy, surface, buffer))
EGL_SHIM(EGLBoolean, eglReleaseTexImage,
         (EGLDisplay dpy, EGLSurface surface, EGLint buffer),
         (dpy, surface, buffer))
EGL_SHIM(EGLBoolean, eglSurfaceAttrib,
         (EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint value),
         (dpy, surface, attribute, value))
EGL_SHIM(EGLint, eglQueryAPI, (void), ())
EGL_SHIM(EGLBoolean, eglCopyBuffers,
         (EGLDisplay dpy, EGLSurface surface, EGLNativePixmapType target),
         (dpy, surface, target))

/* 扩展入口：SDL3 会以可选方式加载，缺失时它会自行降级；这里一并转发 */
EGL_SHIM(EGLSyncKHR, eglCreateSyncKHR,
         (EGLDisplay dpy, EGLenum type, const EGLint *attrib_list),
         (dpy, type, attrib_list))
EGL_SHIM(EGLBoolean, eglDestroySyncKHR, (EGLDisplay dpy, EGLSyncKHR sync),
         (dpy, sync))
EGL_SHIM(EGLint, eglClientWaitSyncKHR,
         (EGLDisplay dpy, EGLSyncKHR sync, EGLint flags, EGLTimeKHR timeout),
         (dpy, sync, flags, timeout))
EGL_SHIM(EGLBoolean, eglWaitSyncKHR,
         (EGLDisplay dpy, EGLSyncKHR sync, EGLint flags), (dpy, sync, flags))
EGL_SHIM(EGLBoolean, eglGetSyncAttribKHR,
         (EGLDisplay dpy, EGLSyncKHR sync, EGLint attribute, EGLint *value),
         (dpy, sync, attribute, value))
EGL_SHIM(EGLint, eglDupNativeFenceFDANDROID,
         (EGLDisplay dpy, EGLSyncKHR sync), (dpy, sync))
EGL_SHIM(EGLBoolean, eglQueryDevicesEXT,
         (EGLint max_devices, EGLDeviceEXT *devices, EGLint *num_devices),
         (max_devices, devices, num_devices))

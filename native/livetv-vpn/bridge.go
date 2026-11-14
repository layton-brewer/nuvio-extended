//go:build cgo

package main

/*
#include <jni.h>
#include <stdlib.h>

static const char* meowio_get_chars(JNIEnv* env, jstring text) {
	if (text == NULL) return NULL;
	return (*env)->GetStringUTFChars(env, text, NULL);
}
static void meowio_release_chars(JNIEnv* env, jstring text, const char* chars) {
	if (text != NULL && chars != NULL) (*env)->ReleaseStringUTFChars(env, text, chars);
}
static jstring meowio_new_string(JNIEnv* env, const char* chars) {
	return (*env)->NewStringUTF(env, chars);
}
*/
import "C"

import (
	"sync"
	"unsafe"
)

var (
	mu        sync.Mutex
	nextID    int64
	tunnels   = map[int64]*tunnel{}
	lastError string
)

func goString(env *C.JNIEnv, text C.jstring) string {
	chars := C.meowio_get_chars(env, text)
	if chars == nil {
		return ""
	}
	defer C.meowio_release_chars(env, text, chars)
	return C.GoString(chars)
}

func javaString(env *C.JNIEnv, text string) C.jstring {
	chars := C.CString(text)
	defer C.free(unsafe.Pointer(chars))
	return C.meowio_new_string(env, chars)
}

//export Java_com_meowio_livetv_LiveTvVpnNative_start
func Java_com_meowio_livetv_LiveTvVpnNative_start(env *C.JNIEnv, class C.jclass, config C.jstring) C.jlong {
	text := goString(env, config)
	t, err := startTunnel(text)
	mu.Lock()
	defer mu.Unlock()
	if err != nil {
		lastError = err.Error()
		return 0
	}
	lastError = ""
	nextID++
	tunnels[nextID] = t
	return C.jlong(nextID)
}

func find(id C.jlong) *tunnel {
	mu.Lock()
	defer mu.Unlock()
	return tunnels[int64(id)]
}

//export Java_com_meowio_livetv_LiveTvVpnNative_port
func Java_com_meowio_livetv_LiveTvVpnNative_port(env *C.JNIEnv, class C.jclass, id C.jlong) C.jint {
	if t := find(id); t != nil {
		return C.jint(t.port())
	}
	return 0
}

//export Java_com_meowio_livetv_LiveTvVpnNative_token
func Java_com_meowio_livetv_LiveTvVpnNative_token(env *C.JNIEnv, class C.jclass, id C.jlong) C.jstring {
	if t := find(id); t != nil {
		return javaString(env, t.token)
	}
	return javaString(env, "")
}

//export Java_com_meowio_livetv_LiveTvVpnNative_lastError
func Java_com_meowio_livetv_LiveTvVpnNative_lastError(env *C.JNIEnv, class C.jclass) C.jstring {
	mu.Lock()
	message := lastError
	mu.Unlock()
	return javaString(env, message)
}

//export Java_com_meowio_livetv_LiveTvVpnNative_stop
func Java_com_meowio_livetv_LiveTvVpnNative_stop(env *C.JNIEnv, class C.jclass, id C.jlong) {
	mu.Lock()
	t := tunnels[int64(id)]
	delete(tunnels, int64(id))
	mu.Unlock()
	if t != nil {
		t.close()
	}
}

func main() {}

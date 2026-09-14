/* CRT-free JNI: which applications/services Restart Manager reports as using a registered path.
 *
 * Rebuild via core/src/main/c/rebuild-windows-natives.sh (needs mingw-w64 and JAVA_HOME); see
 * windows_file_stat.c for the byte-identical rebuild constraints this file shares. The output
 * format is name, 0x1F, decimal pid, 0x1F, ... for up to MAX_PROCESSES entries, built with
 * NewString so non-ASCII process names survive. Restart Manager is an installer/update facility,
 * not a kernel handle enumerator: an empty or missing list is inconclusive, and the caller keeps
 * its path-only receipt.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>
#include <restartmanager.h>
#include "windows_jni_path.h"

#define MAX_PROCESSES 4
/* The receipt records at most MAX_PROCESSES holders. The transient Restart Manager
 * query may grow up to MAX_KNOWN_PROCESSES entries; above that bound the probe
 * returns nothing and the caller keeps its path-only receipt. */
#define MAX_KNOWN_PROCESSES 64
#define OUT_CHARS 2048
#define NAME_CHARS 60
#define SEPARATOR 0x001F

/* mingw-w64 ships no rstrtmgr import library, so the API is resolved per call with
 * LoadLibraryW/GetProcAddress against the SDK's own types. */
typedef DWORD (WINAPI *RmStartSessionFn)(DWORD *, DWORD, WCHAR *);
typedef DWORD (WINAPI *RmRegisterResourcesFn)(DWORD, UINT, LPCWSTR *, UINT, const RM_UNIQUE_PROCESS *, UINT, LPCWSTR *);
typedef DWORD (WINAPI *RmGetListFn)(DWORD, UINT *, UINT *, RM_PROCESS_INFO *, DWORD *);
typedef DWORD (WINAPI *RmEndSessionFn)(DWORD);

/* The ABI this file's output math and buffer sizing depend on; pins any future header drift. */
_Static_assert(sizeof(RM_PROCESS_INFO) == 668, "RM_PROCESS_INFO layout drifted from the Windows SDK");

static jsize append_text(jchar *out, jsize pos, jsize cap, const WCHAR *text, jsize maxChars) {
	jsize i;
	for (i = 0; i < maxChars && text[i] != 0 && pos < cap; i++) out[pos++] = (jchar) text[i];
	return pos;
}

static jsize append_uint(jchar *out, jsize pos, jsize cap, DWORD value) {
	WCHAR digits[10];
	jsize n = 0;
	while (value != 0 && n < 10) { digits[n++] = (WCHAR) (L'0' + (int) (value % 10)); value /= 10; }
	while (n > 0 && pos < cap) out[pos++] = digits[--n];
	return pos;
}

static void free_win32_path(WCHAR *wpath, int heap) {
	if (heap) HeapFree(GetProcessHeap(), 0, wpath);
}

/* JNI encodes '_' in automodpack_core as _1. javac -h emits Java_pl_skidam_automodpack_1core_utils_WindowsLockProbe_describe0. */
JNIEXPORT jstring JNICALL Java_pl_skidam_automodpack_1core_utils_WindowsLockProbe_describe0(JNIEnv *env, jclass cls, jstring jpath) {
	HMODULE module;
	RmStartSessionFn rm_start;
	RmRegisterResourcesFn rm_register;
	RmGetListFn rm_getlist;
	RmEndSessionFn rm_end;
	WCHAR stack[WIN32_JNI_STACK_PATH_CHARS];
	WCHAR *wpath;
	int heap;
	DWORD session = 0;
	WCHAR sessionKey[CCH_RM_SESSION_KEY + 2];
	LPCWSTR resource;
	RM_PROCESS_INFO *infos;
	jchar *out;
	UINT listed = MAX_PROCESSES;
	UINT needed = 0;
	DWORD rmResult = ERROR_MORE_DATA;
	jsize pos = 0;
	jstring result = NULL;
	(void) cls;
	if (jpath == NULL) return NULL;
	/* infos at four entries is ~2.7 KB and out another ~4 KB; past 4 KB mingw emits a ___chkstk_ms stack probe that the
	 * CRT-free link cannot satisfy, so all of it lives on the process heap. */
	module = LoadLibraryW(L"rstrtmgr.dll");
	if (module == NULL) return NULL;
	rm_start = (RmStartSessionFn) (void *) GetProcAddress(module, "RmStartSession");
	rm_register = (RmRegisterResourcesFn) (void *) GetProcAddress(module, "RmRegisterResources");
	rm_getlist = (RmGetListFn) (void *) GetProcAddress(module, "RmGetList");
	rm_end = (RmEndSessionFn) (void *) GetProcAddress(module, "RmEndSession");
	if (rm_start == NULL || rm_register == NULL || rm_getlist == NULL || rm_end == NULL) {
		FreeLibrary(module);
		return NULL;
	}
	if (win32_jni_path(env, jpath, stack, WIN32_JNI_STACK_PATH_CHARS, &wpath, &heap) != 0) {
		FreeLibrary(module);
		return NULL;
	}
	infos = (RM_PROCESS_INFO *) HeapAlloc(GetProcessHeap(), 0, sizeof(RM_PROCESS_INFO) * MAX_PROCESSES);
	out = (jchar *) HeapAlloc(GetProcessHeap(), 0, sizeof(jchar) * OUT_CHARS);
	if (infos == NULL || out == NULL) {
		if (infos != NULL) HeapFree(GetProcessHeap(), 0, infos);
		if (out != NULL) HeapFree(GetProcessHeap(), 0, out);
		free_win32_path(wpath, heap);
		FreeLibrary(module);
		return NULL;
	}
	/* strSessionKey is an out parameter: the session generates its own key, so every session is unique by construction. */
	resource = wpath;
	if (rm_start(&session, 0, sessionKey) == ERROR_SUCCESS) {
		if (rm_register(session, 1, &resource, 0, NULL, 0, NULL) == ERROR_SUCCESS) {
			/* lpdwRebootReasons receives one operation-wide reason, not per-process entries. Restart Manager refreshes
			 * the list on every call, so the documented practice is to re-query into a larger buffer; three attempts. */
			RM_REBOOT_REASON rebootReasons = RmRebootReasonNone;
			for (int attempt = 0; attempt < 3 && rmResult == ERROR_MORE_DATA; attempt++) {
				if (attempt > 0) {
					if (needed > MAX_KNOWN_PROCESSES) break;
					RM_PROCESS_INFO *grown = (RM_PROCESS_INFO *) HeapAlloc(GetProcessHeap(), 0, sizeof(RM_PROCESS_INFO) * needed);
					if (grown == NULL) break;
					HeapFree(GetProcessHeap(), 0, infos);
					infos = grown;
					listed = needed;
					needed = 0;
				}
				rmResult = rm_getlist(session, &needed, &listed, infos, (LPDWORD) &rebootReasons);
			}
			if (rmResult == ERROR_SUCCESS) {
				UINT i;
				if (listed > MAX_PROCESSES) listed = MAX_PROCESSES;
				for (i = 0; i < listed && pos < OUT_CHARS - 80; i++) {
					if (i > 0) out[pos++] = SEPARATOR;
					pos = append_text(out, pos, OUT_CHARS - 72, infos[i].strAppName, NAME_CHARS);
					out[pos++] = SEPARATOR;
					pos = append_uint(out, pos, OUT_CHARS - 8, infos[i].Process.dwProcessId);
				}
				if (pos > 0) result = (*env)->NewString(env, out, pos);
			}
		}
		rm_end(session);
	}
	HeapFree(GetProcessHeap(), 0, out);
	HeapFree(GetProcessHeap(), 0, infos);
	free_win32_path(wpath, heap);
	FreeLibrary(module);
	return result;
}

/* Whether a DELETE-capable open is refused by an existing share mode. Success means this process can
 * take DELETE now; ERROR_SHARING_VIOLATION means another opener did not share delete (the usual
 * File Explorer case). Other failures (missing path, access denied, bad name) are not evidence of a
 * holder. This is not equivalent to "ATOMIC_MOVE will fail": MoveFileEx can also succeed via
 * DELETE_CHILD on the parent. */
JNIEXPORT jboolean JNICALL Java_pl_skidam_automodpack_1core_utils_WindowsLockProbe_held0(JNIEnv *env, jclass cls, jstring jpath) {
	WCHAR stack[WIN32_JNI_STACK_PATH_CHARS];
	WCHAR *wpath;
	int heap;
	HANDLE handle;
	DWORD error;
	(void) cls;
	if (jpath == NULL) return JNI_FALSE;
	if (win32_jni_path(env, jpath, stack, WIN32_JNI_STACK_PATH_CHARS, &wpath, &heap) != 0) return JNI_FALSE;
	handle = CreateFileW(wpath, DELETE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, NULL);
	if (handle == INVALID_HANDLE_VALUE) {
		error = GetLastError();
		free_win32_path(wpath, heap);
		return error == ERROR_SHARING_VIOLATION ? JNI_TRUE : JNI_FALSE;
	}
	free_win32_path(wpath, heap);
	CloseHandle(handle);
	return JNI_FALSE;
}

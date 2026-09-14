/* CRT-free JNI: which processes hold a path open, via the Restart Manager API in rstrtmgr.dll.
 *
 * Rebuild via core/src/main/c/rebuild-windows-natives.sh (needs mingw-w64 and JAVA_HOME); see
 * windows_file_stat.c for the byte-identical rebuild constraints this file shares. The output
 * format is name, 0x1F, decimal pid, 0x1F, ... for up to MAX_PROCESSES entries, built with
 * NewString so non-ASCII process names survive.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>

#define MAX_PROCESSES 4
#define OUT_CHARS 2048
#define NAME_CHARS 60
#define SEPARATOR 0x001F

/* RM_PROCESS_INFO per rstmgr.h: RM_UNIQUE_PROCESS (DWORD id + FILETIME start), 255-wchar app
 * name, 63-wchar service short name. Declared here because mingw-w64 ships no import library
 * for rstrtmgr.dll, so the API is resolved per call with LoadLibraryW/GetProcAddress. */
typedef struct { DWORD dwProcessId; FILETIME ProcessStartTime; } AM_UNIQUE_PROCESS;
typedef struct { AM_UNIQUE_PROCESS Process; WCHAR strAppName[255]; WCHAR strServiceShortName[63]; } AM_PROCESS_INFO;

typedef DWORD (WINAPI *RmStartSessionFn)(DWORD *, DWORD, WCHAR *);
typedef DWORD (WINAPI *RmRegisterResourcesFn)(DWORD, UINT, LPCWSTR *, UINT, const AM_UNIQUE_PROCESS *, UINT, LPCWSTR *);
typedef DWORD (WINAPI *RmGetListFn)(DWORD, UINT *, UINT *, AM_PROCESS_INFO *, DWORD *);
typedef DWORD (WINAPI *RmEndSessionFn)(DWORD);

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

/* JNI encodes '_' in automodpack_core as _1. javac -h emits Java_pl_skidam_automodpack_1core_utils_WindowsLockProbe_describe0. */
JNIEXPORT jstring JNICALL Java_pl_skidam_automodpack_1core_utils_WindowsLockProbe_describe0(JNIEnv *env, jclass cls, jstring jpath) {
	static const WCHAR sessionKeyName[39] = L"6F98D7FD-4A65-4E17-BE4B-6D822D5A5E7C";
	HMODULE module;
	RmStartSessionFn rm_start;
	RmRegisterResourcesFn rm_register;
	RmGetListFn rm_getlist;
	RmEndSessionFn rm_end;
	const jchar *chars;
	DWORD session = 0;
	WCHAR sessionKey[39];
	LPCWSTR resource;
	AM_PROCESS_INFO *infos;
	DWORD *reasons;
	jchar *out;
	UINT listed = MAX_PROCESSES;
	UINT needed = 0;
	jsize pos = 0;
	jstring result = NULL;
	(void) cls;
	if (jpath == NULL) return NULL;
	/* infos alone is ~2.6 KB and out another ~4 KB; past 4 KB mingw emits a ___chkstk_ms stack probe that the CRT-free link cannot satisfy, so both live on the process heap. */
	module = LoadLibraryW(L"rstrtmgr.dll");
	if (module == NULL) return NULL;
	rm_start = (RmStartSessionFn) (void *) GetProcAddress(module, "RmStartSession");
	rm_register = (RmRegisterResourcesFn) (void *) GetProcAddress(module, "RmRegisterResources");
	rm_getlist = (RmGetListFn) (void *) GetProcAddress(module, "RmGetList");
	rm_end = (RmEndSessionFn) (void *) GetProcAddress(module, "RmEndSession");
	chars = (*env)->GetStringChars(env, jpath, NULL);
	if (chars == NULL || rm_start == NULL || rm_register == NULL || rm_getlist == NULL || rm_end == NULL) {
		if (chars != NULL) (*env)->ReleaseStringChars(env, jpath, chars);
		FreeLibrary(module);
		return NULL;
	}
	infos = (AM_PROCESS_INFO *) HeapAlloc(GetProcessHeap(), 0, sizeof(AM_PROCESS_INFO) * MAX_PROCESSES);
	reasons = (DWORD *) HeapAlloc(GetProcessHeap(), 0, sizeof(DWORD) * MAX_PROCESSES);
	out = (jchar *) HeapAlloc(GetProcessHeap(), 0, sizeof(jchar) * OUT_CHARS);
	if (infos == NULL || reasons == NULL || out == NULL) {
		if (infos != NULL) HeapFree(GetProcessHeap(), 0, infos);
		if (reasons != NULL) HeapFree(GetProcessHeap(), 0, reasons);
		if (out != NULL) HeapFree(GetProcessHeap(), 0, out);
		(*env)->ReleaseStringChars(env, jpath, chars);
		FreeLibrary(module);
		return NULL;
	}
	for (jsize i = 0; i < 39; i++) sessionKey[i] = sessionKeyName[i];
	resource = (LPCWSTR) chars;
	if (rm_start(&session, 0, sessionKey) == ERROR_SUCCESS) {
		if (rm_register(session, 1, &resource, 0, NULL, 0, NULL) == ERROR_SUCCESS) {
			DWORD rmResult = rm_getlist(session, &needed, &listed, infos, reasons);
			/* ERROR_MORE_DATA only means more processes than our cap; the listed prefix is still filled. */
			if (rmResult == ERROR_SUCCESS || rmResult == ERROR_MORE_DATA) {
				DWORD i;
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
	HeapFree(GetProcessHeap(), 0, reasons);
	HeapFree(GetProcessHeap(), 0, infos);
	(*env)->ReleaseStringChars(env, jpath, chars);
	FreeLibrary(module);
	return result;
}

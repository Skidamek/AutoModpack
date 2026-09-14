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
#include <restartmanager.h>

#define MAX_PROCESSES 4
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

/* JNI encodes '_' in automodpack_core as _1. javac -h emits Java_pl_skidam_automodpack_1core_utils_WindowsLockProbe_describe0. */
JNIEXPORT jstring JNICALL Java_pl_skidam_automodpack_1core_utils_WindowsLockProbe_describe0(JNIEnv *env, jclass cls, jstring jpath) {
	HMODULE module;
	RmStartSessionFn rm_start;
	RmRegisterResourcesFn rm_register;
	RmGetListFn rm_getlist;
	RmEndSessionFn rm_end;
	const jchar *chars;
	DWORD session = 0;
	WCHAR sessionKey[CCH_RM_SESSION_KEY + 2];
	LPCWSTR resource;
	RM_PROCESS_INFO *infos;
	RM_PROCESS_INFO *grown = NULL;
	DWORD *reasons;
	DWORD *grownReasons = NULL;
	jchar *out;
	UINT listed = MAX_PROCESSES;
	UINT needed = 0;
	DWORD rmResult;
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
	chars = (*env)->GetStringChars(env, jpath, NULL);
	if (chars == NULL || rm_start == NULL || rm_register == NULL || rm_getlist == NULL || rm_end == NULL) {
		if (chars != NULL) (*env)->ReleaseStringChars(env, jpath, chars);
		FreeLibrary(module);
		return NULL;
	}
	infos = (RM_PROCESS_INFO *) HeapAlloc(GetProcessHeap(), 0, sizeof(RM_PROCESS_INFO) * MAX_PROCESSES);
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
	/* strSessionKey is an out parameter: the session generates its own key, so every session is unique by construction. */
	resource = (LPCWSTR) chars;
	if (rm_start(&session, 0, sessionKey) == ERROR_SUCCESS) {
		if (rm_register(session, 1, &resource, 0, NULL, 0, NULL) == ERROR_SUCCESS) {
			rmResult = rm_getlist(session, &needed, &listed, infos, reasons);
			if (rmResult == ERROR_MORE_DATA && needed > MAX_PROCESSES && needed <= MAX_KNOWN_PROCESSES) {
				/* lpdwRebootReasons is parallel to rgAffectedApps: it receives one reason per listed entry, so it must grow to the same size before the second query. */
				grown = (RM_PROCESS_INFO *) HeapAlloc(GetProcessHeap(), 0, sizeof(RM_PROCESS_INFO) * needed);
				grownReasons = (DWORD *) HeapAlloc(GetProcessHeap(), 0, sizeof(DWORD) * needed);
				if (grown != NULL && grownReasons != NULL) {
					UINT grownListed = needed;
					UINT grownNeeded = 0;
					rmResult = rm_getlist(session, &grownNeeded, &grownListed, grown, grownReasons);
					if (rmResult == ERROR_SUCCESS) {
						HeapFree(GetProcessHeap(), 0, infos);
						HeapFree(GetProcessHeap(), 0, reasons);
						infos = grown;
						reasons = grownReasons;
						grown = NULL;
						grownReasons = NULL;
					}
				}
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
	if (grown != NULL) HeapFree(GetProcessHeap(), 0, grown);
	if (grownReasons != NULL) HeapFree(GetProcessHeap(), 0, grownReasons);
	HeapFree(GetProcessHeap(), 0, out);
	HeapFree(GetProcessHeap(), 0, reasons);
	HeapFree(GetProcessHeap(), 0, infos);
	(*env)->ReleaseStringChars(env, jpath, chars);
	FreeLibrary(module);
	return result;
}
